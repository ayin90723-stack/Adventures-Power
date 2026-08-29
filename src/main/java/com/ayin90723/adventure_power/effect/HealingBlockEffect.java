package com.ayin90723.adventure_power.effect;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.mixin.LivingEntityFieldsAccessor;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.ExecutionFinalizer;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import com.ayin90723.adventure_power.util.probe.BloodWriteEngine;
import com.ayin90723.adventure_power.util.probe.PendingVerifyRegistry;
import com.ayin90723.adventure_power.util.probe.gate.GateOracle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingUseTotemEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 禁疗之触效果 —— 禁止回血与复活（纯辅助）
 * 以 NBT 持久化标记为真实判断依据，MobEffect 仅作为视觉指示器
 */
public class HealingBlockEffect extends MobEffect {
   /** 测试日志：禁疗标记/钳制追踪（亚波伦测试用，调试完移除） */
   /** NBT 中存储禁疗之触效果到期时间的 key */
   public static final String NBT_KEY = PersistentDataKeys.HEALING_BLOCK_END_TIME;
   /** NBT 中存储强制击杀标记的 key（跨优先级传递） */
   public static final String FORCE_KILL_KEY = PersistentDataKeys.HEALING_BLOCK_FORCE_KILL;
   /** 觉醒易伤 NBT key（与禁疗标记同期到期） */
   public static final String VULN_NBT_KEY = PersistentDataKeys.HEALING_BLOCK_VULN_END;

   /**
    * 内存追踪记录：血量低点 + 到期时间。
    * <p>
    * 内存表与 NBT 构成<b>双源标记</b>：部分 Boss（如启示录亚波伦）重写
    * {@code getPersistentData()} 每次返回全新空 tag，NBT 标记写入即丢、
    * 读取永远为空。此时 {@link #isActive(LivingEntity)} 回退查内存表，
    * 保证钳制链不因 NBT 失效（代价：服务器重启后此类实体标记丢失）。
    */
   public static final class TrackedEntry {
      /** 追踪血量低点（钳制基准） */
      public float health;
      /** 到期时间（gameTime，tick） */
      public final long endTime;

      TrackedEntry(float health, long endTime) {
         this.health = health;
         this.endTime = endTime;
      }
   }

   /** 记录实体在禁疗之触期间的追踪血量与到期时间（内存表，双源之一） */
   private static final Map<UUID, TrackedEntry> TRACKED_HEALTH = new ConcurrentHashMap<>();
   /** 觉醒易伤到期时间内存表（双源之一，防 getPersistentData() 重写丢失） */
   private static final Map<UUID, Long> VULN_END = new ConcurrentHashMap<>();
   /** FORCE_KILL 标记内存表（v1.4.6 双源化）：HIGHEST 打标与 LOWEST 消费在同一次事件分发内
    *  配对，双写双清与 NBT 同步——重写 getPersistentData() 返回空 tag 的 Boss（亚波伦型）
    *  NBT 写入即丢，单源 NBT 会让死亡锁定与终局复验对这类实体双双失效。
    *  value = 打标时刻 gameTime（审查修 P3#1：事件分发被第三方处理器异常打断时 LOWEST
    *  不执行、条目永久残留，之后该 Boss 与禁疗无关的复活会被误处决——ServerTick END
    *  过期清理超窗条目） */
   private static final Map<UUID, Long> FORCE_KILL_MARKED = new ConcurrentHashMap<>();
   /** FORCE_KILL 标记过期窗口（tick）：正常分发内打标-消费同 tick 完成，窗口远大于任何合法间隔 */
   private static final long FORCE_KILL_MARK_EXPIRY = 100L;
   /** 跨维度传送宽限期：记录实体连续未在维度中找到的 tick 数，防止传送时误清理 */
   private static final Map<UUID, Integer> MISSING_TICKS = new ConcurrentHashMap<>();

   public HealingBlockEffect() {
      super(MobEffectCategory.HARMFUL, 0x8B0000); // 暗红色
   }

