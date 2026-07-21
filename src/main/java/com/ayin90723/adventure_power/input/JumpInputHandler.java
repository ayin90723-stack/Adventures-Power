package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 空中多段跳客户端输入检测。
 * <p>
 * 采用<b>边沿检测</b>：追踪空格「按下瞬间」，按一下跳一次，松开再按再触发下一次。
 * 地面起跳仍走原版（按住空格由原版 noJumpDelay 控制连续地面跳），空中跳必须松开空格再按。
 * 这样玩家从地面起跳后可立即按出二段跳，无强制冷却等待，手感对齐其他多段跳模组。
 * </p>
 * <p>
 * <b>陈旧网络包防护</b>：每个空中周期（落地->起跳->落地）分配唯一递增 {@code airPhaseId}，
 * 请求携带此 id，服务端比对不匹配直接丢弃，防止网络延迟导致陈旧包在新周期重新通过校验并叠加冲量。
 * </p>
 * <p>
 * <b>客户端跳数限制</b>：客户端必须自行维护 {@code clientJumpsUsed} 并以
 * {@link VoidStepMovement#getMaxAirJumps} 为天花板，避免超过服务端允许次数后客户端仍持续施力。
 * </p>
 * <p>
 * <b>施力分工</b>：客户端预测仅动 Y（{@code addSprintBoost=false}），水平冲量交服务端施加。
 * 避免两端 {@code isSprinting()} 采样不同步导致 motion 包覆盖时水平跳变，
 * 亦防止服务端静默 return 时客户端保留水平冲量斜飞（配合 {@link DoubleJumpHandler} 的统一 motion 同步兜底）。
 * </p>
 */
@EventBusSubscriber(Dist.CLIENT)
public class JumpInputHandler {
    /** 上一 tick 的 onGround 状态，用于检测"刚离开地面"的瞬间 */
    private static boolean wasOnGround = true;
    /** 客户端已消耗的空中跳跃次数（与服务端对齐），落地清零 */
    private static int clientJumpsUsed = 0;
    /** 当前空中周期 ID，落地递增，用于陈旧网络包防护 */
    private static int currentAirPhaseId = 0;
    /** 上一 tick 的空格按下状态，用于边沿检测（按下瞬间触发一次） */
    private static boolean wasJumpDown = false;
    /** 上一 tick 的玩家引用，用于检测死亡重生/跨维度导致的实体替换 */
    private static Player lastPlayer = null;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent event) {
        if (event.phase != Phase.END) return;

        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.level == null) return;

        // 玩家引用变化（死亡重生/跨维度）-> 重置跳数与按键状态，保留 airPhaseId 继续递增
        // （避免重生后 id 回到 0 与服务端残留 state 冲突；id 单调递增保证新周期必被识别）
        if (player != lastPlayer) {
            lastPlayer = player;
            wasOnGround = true;
            clientJumpsUsed = 0;
            wasJumpDown = false;
        }

        boolean onGround = player.onGround();

        // 刚离开地面 -> 新空中周期，id 递增（不再设冷却，消除"跳起后要等"的延迟）
        if (wasOnGround && !onGround) {
            currentAirPhaseId++;
        }

        // 落地 -> 重置跳数，保持周期 id（下次离开地面再递增）
        if (onGround) {
            clientJumpsUsed = 0;
        }
        wasOnGround = onGround;

        // 边沿检测：先算边沿（用上一帧 wasJumpDown），再更新 wasJumpDown
        boolean jumpDown = mc.options.keyJump.isDown();
        boolean jumpEdge = jumpDown && !wasJumpDown;
        wasJumpDown = jumpDown;

        // 门禁：必须已激活冒险者 + 已解锁虚空踏步能力
        boolean abilityReady = AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> p.isAdventurer() && p.isAbilityEnabled("void_step"))
            .orElse(false);
        if (!abilityReady) return;

        int maxJumps = VoidStepMovement.getMaxAirJumps(player);

        if (jumpEdge
            && !onGround
            && !player.getAbilities().flying
            && !player.isPassenger()
            && !player.isInWater()
            && clientJumpsUsed < maxJumps) {

            // 客户端预测：仅动 Y（水平冲量交服务端），公式与服务端一致，motion 包到达时数值一致无抖动
            float power = VoidStepMovement.calculateJumpPower(player);
            VoidStepMovement.applyJump(player, power, false);

            // 消耗客户端跳数（与服务端对齐）
            clientJumpsUsed++;
            // 发送请求到服务端执行正式逻辑，携带当前 airPhaseId 用于陈旧包校验
            NetworkHandler.sendDoubleJumpRequest(currentAirPhaseId);
        }
    }
}
