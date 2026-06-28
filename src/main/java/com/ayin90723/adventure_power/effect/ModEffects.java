package com.ayin90723.adventure_power.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEffects {
   public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, "adventure_power");
   public static final RegistryObject<MobEffect> UNDYING_SLASH = EFFECTS.register("undying_slash", UndyingSlashEffect::new);

   public static void register(IEventBus eventBus) {
      EFFECTS.register(eventBus);
   }
}
