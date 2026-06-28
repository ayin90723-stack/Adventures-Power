package com.rosycentury.adventure_power.mixin;

import com.rosycentury.adventure_power.AdventurePower;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.util.FriendlyFireProtection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 见既斩 Mixin — 穿透目标的 isInvulnerableTo() 无敌状态。
 * <p>
 * 注入点: Entity.isInvulnerableTo() 的 HEAD。
 * 之前使用 @Redirect 拦截 LivingEntity.hurt() 内部的 isInvulnerableTo() 调用，
 * 但 Forge 的类加载期 patch 会修改 hurt() 的字节码结构，导致 @At(INVOKE) 匹配失败。
 * 改为直接在 isInvulnerableTo() 入口处截断：若攻击者持有见既斩，立即返回 false，
 * 对一切实体（含 Boss、模组生物）生效，且不受 Forge 字节码 patch 影响。
 * <p>
 * 第二层兜底由 {@link SeeAndSlashLivingEntityMixin} 提供：拦截直接重写 hurt()
 * 而不调用 isInvulnerableTo() 的自定义无敌。
 */
@Mixin(Entity.class)
public class SeeAndSlashMixin {

    /**
     * 在 isInvulnerableTo() 入口处检查攻击者是否持有见既斩。
     * 若持有 → 返回 false（穿透一切无敌），不执行原方法。
     */
    @Inject(
        method = "m_6673_",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onIsInvulnerableTo(DamageSource source,
                                     CallbackInfoReturnable<Boolean> cir) {
        // 只在服务端侧穿透无敌：BetterCombat 等模组会在客户端侧调用 player.attack()
        // 做攻击预测，若在客户端侧强制让 isInvulnerableTo() 返回 false，会导致
        // hurt() 越过早期退出、触发 Forge 的 LivingHurtEvent 钩子，进而触发
        // ElementalCombat 等模组的 ClientLevel→ServerLevel 强转崩溃。
        // 真正的无敌穿透由服务端侧的同一次 Mixin 触发完成，客户端侧跳过即可。
        Entity self = (Entity)(Object)this;
        if (self.level().isClientSide()) {
            return;
        }

        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        // 弹射物（箭、弩箭、火球等）：追溯到发射者
        if (attacker instanceof Projectile projectile) {
            attacker = projectile.getOwner();
        }
        // 攻击者持有见既斩 → 强制穿透无敌
        if (attacker instanceof LivingEntity living && hasSeeAndSlash(living)) {
            // 友好火力保护：不穿透自己驯服生物的无敌
            if (self instanceof LivingEntity target
                && FriendlyFireProtection.isOwnerTarget(living, target)) {
                return;
            }
            cir.setReturnValue(false);
        }
    }

    /**
     * 检查 LivingEntity 主手或副手物品是否拥有见既斩附魔，
     * 且（对于玩家）是否佩戴了冒险的终点饰品。
     */
    private static boolean hasSeeAndSlash(LivingEntity entity) {
        return AdventurePower.hasPiercingGaze(entity)
            && (!(entity instanceof Player player)
                || AdventureProgressCapability.isAbilityAvailable(player, "piercing_gaze"));
    }
}
