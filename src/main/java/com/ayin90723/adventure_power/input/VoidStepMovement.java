package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 虚空踏步空中跳跃的共享施力逻辑。
 * <p>
 * 客户端预测与服务端权威<b>必须调用同一套公式</b>，否则两端 Y 速度不一致会产生顿挫
 * （客户端先预测一个值，服务端 motion 包覆盖为另一个值 -> 视觉抖动）。
 * </p>
 * <p>
 * 本类只负责「施加跳跃力」，不处理网络同步与服务端跳数计数：
 * <ul>
 *   <li>客户端 {@link JumpInputHandler} 调用 {@link #applyJump} 做即时预测</li>
 *   <li>服务端 {@link DoubleJumpHandler} 调用 {@link #applyJump} 后另行发送 motion 包同步</li>
 * </ul>
 */
public final class VoidStepMovement {
    /** 疾跑时附加的水平冲量，对齐原版 {@code LivingEntity#jumpFromGround} 的 0.2 */
    public static final double SPRINT_BOOST = 0.2;

    private VoidStepMovement() {}

    /**
     * 返回玩家可在空中执行的额外跳跃次数。
     * 非觉醒 = 1（二段跳），觉醒 = {@code AWAKEN_VOID_STEP_JUMPS - 1}（三段跳）。
     */
    public static int getMaxAirJumps(Player player) {
        boolean awakened = AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> p.isFullyUnlocked()).orElse(false);
        return awakened ? ModConfig.AWAKEN_VOID_STEP_JUMPS.get() - 1 : 1;
    }

    /**
     * 计算空中跳跃的 Y 速度（覆盖式，非叠加）。
     * 基础值 = 原版跳跃力（0.42 × 方块系数 + 跳跃提升加成）× {@code VOID_STEP_POWER}。
     */
    public static float calculateJumpPower(LivingEntity entity) {
        float blockFactor = entity.level().getBlockState(
            BlockPos.containing(entity.getX(), entity.getBoundingBox().minY - 0.2, entity.getZ())
        ).getBlock().getJumpFactor();
        float vanillaPower = 0.42F * blockFactor + getJumpBoostPower(entity);
        return vanillaPower * ModConfig.VOID_STEP_POWER.get().floatValue();
    }

    /**
     * 施加空中跳跃力。覆盖 Y 速度 + （可选）疾跑水平冲量 + hasImpulse + 摔落距离清零。
     * <p>
     * 客户端预测时 {@code addSprintBoost = false}：仅覆盖 Y，水平冲量留给服务端加，避免重复叠加导致加倍弹飞。
     * 服务端权威执行时 {@code addSprintBoost = true}：完整添加水平冲量。
     * </p>
     * 与原版 {@code jumpFromGround} 行为对齐，但 Y 倍率由 {@code VOID_STEP_POWER} 控制。
     */
    public static void applyJump(LivingEntity entity, float power, boolean addSprintBoost) {
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(motion.x(), power, motion.z());
        entity.hasImpulse = true;
        entity.fallDistance = 0.0F;

        if (addSprintBoost && entity.isSprinting()) {
            float yRot = entity.getYRot() * Mth.DEG_TO_RAD;
            entity.addDeltaMovement(new Vec3(
                -Mth.sin(yRot) * SPRINT_BOOST, 0.0, Mth.cos(yRot) * SPRINT_BOOST));
        }
    }

    private static float getJumpBoostPower(LivingEntity entity) {
        if (entity.hasEffect(MobEffects.JUMP)) {
            return 0.1F * (entity.getEffect(MobEffects.JUMP).getAmplifier() + 1);
        }
        return 0.0F;
    }
}
