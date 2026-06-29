package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.mixin.LivingEntityAccessor;
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
 * 参照 AirHop 的设计：使用持续跳跃键检测（而非边沿检测），配合原版
 * {@code noJumpDelay} 机制防止连发。玩家按住空格即可在冷却到期后
 * 自动触发空中跳跃，无需松开再按。
 * </p>
 */
@EventBusSubscriber(Dist.CLIENT)
public class JumpInputHandler {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent event) {
        if (event.phase == Phase.END) {
            var mc = Minecraft.getInstance();
            var player = mc.player;
            if (player == null || mc.level == null) return;

            // 持续检测跳跃键（类似 AirHop），而非边沿检测。
            // 配合原版 noJumpDelay 防止连发：地面起跳时原版会设置延迟，
            // 空中多段跳后我们手动设置延迟，自然形成触发间隔。
            boolean jumpDown = mc.options.keyJump.isDown();

            if (jumpDown) {
                LivingEntityAccessor accessor = (LivingEntityAccessor) player;

                if (accessor.adp$getNoJumpDelay() <= 0
                    && !player.onGround()
                    && !player.getAbilities().flying
                    && !player.isPassenger()
                    && !player.isInWater()) {

                    // 客户端立即执行跳跃（即时视觉反馈）
                    player.jumpFromGround();
                    // 复用原版 noJumpDelay 防止连发（10 tick ≈ 0.5 秒）
                    accessor.adp$setNoJumpDelay(10);
                    // 发送请求到服务端执行正式逻辑
                    NetworkHandler.sendDoubleJumpRequest();
                }
            }
        }
    }
}
