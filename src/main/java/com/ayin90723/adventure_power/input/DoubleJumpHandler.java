package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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
 *   <li>落地清零由 {@link PlayerTickDispatcher} 分发的 {@link #onTick} 检测 onGround 完成</li>
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

    /** 拒绝回发节流表：UUID → 上次拒绝回发的服务端全局 tick。
     *  v1.4.0 审查修复：拒绝路径原先对每个请求包 1:1 回发拉回包（无放大，但与
     *  网络层其余包的节流思路不一致）——10 tick 窗口内只回发一次，恶意刷包
     *  不再逐包响应。主线程独占访问（包处理经 enqueueWork），HashMap 即可。 */
    private static final java.util.Map<UUID, Long> REJECT_THROTTLE = new java.util.HashMap<>();

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
        } else if (!player.onGround()) {
            // 拒绝时拉回客户端预测，防独飞（10 tick 节流见 REJECT_THROTTLE 注释）。
            // 落地瞬间的拒绝属于落地竞态（服务端 AIR_JUMPED 在 tick END 清零，客户端
            // MovementInputUpdateEvent 已先清零）——此时客户端位置已随落地自然收敛，
            // 拉回包会把客户端预测的 Y 和御风冲刺一起拉掉，产生可见顿挫，故跳过。
            long now = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getTickCount();
            Long last = REJECT_THROTTLE.get(player.getUUID());
            if (last == null || now - last >= 10) {
                REJECT_THROTTLE.put(player.getUUID(), now);
                player.connection.send(new ClientboundSetEntityMotionPacket(player));
            }
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
        if (player.isFallFlying()) return false; // 鞘翅滑翔不触发二段跳（与客户端 JumpInputHandler 一致）
        if (player.onClimbable()) return false;  // 攀爬（梯子/藤蔓）不触发二段跳（与客户端一致）
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
            .map(p -> p.isAdventurer() && p.isAbilityEnabled(AbilityIds.VOID_STEP))
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
     * 每 tick 检测落地 -> 清除「本周期已跳」标记。
     * 由 PlayerTickDispatcher 分发（END phase，仅冒险者玩家调用；
     * 非冒险者不可能进入 AIR_JUMPED，无条目需要清理）。
     */
    public static void onTick(Player player) {
        if (player.onGround()) {
            AIR_JUMPED.remove(player.getUUID());
        }
    }

    /** 玩家登出 -> 清理 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        AIR_JUMPED.remove(event.getEntity().getUUID());
        REJECT_THROTTLE.remove(event.getEntity().getUUID());
    }

    /** 玩家死亡重生 / 跨维度（Clone）-> 清理残留 */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        AIR_JUMPED.remove(event.getEntity().getUUID());
        REJECT_THROTTLE.remove(event.getEntity().getUUID());
    }
}
