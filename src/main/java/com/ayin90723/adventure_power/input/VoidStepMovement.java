package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 虚空踏步空中跳跃的共享施力逻辑。
 * <p>
 * 客户端预测与服务端权威<b>必须调用同一套 Y 公式</b>，否则两端 Y 速度不一致会产生顿挫。
 * </p>
 * <p>
 * 本类只负责「施加跳跃力」，不处理网络同步与跳数计数：
 * <ul>
 *   <li>客户端 {@link JumpInputHandler} 调用 {@link #applyJump} 做即时预测（dash=true 时含御风冲刺）</li>
 *   <li>服务端 {@link DoubleJumpHandler} 调用 {@link #applyJump}（dash=false，只设 Y；水平冲刺由客户端预测，位置客户端权威）</li>
 * </ul>
 */
public final class VoidStepMovement {

    private VoidStepMovement() {}

    /**
     * 计算空中跳跃的 Y 速度。
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
     * 施加空中跳跃力。Y 直接覆盖为 power（对齐原版 {@code jumpFromGround}，无弹跳感）+ hasImpulse + 摔落距离清零；
     * dash=true 时额外朝玩家朝向施加「御风」水平冲刺。
     * <p>
     * Y 直接覆盖而非 max：与原版跳跃一致，避免 max 保留当前更高速度产生的「弹跳感」。
     * </p>
     * <p>
     * <b>御风（dash）</b>：朝玩家视角朝向（{@code YRot} 水平方向）施加冲刺冲量，由 {@code AWAKEN_VOID_STEP_DASH} 控制。
     * 客户端在觉醒+疾跑时调用 dash=true（预测）；服务端调用 dash=false（只设 Y，水平冲刺由客户端预测，位置客户端权威）。
     * </p>
     * @param dash 是否施加御风冲刺
     */
    public static void applyJump(LivingEntity entity, float power, boolean dash) {
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(motion.x(), power, motion.z());
        entity.hasImpulse = true;
        entity.fallDistance = 0.0F;

        if (dash) {
            // 御风：朝玩家朝向水平冲刺
            float yRot = entity.getYRot() * Mth.DEG_TO_RAD;
            double dashAmount = ModConfig.AWAKEN_VOID_STEP_DASH.get();
            entity.addDeltaMovement(new Vec3(
                -Mth.sin(yRot) * dashAmount, 0.0, Mth.cos(yRot) * dashAmount));
        }
    }

    private static float getJumpBoostPower(LivingEntity entity) {
        if (entity.hasEffect(MobEffects.JUMP)) {
            return 0.1F * (entity.getEffect(MobEffects.JUMP).getAmplifier() + 1);
        }
        return 0.0F;
    }
}