   public boolean isDurationEffectTick(int duration, int amplifier) {
      return true;
   }

   /** 检查实体当前是否受禁疗之触效果影响（内存快路径 → NBT → 内存回退；过期自动清理） */
   public static boolean isActive(LivingEntity entity) {
      if (entity == null || entity.level().isClientSide()) {
         return false;
      }
      long gameTime = entity.level().getGameTime();
      // 快路径（v1.4.0 审查修复）：内存表命中且未过期直接返回——本方法挂在全服
      // 每次血量写入（setHealth HEAD/RETURN）与每 tick（TAIL）的热路径上，
      // 99.99% 的实体从未被禁疗，先查内存表避免每次 NBT 查找（getPersistentData
      // 还会为从未使用的实体惰性分配空 tag）。与 onLivingTick 的快路径语义对齐
      TrackedEntry quick = TRACKED_HEALTH.get(entity.getUUID());
      if (quick != null && gameTime <= quick.endTime) {
         return true;
      }
      // ① NBT 标记优先 —— 正常实体持久化路径（服务器重启后仍可恢复）
      CompoundTag data = entity.getPersistentData();
      if (data != null && data.contains(NBT_KEY)) {
         long endTime = data.getLong(NBT_KEY);
         if (gameTime > endTime) {
            data.remove(NBT_KEY); // 过期自动清理
         } else {
            return true;
         }
      }
      // ② 内存表回退 —— 实体重写 getPersistentData() 返回空 tag 时
      //    （如启示录亚波伦 Apostle），NBT 标记写入即丢，仅能靠内存表判定
      TrackedEntry entry = TRACKED_HEALTH.get(entity.getUUID());
      return entry != null && gameTime <= entry.endTime;
   }

   /** 向目标施加禁疗之触标记，持续时间单位：tick */
   public static void apply(LivingEntity target, int durationTicks) {
      long gameTime = target.level().getGameTime();
      long endTime = gameTime + durationTicks;
      // 内存表记录（血量低点 + 到期时间）—— 双源核心：
      // getPersistentData() 被重写返回空 tag 的实体（如亚波伦）NBT 标记写入即丢，
      // isActive() 回退查此表，钳制链不因 NBT 失效
      // 基准血量用架空参照（getEffectiveHealth）：自定义血条实体（亚波伦）原版槽架空，
      // getHealthDirect 读到的是不动值，钳制基准必须取真实血量
      // v1.4.6 续期保留低点：未过期条目续期时基准取 min(旧低点, 当前读数)——防"绕过拦截
      // 的回血（字段直写，setHealth HEAD 拦不到）+ 同 tick 攻击 apply 重锚"把钳制线洗白抬高；
      // 过期条目（语义上已是新一轮禁疗）与新目标基准 = 当前读数
      UUID targetId = target.getUUID();
      TrackedEntry existing = TRACKED_HEALTH.get(targetId);
      float current = HealthUtil.getEffectiveHealth(target);
      float baseline = (existing != null && gameTime <= existing.endTime)
         ? Math.min(existing.health, current) : current;
      TRACKED_HEALTH.put(targetId, new TrackedEntry(baseline, endTime));
      // NBT 持久化（正常实体重启后可恢复；对重写 getPersistentData 的实体静默无效）
      target.getPersistentData().putLong(NBT_KEY, endTime);
      // 验证 NBT 是否真正持久化：重写 getPersistentData() 的实体（亚波伦）再次读取为空，
      // 只能依赖内存表回退；正常实体返回 true
      boolean nbtPersist = target.getPersistentData().contains(NBT_KEY);
      DebugLog.healingBlock("[禁疗] 标记写入: 低点={}hp（当前读数={}，{}） NBT持久={} endTime={}（gameTime={}）",
         baseline, current, existing != null ? "续期保留低点" : "新锚定", nbtPersist, endTime, gameTime);
      // 同时施加 MobEffect 作为视觉指示器
      MobEffect visualEffect = ModEffects.HEALING_BLOCK.get();
      if (visualEffect != null) {
         target.addEffect(new MobEffectInstance(visualEffect, durationTicks, 0, false, true));
      }
   }

