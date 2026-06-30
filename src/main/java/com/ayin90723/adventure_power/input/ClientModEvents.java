package com.ayin90723.adventure_power.input;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.MOD)
public class ClientModEvents {
   public static final KeyMapping BUFF_MANAGEMENT = new KeyMapping("key.adventure_power.buff_screen", GLFW.GLFW_KEY_O, "key.categories.adventure_power");
   public static final KeyMapping ABILITY_MANAGEMENT = new KeyMapping("key.adventure_power.ability_screen", GLFW.GLFW_KEY_P, "key.categories.adventure_power");
   public static final KeyMapping SKILL_SWITCH = new KeyMapping("key.adventure_power.skill_switch", GLFW.GLFW_KEY_Y, "key.categories.adventure_power");
   public static final KeyMapping SKILL_ACTIVATE = new KeyMapping("key.adventure_power.skill_activate", GLFW.GLFW_KEY_G, "key.categories.adventure_power");
   public static final KeyMapping MILESTONE_PROGRESS = new KeyMapping("key.adventure_power.milestone_progress", GLFW.GLFW_KEY_M, "key.categories.adventure_power");

   @SubscribeEvent
   public static void registerKeys(RegisterKeyMappingsEvent event) {
      event.register(BUFF_MANAGEMENT);
      event.register(ABILITY_MANAGEMENT);
      event.register(SKILL_SWITCH);
      event.register(SKILL_ACTIVATE);
      event.register(MILESTONE_PROGRESS);
   }
}
