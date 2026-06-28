package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber(Dist.CLIENT)
public class JumpInputHandler {
   private static boolean wasJumping = false;

   @SubscribeEvent
   public static void onClientTick(ClientTickEvent event) {
      if (event.phase == Phase.END) {
         var mc = Minecraft.getInstance();
         var player = mc.player;
         if (player == null || mc.level == null) return;

         boolean isJumping = mc.options.keyJump.isDown();

         // 空中按下空格（边沿检测：按下瞬间而非持续按住）
         if (isJumping && !wasJumping) {
            if (!player.onGround()
                && !player.getAbilities().flying
                && !player.isPassenger()
                && !player.isInWater()) {
               NetworkHandler.sendDoubleJumpRequest();
            }
         }

         wasJumping = isJumping;
      }
   }
}
