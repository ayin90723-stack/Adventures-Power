package com.ayin90723.adventure_power.effect;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 冒险的能力自定义属性注册
 */
@EventBusSubscriber(modid = "adventure_power", bus = Bus.MOD)
public class ModAttributes {
   public static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(ForgeRegistries.ATTRIBUTES, "adventure_power");

   /** 淬魂伤害 —— 加算到 SoulStrike 的最终伤害中 */
   public static final RegistryObject<Attribute> SOUL_STRIKE_DAMAGE = ATTRIBUTES.register(
      "soul_strike_damage",
      () -> new RangedAttribute("attribute.adventure_power.soul_strike_damage", 0.0, 0.0, 2048.0).setSyncable(true)
   );

   public static void register(IEventBus eventBus) {
      ATTRIBUTES.register(eventBus);
   }

   /** 将冒险的能力自定义属性附加到所有生物上，使 getAttributeValue() 可用 */
   @SubscribeEvent
   public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
      ATTRIBUTES.getEntries().forEach(entry -> {
         event.getTypes().forEach(type -> event.add(type, entry.get()));
      });
   }
}
