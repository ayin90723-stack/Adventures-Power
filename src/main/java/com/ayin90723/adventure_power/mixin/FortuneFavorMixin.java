package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.util.FortuneContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 鸿运当头 — 时运加成 Mixin。
 * <p>
 * 拦截 {@link EnchantmentHelper#getItemEnchantmentLevel(Enchantment, ItemStack)}
 * 当查询时运等级（BLOCK_FORTUNE）时，检测 {@link FortuneContext} 中是否有破坏者，
 * 若有且破坏者启用了鸿运当头，则在原始等级上叠加能力加成。
 * <p>
 * 目标方法 SRG：m_44843_ (Lnet/minecraft/world/item/enchantment/Enchantment;Lnet/minecraft/world/item/ItemStack;)I
 */
@Mixin(EnchantmentHelper.class)
public class FortuneFavorMixin {

    @Inject(method = "m_44843_", at = @At("RETURN"), cancellable = true)
    private static void onGetItemEnchantmentLevel(Enchantment enchantment, ItemStack stack,
                                                   CallbackInfoReturnable<Integer> cir) {
        // 只处理时运查询
        if (enchantment != Enchantments.BLOCK_FORTUNE) return;

        Player player = FortuneContext.getBreaker();
        if (player == null) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("fortune_favor")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            Ability ability = AbilityRegistry.get("fortune_favor");
            if (ability == null) return;

            int bonus = (int) ability.value(progress.getUnlockedMilestoneCount());
            if (FortuneContext.isAwakened()) {
                bonus += 2;
            }
            cir.setReturnValue(cir.getReturnValue() + bonus);
        });
    }
}
