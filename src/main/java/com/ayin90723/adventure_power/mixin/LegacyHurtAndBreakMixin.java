package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.AdventurePower;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 传承/永恒附魔 — 永恒装备 Mixin。
 * <p>
 * 注入 {@link ItemStack#hurtAndBreak(int, LivingEntity, Consumer)} 的 HEAD，
 * 若物品带有传承或永恒附魔则直接跳过耐久扣除和碎裂逻辑，实现真正的无限耐久。
 * 永恒附魔额外将耐久值清零——确保从任意途径（铁砧、整合包机器、KubeJS 等）
 * 首次获得永恒时自动回满耐久。
 * <p>
 * SRG 名: {@code m_41622_ (ILnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V hurtAndBreak}
 */
@Mixin(ItemStack.class)
public class LegacyHurtAndBreakMixin {

    /**
     * 在 hurtAndBreak() 入口检查传承/永恒附魔，有则取消整个耐久扣除流程。
     * <p>
     * 方法签名为 {@code hurtAndBreak(int amount, LivingEntity entity, Consumer<LivingEntity> onBroken)}，
     * 泛型方法 {@code <T extends LivingEntity>}，字节码层面参数为 int, LivingEntity, Consumer。
     */
    @Inject(
        method = "m_41622_",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onHurtAndBreak(int amount, LivingEntity entity, Consumer<LivingEntity> onBroken,
                                 CallbackInfo ci) {
        // TODO: 替换为正式能力检查（round 9）
        if (entity instanceof Player player && AdventurePower.hasUndyingGear(player)) {
            ItemStack self = (ItemStack) (Object) this;
            ci.cancel();
            self.setDamageValue(0);
            return;
        }
        if (entity instanceof Player player && AdventurePower.hasTenacity(player)) {
            ci.cancel();
        }
    }
}
