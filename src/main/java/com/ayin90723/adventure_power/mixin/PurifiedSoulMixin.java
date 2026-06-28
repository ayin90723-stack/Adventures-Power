package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 净魂 — 从源头拦截负面效果。
 * <p>
 * 注入 {@code LivingEntity.canBeAffected(MobEffectInstance)} HEAD，
 * 当玩家启用净魂能力且效果类别为 HARMFUL 时直接返回 false，
 * 效果根本不会被添加到实体上——无 tick 延迟，无 buff 栏闪烁。
 * <p>
 * 替代之前 {@code MobEffectEvent.Applicable} 的 {@code setCanceled} 方案——
 * 该事件在 Forge 1.20.1 中不可取消，与 RevelationFix 等模组不兼容。
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public class PurifiedSoulMixin {

    @Inject(method = "m_7301_", at = @At("HEAD"), cancellable = true)
    private void onCanBeAffected(MobEffectInstance effectInstance, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        if (effectInstance.getEffect().getCategory() != MobEffectCategory.HARMFUL) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isAbilityEnabled("purified_soul")) {
                cir.setReturnValue(false);
            }
        });
    }
}