   /** 获取实体当前的追踪血量（用于 Mixin 等外部调用者读取钳制基准） */
   public static Float getTrackedHealth(LivingEntity entity) {
      TrackedEntry entry = TRACKED_HEALTH.get(entity.getUUID());
      return entry != null ? entry.health : null;
   }

   /** 更新实体的追踪血量（用于 Mixin 等外部调用者在钳制后同步基准值） */
   public static void updateTrackedHealth(LivingEntity entity, float health) {
      TrackedEntry entry = TRACKED_HEALTH.get(entity.getUUID());
      if (entry != null) {
         entry.health = health;
      }
   }

   /**
    * 钳制写入（tick 末 / ServerTickEnd 终极防线共用）：先直写真血
    * （对象图插针路径，覆盖不走 setHealth 的 Boss），再走完整
    * {@code setHealth()} 链——触发覆写 Boss（如妖怪的归家灵梦：
    * setHealth → setCombatProgress → 原版槽 + progress 字段 + 网络同步包）
    * 自己的字段写入与客户端同步，血条稳定在低点而非停在回满值。
    * <p>
    * 完整链会再经过 {@code HealingBlockMixin} 的 HEAD 注入，但 tracked
    * 不满足 {@code health > tracked}，自然放行，无递归。
    */
   public static void clampBack(LivingEntity self, float tracked) {
      // v1.4.3 二十轮：清盾前置已下沉引擎 execute 磨血分支统一处理（调用点零纪律）。
      // 禁疗的额外语义：回血只发生在盾侧时，引擎清盾后读数已 ≤ 低点，execute 磨血
      // 通道的值闸/验证自然不写血（目标值=读数−0 无降向空间）——无需调用点预判
      // v1.4.2：五层改血引擎（磨血语义=写低点值）--L3 类静态容器/L4 广义写路径
      // 覆盖"真血藏在静态 Map/加密存储"的高级 Boss；引擎关闭时内部退回 setHealthLikeAny 行为不变
      BloodWriteEngine.execute(self, tracked, com.ayin90723.adventure_power.util.DebugLog.EngineCaller.HEALING_BLOCK);
      // v1.4.0 审查修复：模组内部写血必须包 INTERNAL_HEALTH_WRITE 标记（与
      // ExplorationAbilityHandler.clampHealthTo 同款）——否则一旦目标为冒险者
      // （未来开放 PVP 禁疗或外部数据包写入 NBT 标记），此调用会被自家
      // RejectHealthManipMixin cancel 且 TrueHealthMixin 反向修复回 backup，
      // 钳制与防御层形成对抗
      boolean prevInternal = HealthUtil.INTERNAL_HEALTH_WRITE.get();
      HealthUtil.INTERNAL_HEALTH_WRITE.set(true);
      try {
         self.setHealth(tracked);
      } finally {
         HealthUtil.INTERNAL_HEALTH_WRITE.set(prevInternal);
      }
   }

   /** 记录觉醒易伤到期时间（内存 + NBT 双源，与禁疗标记同理） */
   public static void applyVuln(LivingEntity target, long endTime) {
      VULN_END.put(target.getUUID(), endTime);
      target.getPersistentData().putLong(VULN_NBT_KEY, endTime);
   }

   /** 清除觉醒易伤标记（内存 + NBT） */
   public static void removeVuln(LivingEntity entity) {
      VULN_END.remove(entity.getUUID());
      entity.getPersistentData().remove(VULN_NBT_KEY);
   }

   /** 读取觉醒易伤到期时间：内存表优先，NBT 兜底（重启恢复）；无标记返回 null */
   public static Long getVulnEnd(LivingEntity entity) {
      Long endTime = VULN_END.get(entity.getUUID());
      if (endTime != null) {
         return endTime;
      }
      CompoundTag data = entity.getPersistentData();
      if (data != null && data.contains(VULN_NBT_KEY)) {
         return data.getLong(VULN_NBT_KEY);
      }
      return null;
   }

