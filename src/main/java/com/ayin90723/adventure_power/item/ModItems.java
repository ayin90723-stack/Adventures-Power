package com.ayin90723.adventure_power.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, "adventure_power");

    public static final RegistryObject<Item> ADVENTURE_BEGIN =
        ITEMS.register("adventure_begin", () -> new AdventureCurioItem(false));

    public static final RegistryObject<Item> ADVENTURE_END =
        ITEMS.register("adventure_end", () -> new AdventureCurioItem(true));

    // ===== 创造模式物品栏 =====

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "adventure_power");

    public static final RegistryObject<CreativeModeTab> ADVENTURE_POWER_TAB =
        CREATIVE_MODE_TABS.register("adventure_power", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.adventure_power"))
            .icon(() -> new ItemStack(ADVENTURE_BEGIN.get()))
            .displayItems((params, output) -> {
                // 仅放冒险的开始：终点只通过 10 里程碑自动替换获得（无创造途径，防解锁绕过）
                output.accept(ADVENTURE_BEGIN.get());
            })
            .build());
}
