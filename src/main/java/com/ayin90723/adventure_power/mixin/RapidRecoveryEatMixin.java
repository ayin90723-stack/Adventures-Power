package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.AbilityIds;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 休养生息 — 饱食度满时仍可进食。
 * <p>
 * 注入 {@code Player.canEat(boolean)} HEAD（SRG: {@code m_36391_}），
 * 休养生息解锁的玩家在饱食度满时也允许食用食物。
 * 原版链路（Item#use → startUsingItem → completeUsingItem → eat → FoodData.eat）
 * 一字不改，仅放宽判定——依赖原版进食链路的模组（均衡饮食 diet、
 * 农夫乐事等通过进食成长的模组）均不受影响。
 * <p>
 * 与 AlwaysEat 的事件接管方案（cancel RightClickItem + 自行 startUsingItem）不同，
 * 本 Mixin 不绕过、不取消任何原版调用，兼容性更好。
 * <p>
 * 注意：canEat 在客户端与服务端都会调用（客户端判定决定是否开始食用动画），
 * 故不做 isClientSide 限制，两端均生效；服务端最终裁决，无作弊面。
 */
@Mixin(Player.class)
public class RapidRecoveryEatMixin {

    @Inject(method = "m_36391_", at = @At("HEAD"), cancellable = true)
    private void onCanEat(boolean alwaysEat, CallbackInfoReturnable<Boolean> cir) {
        if (alwaysEat) return; // canAlwaysEat 食物原版即可食用，无需干预
        if (!ModConfig.RAPID_RECOVERY_ALLOW_EAT_AT_FULL.get()) return;

        // 门禁：冒险者/觉醒 + 休养生息启用。调用频率低（仅右键食物时），直接查 capability 即可
        if (AbilityGate.isAbilityActive((Player) (Object) this, AbilityIds.RAPID_RECOVERY)) {
            cir.setReturnValue(true);
        }
    }
}
