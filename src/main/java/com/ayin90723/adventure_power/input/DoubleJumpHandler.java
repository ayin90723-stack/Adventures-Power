package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 *   <li>计数器模型：记录<strong>已消耗</strong>的空中跳跃次数（起跳前=0）</li>
 *   <li>冷却由客户端通过原版 {@code noJumpDelay} 控制，服务端不再维护自定义冷却</li>
 *   <li>玩家死亡重生 / 跨维度 / 登出时自动清理残留状态</li>
 *   <li>施力公式由 {@link VoidStepMovement} 统一提供，客户端预测与服务端权威共用，保证两端一致、无顿挫</li>
 *   <li><b>陈旧网络包防护（核心）</b>：每个空中周期绑定唯一递增 {@code airPhaseId}（客户端离开地面时递增），
 *       服务端据此判断周期归属：
 *       <ul>
 *         <li>{@code clientAirPhaseId > state.expectedAirPhaseId}：客户端已进入新周期（落地重跳），重置 used=0 接受</li>
 *         <li>{@code clientAirPhaseId == state.expectedAirPhaseId}：同周期，正常消耗</li>
 *         <li>{@code clientAirPhaseId < state.expectedAirPhaseId}：陈旧包，直接丢弃</li>
 *       </ul>
 *       <b>不</b>在落地时清零 state，否则旧包到达时 state=null 会被误当成新周期首跳接受，防护失效。
 *       新周期靠 airPhaseId 递增识别，落地不清零无内存泄漏（每玩家仅一个 entry，下次跳时覆盖）。</li>
 * </ul>
 */
@EventBusSubscriber(bus = Bus.FORGE)
public class DoubleJumpHandler {
    /**
     * 每玩家空中周期状态：
     * @param expectedAirPhaseId  当前空中周期期望的请求 ID
     * @param jumpsUsed  此周期已消耗的跳跃次数
     */
    private static class AirPhaseState {
        public final int expectedAirPhaseId;
        public final int jumpsUsed;
        public AirPhaseState(int expectedAirPhaseId, int jumpsUsed) {
            this.expectedAirPhaseId = expectedAirPhaseId;
            this.jumpsUsed = jumpsUsed;
        }
    }

    /** 每玩家空中周期状态（落地不清理，靠 airPhaseId 递增重置） */
    private static final Map<UUID, AirPhaseState> AIR_PHASE_STATE = new HashMap<>();

    // ==================== 公开入口 ====================

    /**
     * 处理二段跳请求，携带客户端空气周期 ID 用于陈旧包校验。
     * <p>
     * <b>统一 motion 同步（防独飞）</b>：无论 {@link #tryApplyJump} 是否通过校验施力，
     * 方法末尾都会向客户端发送一次 {@link ClientboundSetEntityMotionPacket}：
     * <ul>
     *   <li>通过 -> 同步施力后的 motion（覆盖客户端预测，最终一致）</li>
     *   <li>未通过 -> 同步服务端当前真实 motion，把客户端的预测施力拉回，防止"客户端独飞"</li>
     * </ul>
     * 历史上服务端静默 return 不发包，客户端预测保留导致斜飞/悬停，此为根治。
     * </p>
     */
    public static void handleDoubleJump(ServerPlayer player, int clientAirPhaseId) {
        boolean applied = tryApplyJump(player, clientAirPhaseId);
        // A′: 无论是否施力，统一同步 motion，把客户端预测拉回服务端权威值，根治"客户端独飞"
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        if (applied) {
            playEffects(player);
        }
    }

    /**
     * 校验并施加空中跳跃力。纯逻辑，不发包、不放效果。
     * @return true 表示已施力并消耗一次跳数；false 表示未通过校验（未施力）。
     */
    private static boolean tryApplyJump(ServerPlayer player, int clientAirPhaseId) {
        if (!isDoubleJumpEnabled(player)) return false;
        if (player.isPassenger()) return false;
        if (player.isInWater()) return false;
        if (player.getAbilities().flying) return false;
        if (player.onGround()) return false;  // 落地状态不允许（落地后第一跳走原版地面跳）

        AirPhaseState state = AIR_PHASE_STATE.get(player.getUUID());
        int used;
        if (state == null) {
            // 首次或落地后未清理 -> 新周期首跳
            used = 0;
        } else if (state.expectedAirPhaseId == clientAirPhaseId) {
            // 同周期
            used = state.jumpsUsed;
        } else if (state.expectedAirPhaseId < clientAirPhaseId) {
            // 客户端已进入新周期（落地重跳），服务端重置计数
            used = 0;
        } else {
            // 陈旧包（clientAirPhaseId < expected），丢弃
            return false;
        }

        int maxJumps = VoidStepMovement.getMaxAirJumps(player);
        if (used >= maxJumps) return false;  // 本周期跳数已耗尽

        float jumpPower = VoidStepMovement.calculateJumpPower(player);
        VoidStepMovement.applyJump(player, jumpPower, true);  // 服务端权威：含水平冲量

        // 更新周期状态：消耗一次跳跃
        AIR_PHASE_STATE.put(player.getUUID(), new AirPhaseState(clientAirPhaseId, used + 1));
        return true;
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

    /** 玩家登出 -> 清理追踪数据 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        AIR_PHASE_STATE.remove(event.getEntity().getUUID());
    }

    /**
     * 玩家死亡重生后清理残留数据。
     * 使用 Clone 事件（非 original 参数），确保新实体从干净状态开始。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        AIR_PHASE_STATE.remove(event.getEntity().getUUID());
    }

    /**
     * 玩家跨维度时清理残留数据。
     * 维度切换可能导致 onGround 状态短暂异常，应主动重置。
     */
    @SubscribeEvent
    public static void onPlayerChangeDimension(
        net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        AIR_PHASE_STATE.remove(event.getEntity().getUUID());
    }
}
