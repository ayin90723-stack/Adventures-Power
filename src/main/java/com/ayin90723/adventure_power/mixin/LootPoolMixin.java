package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.handler.LootAllHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntry;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 满载而归 - 绕过 pool 级条件 + 打破 weight 单选。
 * <p>
 * 注入 {@link LootPool#addRandomItems(Consumer, LootContext)}（SRG m_79053_）HEAD，
 * 当 {@link LootAllHandler#BYPASS} 标志位为 true 时：
 * <ul>
 *   <li>遍历该 pool 的所有 entries（经 {@link LootPoolAccessor}），每个调用一次 createItemStack
 *       --createItemStack 内部的 canRun 已被 {@link LootPoolEntryContainerMixin} 让总返回 true</li>
 *   <li>cancel 原方法的 weight 抽取 + rolls 滚动逻辑</li>
 * </ul>
 * <p>
 * 效果：每个 entry 至少生成一份（每样一份），无视 pool 级 compositeCondition（原方法不执行，
 * 条件检查被跳过），无视 weight 概率。
 * <p>
 * 标志位隔离：原版掉落 BYPASS=false，走原 weight 抽取，不受影响。
 */
@Mixin(LootPool.class)
public abstract class LootPoolMixin {

    @Inject(method = "m_79053_", at = @At("HEAD"), cancellable = true)
    private void adventure_power$lootAllAddItems(Consumer<ItemStack> consumer, LootContext context, CallbackInfo ci) {
        if (LootAllHandler.BYPASS.get()) {
            LootPoolEntryContainer[] entries = ((LootPoolAccessor) (Object) this).adventure_power$getEntries();
            if (entries != null) {
                for (LootPoolEntryContainer entryContainer : entries) {
                    if (entryContainer != null) {
                        // expand 把 entryContainer 展开为 LootPoolEntry（其内部 canRun 已被
                        // LootPoolEntryContainerMixin 让总返回 true，无视条件），直接调 createItemStack
                        // 生成物品 -- 打破 weight 单选，每个 entry 各生成一份
                        entryContainer.expand(context, lootPoolEntry -> lootPoolEntry.createItemStack(consumer, context));
                    }
                }
            }
            ci.cancel();
        }
    }
}