   @EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
   public static class EventHandler {
      /** 拦截所有治疗事件 */
      @SubscribeEvent
      public static void onLivingHeal(LivingHealEvent event) {
         if (isActive(event.getEntity())) {
            event.setCanceled(true);
         }
      }

      /** 每 tick 检查：过期清理追踪记录（钳制由 HealingBlockMixin.onTickTailClamp 在 tick 末尾负责） */
      @SubscribeEvent
      public static void onLivingTick(LivingTickEvent event) {
         LivingEntity entity = event.getEntity();
         if (entity.level().isClientSide()) return;
         UUID uuid = entity.getUUID();
         // 快路径：99.99% 实体从未被禁疗，先查内存追踪表，
         // 避免每 tick 对全服实体做 NBT 查找 + CHM 原子操作
         if (!TRACKED_HEALTH.containsKey(uuid)) return;
         if (!isActive(entity)) {
            // 效果已过期，清理追踪记录、觉醒易伤（内存 + NBT）与宽限期
            TRACKED_HEALTH.remove(uuid);
            VULN_END.remove(uuid);
            MISSING_TICKS.remove(uuid);
            entity.getPersistentData().remove(VULN_NBT_KEY);
         }
      }

      /** 实体加载/生成时从 NBT 回填内存表：区块卸载导致内存条目被 2-tick 宽限清理
       *  （TRACKED_HEALTH / VULN_END），而正常实体的 NBT 标记仍在——重载/回到已加载
       *  维度时回填，恢复 tick 末钳制与觉醒易伤（对重写 getPersistentData 的实体
       *  NBT 已丢、无回填源，属已知限制，下次攻击重新施加） */
      @SubscribeEvent
      public static void onEntityJoin(EntityJoinLevelEvent event) {
         if (!(event.getEntity() instanceof LivingEntity living)) return;
         if (living.level().isClientSide()) return;
         long gameTime = living.level().getGameTime();
         CompoundTag data = living.getPersistentData();
         if (data != null) {
            if (data.contains(NBT_KEY)) {
               long endTime = data.getLong(NBT_KEY);
               if (endTime > gameTime) {
                  // putIfAbsent 而非 put：内存条目存活时其钳制线更低更准（tick 末持续下移），
                  // 覆盖会短暂抬高钳制线（Boss 卸载窗口内自愈 1 tick 的量级）
                  TRACKED_HEALTH.putIfAbsent(living.getUUID(),
                     new TrackedEntry(HealthUtil.getEffectiveHealth(living), endTime));
               }
            }
            if (data.contains(VULN_NBT_KEY)) {
               long endTime = data.getLong(VULN_NBT_KEY);
               if (endTime > gameTime) {
                  VULN_END.put(living.getUUID(), endTime);
               }
            }
         }
      }

      /** 拦截不死图腾激活 */
      @SubscribeEvent
      public static void onLivingUseTotem(LivingUseTotemEvent event) {
         if (isActive(event.getEntity())) {
            event.setCanceled(true);
         }
      }

      /** 预先标记：HIGHEST 优先级记录禁疗之触实体即将死亡 */
      @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
      public static void onLivingDeathPreMark(LivingDeathEvent event) {
         if (isActive(event.getEntity())) {
            event.getEntity().getPersistentData().putBoolean(FORCE_KILL_KEY, true);
            // v1.4.6 双源：内存表兜底——重写 getPersistentData() 返回空 tag 的 Boss
            // NBT 写入即丢，单源会让 FORCE_KILL 链（死亡锁定+终局复验）对这类实体失效。
            // 复查修 P3#2：打标时刻统一用主世界时钟——超窗清理在 onServerTickEnd 用
            // overworld().getGameTime() 比对，两处基准不一致时（维度时钟偏移 ≥ 窗口）
            // 残留条目永不清理
            net.minecraft.server.MinecraftServer server = event.getEntity().getServer();
            long markedAt = server != null
                ? server.overworld().getGameTime()
                : event.getEntity().level().getGameTime();
            FORCE_KILL_MARKED.put(event.getEntity().getUUID(), markedAt);
         }
      }

