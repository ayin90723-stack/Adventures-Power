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

/**
 * 空中多段跳服务端逻辑。
 * <p>
 * 参照 AirHop 的设计：
 * </p>
 * <ul>
 *   <li>计数器模型：记录<strong>已消耗</strong>的空中跳跃次数（起跳前=0），落地归零</li>
 *   <li>冷却由客户端通过原版 {@code noJumpDelay} 控制，服务端不再维护自定义冷却</li>
 *   <li>玩家死亡重生 / 跨维度时自动清理残留状态</li>
 *   <li>客户端侧通过 {@code LivingEntity.jumpFromGround()} 提供即时跳跃反馈</li>
 * </ul>
 */
@EventBusSubscriber(bus = Bus.FORGE)
public class DoubleJumpHandler {
    /** 二段跳力度倍率（在 vanilla 跳跃力的基础上叠加） */
    private static final float POWER_MULTIPLIER = 1.2F;
    /** 疾跑时水平冲量 */
    private static final double SPRINT_BOOST = 0.2;

    /** 每玩家已消耗的空中跳跃次数（null = 未初始化/已落地归零，0 = 尚未消耗） */
    private static final Map<UUID, Integer> JUMPS_USED = new HashMap<>();

    // ==================== 公开入口 ====================

    public static void handleDoubleJump(ServerPlayer player) {
        if (!isDoubleJumpEnabled(player)) return;
        if (!canDoubleJump(player)) return;

        float jumpPower = calculateJumpPower(player);
        applyMovement(player, jumpPower);

        // 消耗一次空中跳跃
        Integer used = JUMPS_USED.get(player.getUUID());
        if (used != null) {
            JUMPS_USED.put(player.getUUID(), used + 1);
        }

        playEffects(player);
    }

    // ==================== 条件检查（纯查询，无副作用） ====================

    private static boolean canDoubleJump(ServerPlayer player) {
        if (player.isPassenger()) return false;
        if (player.isInWater()) return false;
        if (player.getAbilities().flying) return false;

        // 落地状态 → 不允许（初始化/重置交给 onPlayerTick）
        if (player.onGround()) return false;

        // 初始化：首次进入空中时 JUMPS_USED 为 null → 初始化为 0
        Integer used = JUMPS_USED.get(player.getUUID());
        if (used == null) {
            JUMPS_USED.put(player.getUUID(), 0);
            used = 0;
        }

        int maxJumps = getMaxAirJumps(player);
        return used < maxJumps;
    }

    private static boolean isDoubleJumpEnabled(ServerPlayer player) {
        return AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> p.isAdventurer() && p.isAbilityEnabled("void_step"))
            .orElse(false);
    }

    /** 返回玩家可在空中执行的额外跳跃次数（觉醒=2次，非觉醒=1次） */
    private static int getMaxAirJumps(ServerPlayer player) {
        boolean awakened = AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> p.isFullyUnlocked()).orElse(false);
        return awakened
            ? com.ayin90723.adventure_power.config.ModConfig.AWAKEN_VOID_STEP_JUMPS.get() - 1
            : 1;
    }

    // ==================== 跳跃物理 ====================

    /**
     * 计算空中跳跃力度。
     * 基础值 = 原版跳跃力（0.42 × 方块系数 + 跳跃提升加成）× 模组倍率。
     * 与客户端侧 {@code jumpFromGround()} 的行为保持一致。
     */
    private static float calculateJumpPower(ServerPlayer player) {
        float blockFactor = player.level().getBlockState(
            BlockPos.containing(player.getX(), player.getBoundingBox().minY - 0.2, player.getZ())
        ).getBlock().getJumpFactor();
        float vanillaPower = 0.42F * blockFactor + getJumpBoostPower(player);
        return vanillaPower * POWER_MULTIPLIER;
    }

    private static float getJumpBoostPower(ServerPlayer player) {
        if (player.hasEffect(MobEffects.JUMP)) {
            return 0.1F * (player.getEffect(MobEffects.JUMP).getAmplifier() + 1);
        }
        return 0.0F;
    }

    /**
     * 施加移动速度。
     * <p>
     * 直接设置 Y 轴速度并触发 {@code hasImpulse}，
     * 客户端侧通过 {@code LivingEntity.jumpFromGround()} 已先行提供即时反馈。
     * </p>
     */
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

    // ==================== 效果 ====================

    private static void playEffects(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        level.sendParticles(ParticleTypes.CLOUD,
            player.getX(), player.getY(), player.getZ(),
            6, 0.3, 0.1, 0.3, 0.02);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.GOAT_LONG_JUMP, SoundSource.PLAYERS, 0.6F, 0.8F);
    }

    // ==================== 生命周期事件 ====================

    /**
     * 玩家落地时重置已消耗跳跃次数。
     * END phase 确保 {@code onGround()} 状态已稳定。
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
            if (player.onGround()) {
                JUMPS_USED.remove(player.getUUID());
            }
        }
    }

    /** 玩家登出 → 清理追踪数据 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        JUMPS_USED.remove(event.getEntity().getUUID());
    }

    /**
     * 玩家死亡重生后清理残留数据。
     * 使用 Clone 事件（非 original 参数），确保新实体从干净状态开始。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        JUMPS_USED.remove(event.getEntity().getUUID());
    }

    /**
     * 玩家跨维度时清理残留数据。
     * 维度切换可能导致 onGround 状态短暂异常，应主动重置。
     */
    @SubscribeEvent
    public static void onPlayerChangeDimension(
        net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        JUMPS_USED.remove(event.getEntity().getUUID());
    }
}
