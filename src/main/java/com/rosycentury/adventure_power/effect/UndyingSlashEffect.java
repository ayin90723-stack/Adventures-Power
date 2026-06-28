package com.rosycentury.adventure_power.effect;

import com.rosycentury.adventure_power.AdventurePower;
import com.rosycentury.adventure_power.util.HealthUtil;
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
 * 不死斩效果 —— 禁止回血与复活（纯辅助）
 * 以 NBT 持久化标记为真实判断依据，MobEffect 仅作为视觉指示器
 */
public class UndyingSlashEffect extends MobEffect {
   /** NBT 中存储不死斩效果到期时间的 key */
   public static final String NBT_KEY = "MME_UndyingSlashEndTime";
   /** NBT 中存储强制击杀标记的 key（跨优先级传递） */
   public static final String FORCE_KILL_KEY = "MME_UndyingSlash_ForceKill";
   /** 记录实体在不死斩期间的最近已知血量，用于拦截 setHealth() 直接回血 */
   private static final Map<UUID, Float> TRACKED_HEALTH = new ConcurrentHashMap<>();
   /** 跨维度传送宽限期：记录实体连续未在维度中找到的 tick 数，防止传送时误清理 */
   private static final Map<UUID, Integer> MISSING_TICKS = new ConcurrentHashMap<>();

   // TODO: 临时字段，后续改为从配置系统读取
   private static final boolean ALLOW_BOSS_PHASE_TWO = true;

   public UndyingSlashEffect() {
      super(MobEffectCategory.HARMFUL, 0x8B0000); // 暗红色
   }

   public boolean isDurationEffectTick(int duration, int amplifier) {
      return true;
   }

   /** 检查实体当前是否受不死斩效果影响（以 NBT 为准，过期自动清理） */
   public static boolean isActive(LivingEntity entity) {
      if (entity == null || entity.level().isClientSide()) {
         return false;
      }
      CompoundTag data = entity.getPersistentData();
      if (!data.contains(NBT_KEY)) {
         return false;
      }
      long endTime = data.getLong(NBT_KEY);
      if (entity.level().getGameTime() > endTime) {
         data.remove(NBT_KEY); // 过期自动清理
         return false;
      }
      return true;
   }

   /** 向目标施加不死斩标记，持续时间单位：tick */
   public static void apply(LivingEntity target, int durationTicks) {
      long endTime = target.level().getGameTime() + durationTicks;
      target.getPersistentData().putLong(NBT_KEY, endTime);
      // 记录施加时的血量，用于后续拦截 setHealth() 直写回血
      TRACKED_HEALTH.put(target.getUUID(), target.getHealth());
      // 同时施加 MobEffect 作为视觉指示器
      MobEffect visualEffect = ModEffects.UNDYING_SLASH.get();
      if (visualEffect != null) {
         target.addEffect(new MobEffectInstance(visualEffect, durationTicks, 0, false, true));
      }
   }

   /** 获取实体当前的追踪血量（用于 Mixin 等外部调用者读取钳制基准） */
   public static Float getTrackedHealth(LivingEntity entity) {
      return TRACKED_HEALTH.get(entity.getUUID());
   }

   /** 更新实体的追踪血量（用于 Mixin 等外部调用者在钳制后同步基准值） */
   public static void updateTrackedHealth(LivingEntity entity, float health) {
      TRACKED_HEALTH.put(entity.getUUID(), health);
   }

   /** 检查是否应允许二阶段（Boss 实体 + 配置启用） */
   private static boolean shouldAllowPhaseTwo(LivingEntity entity) {
      if (!ALLOW_BOSS_PHASE_TWO) {
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

      /** 每 tick 检查：拦截绕过 LivingHealEvent 的 setHealth() 直写回血 */
      @SubscribeEvent
      public static void onLivingTick(LivingTickEvent event) {
         LivingEntity entity = event.getEntity();
         if (entity.level().isClientSide()) return;

         UUID uuid = entity.getUUID();
         if (isActive(entity)) {
            Float tracked = TRACKED_HEALTH.get(uuid);
            if (tracked != null && entity.getHealth() > tracked) {
               // 血量异常增长（可能被其他 mod 的 setHealth() 直写），钳制回记录值
               // 使用 setAllHealthLikeRaw 直写 DataItem.value 字段，
               // 绕过 SynchedEntityData.set() 的 dirty 标记/监听器/Mixin 注入
               HealthUtil.setAllHealthLikeRaw(entity, tracked);
            }
            // 更新记录值（允许血量下降，但禁止上升）
            TRACKED_HEALTH.put(uuid, Math.min(entity.getHealth(), tracked != null ? tracked : entity.getHealth()));
         } else {
            // 效果已过期，清理追踪记录与宽限期
            TRACKED_HEALTH.remove(uuid);
            MISSING_TICKS.remove(uuid);
         }
      }

      /** 拦截不死图腾激活 */
      @SubscribeEvent
      public static void onLivingUseTotem(LivingUseTotemEvent event) {
         if (isActive(event.getEntity())) {
            event.setCanceled(true);
         }
      }

      /** 预先标记：HIGHEST 优先级记录不死斩实体即将死亡 */
      @SubscribeEvent(priority = EventPriority.HIGHEST)
      public static void onLivingDeathPreMark(LivingDeathEvent event) {
         if (isActive(event.getEntity())) {
            if (shouldAllowPhaseTwo(event.getEntity())) {
               // 允许 Boss 进入二阶段，清理追踪记录、宽限期与 NBT 标记
               TRACKED_HEALTH.remove(event.getEntity().getUUID());
               MISSING_TICKS.remove(event.getEntity().getUUID());
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
                  Float tracked = TRACKED_HEALTH.get(uuid);
                  if (tracked != null) {
                     float current = living.getHealth();
                     if (current > tracked) {
                        HealthUtil.setAllHealthLikeRaw(living, tracked);
                     }
                     TRACKED_HEALTH.put(uuid, Math.min(current, tracked));
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
      @SubscribeEvent(priority = EventPriority.LOWEST)
      public static void onLivingDeath(LivingDeathEvent event) {
         LivingEntity entity = event.getEntity();
         // 死亡时清理追踪记录、宽限期与不死斩 NBT 标记（防止玩家复活后残留禁疗）
         TRACKED_HEALTH.remove(entity.getUUID());
         MISSING_TICKS.remove(entity.getUUID());
         entity.getPersistentData().remove(NBT_KEY);
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
