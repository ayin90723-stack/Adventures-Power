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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 空中二段跳服务端逻辑。
 * <p>
 * <b>单次二段跳</b>：每个空中周期（落地->起跳->落地）仅允许跳 1 次。
 * 服务端用 {@link #AIR_JUMPED} 集合记录「本周期已跳」的玩家，落地时清除。
 * </p>
 * <ul>
 *   <li>落地清零由 {@link #onPlayerTick} 检测 onGround 完成</li>
 *   <li>玩家登出 / 死亡重生 / 跨维度（Clone）时清理残留</li>
 *   <li>施力 Y 公式由 {@link VoidStepMovement} 统一提供，客户端预测与服务端权威共用</li>
 *   <li><b>服务端只设 Y，不施加御风冲刺</b>（对齐云朵瓶）：水平冲刺由客户端预测，位置客户端权威，服务端不重复施加避免两端 sprint 状态不同步导致水平跳变</li>
 *   <li><b>成功不发包</b>：信任客户端预测（两端 Y 同公式），无顿挫；拒绝时才发包防独飞</li>
 * </ul>
 */
@EventBusSubscriber(bus = Bus.FORGE)
public class DoubleJumpHandler {
    /** 本空中周期已跳过二段跳的玩家集合，落地清除 */
    private static final Set<UUID> AIR_JUMPED = new HashSet<>();

    // ==================== 公开入口 ====================

    /**
     * 处理二段跳请求。
     * <p>
     * <b>成功不发包</b>：施力后<b>不</b>发 {@link ClientboundSetEntityMotionPacket}，
     * 保留客户端预测（两端 Y 同公式，无顿挫）。
     * 仅在拒绝时发包拉回客户端预测，防独飞。
     * </p>
     */
    public static void handleDoubleJump(ServerPlayer player) {
        if (tryApplyJump(player)) {
            playEffects(player);
        } else {
            // 拒绝时拉回客户端预测，防独飞
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        }
    }

    /**
     * 校验并施加空中跳跃力。纯逻辑，不发包、不放效果。
     * @return true 表示已施力；false 表示未通过校验。
     */
    private static boolean tryApplyJump(ServerPlayer player) {
        if (!isDoubleJumpEnabled(player)) return false;
        if (player.isPassenger()) return false;
        if (player.isInWater()) return false;
        if (player.getAbilities().flying) return false;
        // 不校验 onGround：客户端已在 !onGround 时才发包，服务端再校会因网络延迟误杀

        if (AIR_JUMPED.contains(player.getUUID())) return false;

        float jumpPower = VoidStepMovement.calculateJumpPower(player);
        // 服务端只设 Y（dash=false），御风冲刺由客户端预测
        VoidStepMovement.applyJump(player, jumpPower, false);

        AIR_JUMPED.add(player.getUUID());
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

    /** 每 tick 检测落地 -> 清除「本周期已跳」标记 */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player.onGround()) {
            AIR_JUMPED.remove(event.player.getUUID());
        }
    }

    /** 玩家登出 -> 清理 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        AIR_JUMPED.remove(event.getEntity().getUUID());
    }

    /** 玩家死亡重生 / 跨维度（Clone）-> 清理残留 */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        AIR_JUMPED.remove(event.getEntity().getUUID());
    }
}
