package com.ayin90723.adventure_power.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link LootPool#entries} 字段（package-private final，SRG f_79023_）。
 * <p>
 * 供 {@link LootPoolMixin} 在满载而归额外滚取时遍历 pool 的所有 entries，
 * 实现"每样一份"（打破 weight 单选）。
 */
@Mixin(LootPool.class)
public interface LootPoolAccessor {

    @Accessor("entries")
    LootPoolEntryContainer[] adventure_power$getEntries();
}
