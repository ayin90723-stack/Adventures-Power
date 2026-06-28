package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(bus = Bus.FORGE)
public class DoubleJumpHandler {
   /** 二段跳冷却（游戏刻），防止同一次腾空中连续触发 */
   private static final long COOLDOWN_TICKS = 20;
   /** 二段跳力度倍率（基于原版跳跃力），1.2 = 比普通跳跃高 20% */
   private static final float POWER_MULTIPLIER = 1.2F;
   /** 疾跑时水平冲量 */
   private static final double SPRINT_BOOST = 0.2;
   /** 每玩家冷却追踪 */
   private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
   /** 每玩家剩余空中跳跃次数（觉醒后=2，非觉醒=1） */
   private static final Map<UUID, Integer> JUMPS_REMAINING = new HashMap<>();

   public static void handleDoubleJump(ServerPlayer player) {
      if (isDoubleJumpEnabled(player)) {
         if (canDoubleJump(player)) {
            float jumpPower = calculateJumpPower(player);
            applyMovement(player, jumpPower);
            // 消耗一次空中跳跃
            Integer remaining = JUMPS_REMAINING.get(player.getUUID());
            if (remaining != null && remaining > 0) {
               JUMPS_REMAINING.put(player.getUUID(), remaining - 1);
            }
            playEffects(player);
            COOLDOWNS.put(player.getUUID(), player.level().getGameTime());
         }
      }
   }

   private static boolean canDoubleJump(ServerPlayer player) {
      if (!isDoubleJumpEnabled(player)) return false;
      if (player.isPassenger()) return false;
      if (player.isInWater()) return false;
      if (player.getAbilities().flying) return false;

      // 落地 → 不允许（tick 处理器负责在此处清理 JUMPS_REMAINING）
      if (player.onGround()) {
         return false;
      }

      // 刚离开地面 → 初始化跳数
      Integer remaining = JUMPS_REMAINING.get(player.getUUID());
      if (remaining == null) {
         int maxJumps = getMaxAirJumps(player);
         JUMPS_REMAINING.put(player.getUUID(), maxJumps);
         remaining = maxJumps;
      }

      // 冷却检查
      Long lastJump = COOLDOWNS.get(player.getUUID());
      if (lastJump != null && player.level().getGameTime() - lastJump < COOLDOWN_TICKS) {
         return false;
      }

      return remaining > 0;
   }

   @SubscribeEvent
   public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
      COOLDOWNS.remove(event.getEntity().getUUID());
      JUMPS_REMAINING.remove(event.getEntity().getUUID());
   }

   /**
    * 玩家落地时重置空中跳跃次数。
    * 使用 END phase 确保在服务端 tick 逻辑完成后检测，此时 {@code onGround()} 状态已经稳定。
    */
   @SubscribeEvent
   public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
      if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
         if (player.onGround()) {
            JUMPS_REMAINING.remove(player.getUUID());
         }
      }
   }

   private static boolean isDoubleJumpEnabled(ServerPlayer player) {
      return AdventureProgressCapability.getAdventureProgress(player)
          .map(p -> p.isAdventurer() && p.isAbilityEnabled("void_step"))
          .orElse(false);
   }

   /** 返回玩家可在空中执行的额外跳跃次数（觉醒=2，非觉醒=1） */
   private static int getMaxAirJumps(ServerPlayer player) {
      boolean awakened = AdventureProgressCapability.getAdventureProgress(player)
          .map(p -> p.isFullyUnlocked()).orElse(false);
      return awakened ? com.ayin90723.adventure_power.config.ModConfig.AWAKEN_VOID_STEP_JUMPS.get() - 1 : 1;
   }

   /**
    * 二段跳力量 = 原版跳跃力 × 倍率。
    * 原版跳跃力 = 0.42 × 方块跳跃系数 + 跳跃提升加成(0.1 × 等级)，
    * 因此跳跃提升效果会自然作用于二段跳，无需手动计算。
    */
   private static float calculateJumpPower(ServerPlayer player) {
      float blockFactor = getBlockJumpFactor(player);
      float vanillaPower = 0.42F * blockFactor + getJumpBoostPower(player);
      return vanillaPower * POWER_MULTIPLIER;
   }

   private static float getJumpBoostPower(ServerPlayer player) {
      if (player.hasEffect(MobEffects.JUMP)) {
         return 0.1F * (player.getEffect(MobEffects.JUMP).getAmplifier() + 1);
      }
      return 0.0F;
   }

   private static float getBlockJumpFactor(ServerPlayer player) {
      BlockPos pos = BlockPos.containing(player.getX(), player.getBoundingBox().minY - 0.2, player.getZ());
      return player.level().getBlockState(pos).getBlock().getJumpFactor();
   }

   private static void applyMovement(ServerPlayer player, float power) {
      Vec3 motion = player.getDeltaMovement();
      player.setDeltaMovement(motion.x(), power, motion.z());
      player.hasImpulse = true;
      player.fallDistance = 0.0F;

      // 疾跑时附加水平冲量（方向 = 玩家视线）
      if (player.isSprinting()) {
         float yRot = player.getYRot() * Mth.DEG_TO_RAD;
         player.addDeltaMovement(new Vec3(
             -Mth.sin(yRot) * SPRINT_BOOST, 0.0, Mth.cos(yRot) * SPRINT_BOOST));
      }

      player.connection.send(new ClientboundSetEntityMotionPacket(player));
   }

   private static void playEffects(ServerPlayer player) {
      ServerLevel level = (ServerLevel)player.level();
      level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 6, 0.3, 0.1, 0.3, 0.02);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GOAT_LONG_JUMP, SoundSource.PLAYERS, 0.6F, 0.8F);
   }
}