      /**
       * 终极防线：在服务器 tick 末尾（所有实体 tick 全部完成后）遍历追踪表，
       * 将一切被 Boss 绕过的回血钳制回追踪值。
       * <p>
       * 针对 BossYoukaiEntity 等重写 {@code setHealth()} 不调用 super 的实体——
       * 它们的回血完全绕开 {@code @ModifyArg} Mixin 和 {@code LivingTickEvent}。
       * 本处理器在 tick 末尾执行，是时序上的最后防线。
       */
      @SubscribeEvent
      public static void onServerTickEnd(TickEvent.ServerTickEvent event) {
         if (event.phase != TickEvent.Phase.END) return;
         if (TRACKED_HEALTH.isEmpty() && FORCE_KILL_MARKED.isEmpty()) return;

         Set<UUID> found = new HashSet<>();
         for (ServerLevel level : event.getServer().getAllLevels()) {
            for (UUID uuid : new ArrayList<>(TRACKED_HEALTH.keySet())) {
               Entity entity = level.getEntity(uuid);
               if (entity instanceof LivingEntity living && living.isAlive()) {
                  found.add(uuid);
                  TrackedEntry entry = TRACKED_HEALTH.get(uuid);
                  if (entry != null) {
                     // v1.4.6：过期清理下沉本层——过期清理此前只挂 LivingTickEvent，不触发该
                     // 事件的实体（覆写 tick 不调 super 型）标记过期后仍被本层持续钳制（过度
                     // 执行，违背"到期即失效"语义）；与 onLivingTick 同款清理（内存表双源，
                     // NBT 侧读取处自带 endTime 防御）
                     if (level.getGameTime() > entry.endTime) {
                        TRACKED_HEALTH.remove(uuid);
                        VULN_END.remove(uuid);
                        continue;
                     }
                     // 架空参照读数：自定义血条实体（亚波伦）原版槽被架空，
                     // getHealthDirect 读到不动值会导致回血检测永远 false，钳制失效
                     float current = HealthUtil.getEffectiveHealth(living);
                     if (current > entry.health) {
                        DebugLog.healingBlock("[禁疗] 终极钳制(ServerTickEnd): {} > {} → 直写 {}",
                           current, entry.health, entry.health);
                        // 钳制写入：直写真血 + 走完整 setHealth 链（触发覆写 Boss
                        // 自己的字段写入与客户端网络同步，血条稳定在低点）
                        clampBack(living, entry.health);
                        current = entry.health;
                     }
                     entry.health = Math.min(current, entry.health);
                  }
               }
            }
         }
         // 清理已死亡/卸载的实体条目，防止内存泄漏
         // 使用 2 tick 宽限期，避免跨维度传送时实体短暂不在任何维度中被误清理
         for (UUID uuid : new ArrayList<>(TRACKED_HEALTH.keySet())) {
            if (!found.contains(uuid)) {
               int missing = MISSING_TICKS.getOrDefault(uuid, 0) + 1;
               if (missing >= 2) {
                  TRACKED_HEALTH.remove(uuid);
                  VULN_END.remove(uuid);
                  MISSING_TICKS.remove(uuid);
               } else {
                  MISSING_TICKS.put(uuid, missing);
               }
            } else {
               MISSING_TICKS.remove(uuid); // 找到后重置计数
            }
         }
         // 审查修 P3#1：FORCE_KILL 标记过期清理——打标（HIGHEST）与消费（LOWEST）依赖
         // 同一次事件分发完整跑完，第三方处理器异常会打断分发、LOWEST 不执行，条目
         // 永久残留；之后该 Boss 与禁疗无关的复活会被误处决。超窗条目双清（NBT + 内存）
         long now = event.getServer().overworld().getGameTime();
         for (UUID uuid : new ArrayList<>(FORCE_KILL_MARKED.keySet())) {
            Long markedAt = FORCE_KILL_MARKED.get(uuid);
            if (markedAt == null || now - markedAt <= FORCE_KILL_MARK_EXPIRY) continue;
            FORCE_KILL_MARKED.remove(uuid);
            for (ServerLevel level : event.getServer().getAllLevels()) {
               Entity entity = level.getEntity(uuid);
               if (entity != null) {
                  entity.getPersistentData().remove(FORCE_KILL_KEY);
                  break;
               }
            }
         }
      }

