package com.rosycentury.adventure_power;

import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.config.ModConfig;
import com.rosycentury.adventure_power.effect.ModAttributes;
import com.rosycentury.adventure_power.effect.ModEffects;
import com.rosycentury.adventure_power.input.JumpInputHandler;
import com.rosycentury.adventure_power.item.AdventureEndRecipe;
import com.rosycentury.adventure_power.item.ModItems;
import com.rosycentury.adventure_power.network.NetworkHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(AdventurePower.MODID)
public class AdventurePower {

    public static final String MODID = "adventure_power";

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
        DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MODID);

    public static final RegistryObject<RecipeSerializer<AdventureEndRecipe>> ADVENTURE_END_RECIPE =
        RECIPE_SERIALIZERS.register("adventure_end", () -> AdventureEndRecipe.Serializer.INSTANCE);

    public AdventurePower() {
        ModLoadingContext.get().registerConfig(Type.COMMON, ModConfig.SPEC, "adventure_power.toml");

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册
        ModEffects.register(modEventBus);
        ModAttributes.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModItems.CREATIVE_MODE_TABS.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);

        // 事件订阅
        MinecraftForge.EVENT_BUS.register(JumpInputHandler.class);
        MinecraftForge.EVENT_BUS.register(this);

        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::register);
    }

    // ===== 能力检查（Mixin 调用） =====

    public static boolean hasPiercingGaze(LivingEntity entity) {
        if (entity instanceof Player player) {
            return AdventureProgressCapability.getAdventureProgress(player)
                .map(p -> p.isAdventurer() && p.isAbilityEnabled("piercing_gaze")).orElse(false);
        }
        return false;
    }

    public static boolean hasUndyingGear(Player player) {
        return AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> p.isAdventurer() && p.isAbilityEnabled("undying_gear")).orElse(false);
    }

    public static boolean hasTenacity(Player player) {
        return AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> p.isAdventurer() && p.isAbilityEnabled("resilience")).orElse(false);
    }
}
