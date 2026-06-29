package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.AdventurePower;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * 不朽装备 / 受击坚韧 — 双重拦截耐久扣除。
 * <p>
 * <b>原版 hurtAndBreak</b>（{@code m_41622_}）：被原版和部分老模组使用。
 * <b>Forge hurt</b>（{@code m_220157_}）：Forge 1.20.1 新增方法，
 * LostEngine 等现代模组通过此方法消耗耐久。两个方法是独立实现，
 * Forge hurt 不会内部调用原版 hurtAndBreak——因此必须双点注入。
 * <p>
 * {@code m_220157_} 返回 {@code boolean}（物品是否损坏），
 * 返回 {@code false} 可阻止调用方（如 LostEngine）的"物品破碎→删除"逻辑。
 * <p>
 * SRG 名:
 * <ul>
 *   <li>{@code m_41622_ (ILnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V hurtAndBreak}</li>
 *   <li>{@code m_220157_ (ILnet/minecraft/util/RandomSource;Lnet/minecraft/server/level/ServerPlayer;)Z hurt}</li>
 * </ul>
 */
@Mixin(ItemStack.class)
public class LegacyHurtAndBreakMixin {

    // ===== 原版 hurtAndBreak =====

    /**
     * 拦截原版 {@code hurtAndBreak(int, LivingEntity, Consumer)}。
     * <p>
     * 泛型方法 {@code <T extends LivingEntity>}，字节码层面参数为 int, LivingEntity, Consumer。
     */
    @Inject(
        method = "m_41622_",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onHurtAndBreak(int amount, LivingEntity entity, Consumer<LivingEntity> onBroken,
                                 CallbackInfo ci) {
        if (entity instanceof Player player && AdventurePower.hasUndyingGear(player)) {
            ItemStack self = (ItemStack) (Object) this;
            ci.cancel();
            self.setDamageValue(0);
        }
    }

    // ===== Forge hurt（m_220157_）=====

    /**
     * 拦截 Forge 新增的 {@code hurt(int, RandomSource, ServerPlayer)}。
     * <p>
     * LostEngine 等模组通过此方法消耗耐久并检测物品是否损坏。
     * 返回 {@code false}（未损坏）阻止调用方的物品删除逻辑，
     * 同时重置耐久值——即使调用方后续删除 Unbreakable 标签也不影响实质保护。
     */
    @Inject(
        method = "m_220157_",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onForgeHurt(int amount, RandomSource random, ServerPlayer player,
                              CallbackInfoReturnable<Boolean> cir) {
        if (player != null && AdventurePower.hasUndyingGear(player)) {
            ItemStack self = (ItemStack) (Object) this;
            self.setDamageValue(0);
            cir.setReturnValue(false);
        }
    }
}
