package com.ayin90723.adventure_power;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.effect.ModAttributes;
import com.ayin90723.adventure_power.effect.ModEffects;
import com.ayin90723.adventure_power.item.ModItems;
import com.ayin90723.adventure_power.network.NetworkHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(AdventurePower.MODID)
public class AdventurePower {

    public static final String MODID = "adventure_power";

    public AdventurePower() {
        ModLoadingContext.get().registerConfig(Type.COMMON, ModConfig.SPEC, "adventure_power.toml");

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册（冒险的终点仅通过「10 里程碑自动替换」获得，无合成配方）
        ModEffects.register(modEventBus);
        ModAttributes.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModItems.CREATIVE_MODE_TABS.register(modEventBus);

        // 事件订阅均走各 handler 的 @EventBusSubscriber（本类无 @SubscribeEvent，
        // 无需 EVENT_BUS.register——v1.4.0 审查清理冗余注册）
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::register);
    }

    // ===== 能力检查（Mixin 调用） =====

    public static boolean hasPiercingGaze(LivingEntity entity) {
        if (entity instanceof Player player) {
            return AdventureProgressCapability.getAdventureProgress(player)
                .map(p -> (p.isAdventurer() || p.isFullyUnlocked()) && p.isAbilityEnabled(AbilityIds.PIERCING_GAZE)).orElse(false);
        }
        return false;
    }

    public static boolean hasUndyingGear(Player player) {
        return AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> (p.isAdventurer() || p.isFullyUnlocked()) && p.isAbilityEnabled(AbilityIds.UNDYING_GEAR)).orElse(false);
    }
}
