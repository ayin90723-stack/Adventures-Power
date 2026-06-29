package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.network.NetworkHandler;
import net.minecraft.client.Minecraft;
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
 *   <li>地面起跳瞬间 → 冷却设为 10 tick（匹配原版 noJumpDelay 行为）</li>
 *   <li>每 tick 递减冷却</li>
 *   <li>冷却归零 + 空中 + 按住空格 → 触发多段跳 → 冷却重置为 10</li>
 *   <li>落地时冷却清零</li>
 * </ol>
 */
@EventBusSubscriber(Dist.CLIENT)
public class JumpInputHandler {
    /** 空中跳跃冷却（tick），模拟原版 noJumpDelay 行为 */
    private static int jumpCooldown = 0;
    /** 上一 tick 的 onGround 状态，用于检测"刚离开地面"的瞬间 */
    private static boolean wasOnGround = true;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent event) {
        if (event.phase == Phase.END) {
            var mc = Minecraft.getInstance();
            var player = mc.player;
            if (player == null || mc.level == null) return;

            boolean onGround = player.onGround();

            // 刚离开地面 → 设置初始冷却（与原版 noJumpDelay = 10 行为一致）
            if (wasOnGround && !onGround && jumpCooldown <= 0) {
                jumpCooldown = 10;
            }

            // 冷却递减
            if (jumpCooldown > 0) {
                jumpCooldown--;
            }

            // 落地 → 重置
            if (onGround) {
                jumpCooldown = 0;
            }

            wasOnGround = onGround;

            // 持续检测跳跃键（类似 AirHop），而非边沿检测
            boolean jumpDown = mc.options.keyJump.isDown();

            if (jumpDown
                && jumpCooldown <= 0
                && !onGround
                && !player.getAbilities().flying
                && !player.isPassenger()
                && !player.isInWater()) {

                // 客户端立即执行跳跃（即时视觉反馈）
                player.jumpFromGround();
                // 防止连发
                jumpCooldown = 10;
                // 发送请求到服务端执行正式逻辑
                NetworkHandler.sendDoubleJumpRequest();
            }
        }
    }
}
