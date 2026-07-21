package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 *   <li>施力公式由 {@link VoidStepMovement} 统一提供，客户端预测与服务端权威共用，保证两端一致、无顿挫</li>
 * </ul>
 */
@EventBusSubscriber(bus = Bus.FORGE)
public class DoubleJumpHandler {
    /** 每玩家已消耗的空中跳跃次数（落地归零；查询用 getOrDefault，不在此初始化） */
    private static final Map<UUID, Integer> JUMPS_USED = new HashMap<>();

    // ==================== 公开入口 ====================

    public static void handleDoubleJump(ServerPlayer player) {
        if (!isDoubleJumpEnabled(player)) return;
        if (!canDoubleJump(player)) return;

        float jumpPower = VoidStepMovement.calculateJumpPower(player);
        VoidStepMovement.applyJump(player, jumpPower, true);

        // 消耗一次空中跳跃（merge 兼容首跳 key 不存在的情况，无需预先初始化）
        JUMPS_USED.merge(player.getUUID(), 1, Integer::sum);

        playEffects(player);

        // 服务端权威：同步 motion 到客户端，覆盖客户端预测值，确保最终一致
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }

    // ==================== 条件检查（纯查询，无副作用） ====================

    private static boolean canDoubleJump(ServerPlayer player) {
        if (player.isPassenger()) return false;
        if (player.isInWater()) return false;
        if (player.getAbilities().flying) return false;

        // 落地状态 -> 不允许（重置交给 onPlayerTick）
        if (player.onGround()) return false;

        int used = JUMPS_USED.getOrDefault(player.getUUID(), 0);
        return used < VoidStepMovement.getMaxAirJumps(player);
    }

    private static boolean isDoubleJumpEnabled(ServerPlayer player) {
        return AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> p.isAdventurer() && p.isAbilityEnabled("void_step"))
            .orElse(false);
    }

    // ==================== 效果 ====================

    private static void playEffects(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        level.sendParticles(ParticleTypes.CLOUD,
            player.getX(), player.getY(), player.getZ(),
            6, 0.3, 0.1, 0.3, 0.02);
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

    /** 玩家登出 -> 清理追踪数据 */
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