      /** 最终保障：LOWEST 优先级兜底，检测 ForceKill 标记并强制死亡 */
      @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
      public static void onLivingDeath(LivingDeathEvent event) {
         LivingEntity entity = event.getEntity();
         // 死亡时清理追踪记录、觉醒易伤（内存 + NBT）、宽限期与禁疗之触 NBT 标记（防止玩家复活后残留禁疗）
         TRACKED_HEALTH.remove(entity.getUUID());
         VULN_END.remove(entity.getUUID());
         MISSING_TICKS.remove(entity.getUUID());
         entity.getPersistentData().remove(NBT_KEY);
         entity.getPersistentData().remove(VULN_NBT_KEY);
         CompoundTag data = entity.getPersistentData();
         // v1.4.6 双源查询：NBT || 内存表（空 tag Boss 的 NBT 通道丢失，内存表兜底）；
         // 消费即双清（打标与消费在同一次事件分发内配对）
         boolean forceKill = (data.contains(FORCE_KILL_KEY) && data.getBoolean(FORCE_KILL_KEY))
            || FORCE_KILL_MARKED.containsKey(entity.getUUID());
         FORCE_KILL_MARKED.remove(entity.getUUID());
         if (forceKill) {
            data.remove(FORCE_KILL_KEY);
            // 如果被其他模组取消（复活），强制归零血量并放行死亡
            // v1.4.2：五层引擎处决归零（覆盖静态 Map/加密存储型；全层失败退 raw 清零原版+自定义血条）
            if (event.isCanceled()) {
               BloodWriteEngine.execute(entity, 0.0F, com.ayin90723.adventure_power.util.DebugLog.EngineCaller.HEALING_BLOCK);
               event.setCanceled(false);
               // v1.4.6：终局复验（docs/execution-finality-proposal.md §3）——归零放行后挂
               // pending 窗口观察 die 的结果，防"die 覆写型/die 后拉回型"复活（标记已清、
               // 钳制已停，无人再管的禁疗承诺缺口，详见类内 scheduleFinalityRecheck）
               scheduleFinalityRecheck(entity, event.getSource());
            }
         }
      }

      // ==================== v1.4.6 终局复验 ====================

      /** 终局复验 per-target 去重（弱 key 随目标回收；事件与 ServerTick END 均主线程，无并发） */
      private static final Map<LivingEntity, Boolean> FINALITY_RECHECK_ACTIVE = new WeakHashMap<>();

