package com.ayin90723.adventure_power.effect;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraftforge.event.TickEvent;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 禁疗之触效果 —— 禁止回血与复活（纯辅助）
 * 以 NBT 持久化标记为真实判断依据，MobEffect 仅作为视觉指示器
 */
public class HealingBlockEffect extends MobEffect {
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
   /** 跨维度传送宽限期：记录实体连续未在维度中找到的 tick 数，防止传送时误清理 */
   private static final Map<UUID, Integer> MISSING_TICKS = new ConcurrentHashMap<>();

   public HealingBlockEffect() {
      super(MobEffectCategory.HARMFUL, 0x8B0000); // 暗红色
   }

   public boolean isDurationEffectTick(int duration, int amplifier) {
      return true;
   }

   /** 检查实体当前是否受禁疗之触效果影响（NBT 优先，内存表回退；过期自动清理） */
   public static boolean isActive(LivingEntity entity) {
      if (entity == null || entity.level().isClientSide()) {
         return false;
      }
      long gameTime = entity.level().getGameTime();
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
      long endTime = target.level().getGameTime() + durationTicks;
      // 内存表记录（血量低点 + 到期时间）—— 双源核心：
      // getPersistentData() 被重写返回空 tag 的实体（如亚波伦）NBT 标记写入即丢，
      // isActive() 回退查此表，钳制链不因 NBT 失效
      // 基准血量用架空参照（getEffectiveHealth）：自定义血条实体（亚波伦）原版槽架空，
      // getHealthDirect 读到的是不动值，钳制基准必须取真实血量
      TRACKED_HEALTH.put(target.getUUID(), new TrackedEntry(HealthUtil.getEffectiveHealth(target), endTime));
      // NBT 持久化（正常实体重启后可恢复；对重写 getPersistentData 的实体静默无效）
      target.getPersistentData().putLong(NBT_KEY, endTime);
      // 同时施加 MobEffect 作为视觉指示器
      MobEffect visualEffect = ModEffects.UNDYING_SLASH.get();
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

   /** 检查是否应允许二阶段（Boss 实体 + 配置启用） */
   private static boolean shouldAllowPhaseTwo(LivingEntity entity) {
      if (!ModConfig.HEALING_BLOCK_ALLOW_BOSS_PHASE_TWO.get()) {
         return false;
      }
      return entity instanceof WitherBoss || entity instanceof EnderDragon;
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
            if (shouldAllowPhaseTwo(event.getEntity())) {
               // 允许 Boss 进入二阶段，清理追踪记录、觉醒易伤、宽限期与 NBT 标记
               UUID uuid = event.getEntity().getUUID();
               TRACKED_HEALTH.remove(uuid);
               VULN_END.remove(uuid);
               MISSING_TICKS.remove(uuid);
               event.getEntity().getPersistentData().remove(NBT_KEY);
               return;
            }
            event.getEntity().getPersistentData().putBoolean(FORCE_KILL_KEY, true);
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
         if (TRACKED_HEALTH.isEmpty()) return;

         Set<UUID> found = new HashSet<>();
         for (ServerLevel level : event.getServer().getAllLevels()) {
            for (UUID uuid : new ArrayList<>(TRACKED_HEALTH.keySet())) {
               Entity entity = level.getEntity(uuid);
               if (entity instanceof LivingEntity living && living.isAlive()) {
                  found.add(uuid);
                  TrackedEntry entry = TRACKED_HEALTH.get(uuid);
                  if (entry != null) {
                     // 架空参照读数：自定义血条实体（亚波伦）原版槽被架空，
                     // getHealthDirect 读到不动值会导致回血检测永远 false，钳制失效
                     float current = HealthUtil.getEffectiveHealth(living);
                     if (current > entry.health) {
                        HealthUtil.setAllHealthLikeRaw(living, entry.health);
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
         if (data.contains(FORCE_KILL_KEY) && data.getBoolean(FORCE_KILL_KEY)) {
            data.remove(FORCE_KILL_KEY);
            // 如果被其他模组取消（复活），强制归零血量并放行死亡
            // 使用 setAllHealthLikeRaw 直写 DataItem.value 字段清零原版+自定义血条
            if (event.isCanceled()) {
               HealthUtil.setAllHealthLikeRaw(entity, 0.0F);
               event.setCanceled(false);
            }
         }
      }
   }
}
