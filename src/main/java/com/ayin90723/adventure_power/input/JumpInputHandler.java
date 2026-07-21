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
 * 参照 AirHop 的设计：使用持续跳跃键检测（而非边沿检测），配合冷却机制防止连发。
 * 与原版 {@code noJumpDelay} 行为等价：玩家起跳后按住空格，冷却到期自动触发空中跳跃。
 * </p>
 * <p>
 * 冷却逻辑：
 * </p>
 * <ol>
 *   <li>地面起跳瞬间 -> 冷却设为 10 tick（匹配原版 noJumpDelay 行为）</li>
 *   <li>每 tick 递减冷却</li>
 *   <li>冷却归零 + 空中 + 按住空格 + 仍有剩余跳数 -> 触发多段跳 -> 冷却重置为 10</li>
 *   <li>落地时冷却与跳数清零</li>
 * </ol>
 * <p>
 * <b>客户端跳数限制（关键）</b>：客户端必须自行维护 {@code clientJumpsUsed} 并以
 * {@link VoidStepMovement#getMaxAirJumps} 为天花板。否则超过服务端允许次数后，客户端仍会持续
 * 施力（覆盖 Y + 疾跑水平冲量），而服务端不再发 motion 包纠正 -> 水平速度累积飞出、垂直悬停，
 * 即历史"突然飞出去"bug 的根因。
 * </p>
 * <p>
 * <b>施力一致性</b>：客户端预测调用 {@link VoidStepMovement#applyJump}，与服务端完全相同的公式，
 * 服务端 motion 包到达时数值一致，无抖动。
 * </p>
 */
@EventBusSubscriber(Dist.CLIENT)
public class JumpInputHandler {
    /** 空中跳跃冷却（tick），模拟原版 noJumpDelay 行为 */
    private static int jumpCooldown = 0;
    /** 上一 tick 的 onGround 状态，用于检测"刚离开地面"的瞬间 */
    private static boolean wasOnGround = true;
    /** 客户端已消耗的空中跳跃次数（与服务端 JUMPS_USED 对齐），落地清零 */
    private static int clientJumpsUsed = 0;
    /** 上一 tick 的玩家引用，用于检测死亡重生/跨维度导致的实体替换 */
    private static Player lastPlayer = null;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent event) {
        if (event.phase == Phase.END) {
            var mc = Minecraft.getInstance();
            var player = mc.player;
            if (player == null || mc.level == null) return;

            // 玩家引用变化（死亡重生/跨维度）-> 重置全部状态，避免残留导致异常
            if (player != lastPlayer) {
                lastPlayer = player;
                jumpCooldown = 0;
                wasOnGround = true;
                clientJumpsUsed = 0;
            }

            boolean onGround = player.onGround();

            // 刚离开地面 -> 设置初始冷却（与原版 noJumpDelay = 10 行为一致）
            if (wasOnGround && !onGround && jumpCooldown <= 0) {
                jumpCooldown = 10;
            }

            // 冷却递减
            if (jumpCooldown > 0) {
                jumpCooldown--;
            }

            // 落地 -> 重置冷却与跳数
            if (onGround) {
                jumpCooldown = 0;
                clientJumpsUsed = 0;
            }

            wasOnGround = onGround;

            // 持续检测跳跃键（类似 AirHop），而非边沿检测
            boolean jumpDown = mc.options.keyJump.isDown();

            // 门禁：必须已激活冒险者 + 已解锁虚空踏步能力
            boolean abilityReady = AdventureProgressCapability.getAdventureProgress(player)
                .map(p -> p.isAdventurer() && p.isAbilityEnabled("void_step"))
                .orElse(false);

            if (jumpDown
                && jumpCooldown <= 0
                && !onGround
                && !player.getAbilities().flying
                && !player.isPassenger()
                && !player.isInWater()
                && abilityReady
                && clientJumpsUsed < VoidStepMovement.getMaxAirJumps(player)) {

                // 客户端预测：与服务端相同的 Y 计算，水平冲量不加（由服务端加，避免重复叠加）
                float power = VoidStepMovement.calculateJumpPower(player);
                VoidStepMovement.applyJump(player, power, false);

                // 防止连发
                jumpCooldown = 10;
                // 消耗客户端跳数（与服务端对齐）
                clientJumpsUsed++;
                // 发送请求到服务端执行正式逻辑（服务端再校验跳数并同步 motion）
                NetworkHandler.sendDoubleJumpRequest();
            }
        }
    }
}
