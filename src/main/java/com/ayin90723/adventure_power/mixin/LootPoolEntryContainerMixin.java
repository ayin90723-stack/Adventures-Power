package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.handler.LootAllHandler;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 满载而归 - 绕过 entry 级条件检查。
 * <p>
 * 注入 {@link LootPoolEntryContainer#canRun(LootContext)}（SRG m_79639_）HEAD，
 * 当 {@link LootAllHandler#BYPASS} 标志位为 true（满载而归额外滚取期间）时强制返回 true，
 * 使所有 entry 级 LootItemCondition 被无视--无需满足火杀/玩家杀/特定方法击杀等条件。
 * <p>
 * 标志位隔离：原版掉落流程 BYPASS=false，canRun 行为不变，不受影响。
 * <p>
 * canRun 为 protected final 方法，Mixin 可直接注入。
 */
@Mixin(LootPoolEntryContainer.class)
public abstract class LootPoolEntryContainerMixin {

    @Inject(method = "m_79639_", at = @At("HEAD"), cancellable = true)
    private void adventure_power$lootAllCanRun(LootContext context, CallbackInfoReturnable<Boolean> cir) {
        if (LootAllHandler.BYPASS.get()) {
            cir.setReturnValue(true);
        }
    }
}