      /**
       * 终局复验（v1.4.6，docs/execution-finality-proposal.md §3.1/§3.2）：FORCE_KILL
       * 归零 + uncancel 放行 die 后挂 pending 窗口，窗口末单点裁决三态：
       * <ul>
       *   <li>{@code isRemoved}（容器事实）→ 原版链自清（uncancel 后 die 正常走完 →
       *       tickDeath 移除），完成；</li>
       *   <li>{@code deathTime > 0} → 死亡流程已启动（演出型/正常死亡动画），容忍不干预；</li>
       *   <li>{@code !isRemoved && deathTime==0} → 真被拉回（die 覆写型 / die 后拉回型）→
       *       GateOracle 开门梯（成功 = 正规死亡链自清）/ 开关关或全败 → ExecutionFinalizer
       *       处决善后（"不许活"兜底）。</li>
       * </ul>
       * onDead 分支必须复查容器事实（六轮教训：表层 {@code !isAlive()} 会被 liveness 覆写
       * 谎报——归零后"宣称已死"但 isRemoved=false / deathTime=0，die 从未执行），与裁决
       * 三态处置路径合流；正常死亡动画中（deathTime&gt;0 未移除）同样容忍不误杀。
       * <p>
       * 语义门禁天然满足（触发语境 = FORCE_KILL 分支本身）；GateOracle PENDING 衔接：开门梯
       * 进入轮询等待后由其内部 pending 任务接管（超时降级自动补跑 finalizeFallback），复验
       * 任务使命结束不重复挂。
       */
      private static void scheduleFinalityRecheck(LivingEntity target, DamageSource source) {
         if (target.level().isClientSide()) return;
         if (!(target.level() instanceof ServerLevel serverLevel)) return;
         if (target instanceof net.minecraft.world.entity.player.Player) return; // PVP 门禁防御性复查（标记只打非玩家目标）
         if (FINALITY_RECHECK_ACTIVE.put(target, Boolean.TRUE) != null) return; // per-target 去重
         DebugLog.healingBlock("[禁疗] 终局复验挂起：uncancel 放行 die，{} tick 后裁决 target={}",
            ModConfig.GATE_ORACLE_WAIT_TICKS.get(), target);
         PendingVerifyRegistry.register(target, ModConfig.GATE_ORACLE_WAIT_TICKS.get(),
            PendingVerifyRegistry.TaskKind.GATE,
            new PendingVerifyRegistry.PendingTask() {
               @Override
               public boolean onVerify(LivingEntity t) {
                  FINALITY_RECHECK_ACTIVE.remove(t);
                  return adjudicateFinality(t, source, serverLevel);
               }

               @Override
               public void onDead(LivingEntity t) {
                  FINALITY_RECHECK_ACTIVE.remove(t);
                  // 六轮教训：!isAlive 谎报不算死——isRemoved 为真才完成，否则与裁决三态合流
                  adjudicateFinality(t, source, serverLevel);
               }
            });
      }

      /**
       * 裁决三态 + 处置（onVerify/onDead 合流入口）。恒返回 true（处置已交棒，任务完成）。
       * <p>
       * tryOpen 返回值按方案 §3.1 四态契约处置：FAILED 时善后已由 finalizeFallback 在
       * GateOracle 内部跑过，直接 return 勿双跑（各段不幂等——双掉装备/双发事件）；
       * gate_oracle_enabled=false 时 tryOpen 返回 NOT_APPLICABLE → 直接处决善后
       * （终局性 = 禁疗能力承诺，开关只控制"是否尝试正规死亡链"这一手段）。
       */
      private static boolean adjudicateFinality(LivingEntity t, DamageSource source, ServerLevel serverLevel) {
         if (t.isRemoved()) {
            DebugLog.healingBlock("[禁疗] 终局复验：目标已移除（原版链自清），完成 target={}", t);
            return true;
         }
         int deathTime = ((LivingEntityFieldsAccessor) t).adventure_power$getDeathTime();
         if (deathTime > 0) {
            DebugLog.healingBlock("[禁疗] 终局复验：死亡流程已启动（deathTime={}），容忍不干预 target={}",
               deathTime, t);
            return true;
         }
         DebugLog.healingBlock("[禁疗] 终局复验：真被拉回（die 覆写/拉回型）→ 开门梯/处决善后 target={}", t);
         GateOracle.OpenResult gate = GateOracle.tryOpen(t, source,
            () -> ExecutionFinalizer.finalizeKill(t, source, serverLevel,
               com.ayin90723.adventure_power.util.DebugLog.EngineCaller.HEALING_BLOCK));
         switch (gate) {
            case SYNC_DEAD -> ExecutionFinalizer.schedulePostKillSync(t, serverLevel,
               com.ayin90723.adventure_power.util.DebugLog.EngineCaller.HEALING_BLOCK);
            case PENDING, FAILED -> { /* 开门在飞 / 善后已在 tryOpen 内部跑过（勿双跑） */ }
            case NOT_APPLICABLE -> ExecutionFinalizer.finalizeKill(t, source, serverLevel,
               com.ayin90723.adventure_power.util.DebugLog.EngineCaller.HEALING_BLOCK);
         }
         return true;
      }
   }
}
