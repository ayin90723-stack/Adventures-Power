package com.rosycentury.adventure_power.mixin;

import com.rosycentury.adventure_power.AdventurePower;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.util.FriendlyFireProtection;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 见既斩 Layer 0 — 穿透通过重写 {@code hurt()} 且不调用 {@code super.hurt()} 实现的自定义无敌。
 * <p>
 * 第一层 {@link SeeAndSlashMixin} 拦截 {@code isInvulnerableTo()}、第二层
 * {@link SeeAndSlashLivingEntityMixin} 拦截 {@code LivingEntity.hurt()} 的 RETURN，
 * 但部分 Boss（如暮色森林巫妖）完全重写 {@code hurt()} 方法，在自身护盾检查失败后
 * 直接 {@code return false}，从不调用 {@code super.hurt()}。
 * <p>
 * 这导致 {@code LivingEntity.hurt()} 内的所有注入点（含 Layer 2 / Layer 2.5）永远不会触发。
 * <p>
 * 本 Mixin 在 {@link Player#attack(Entity)} 中拦截 {@code target.hurt()} 调用：
 * <ul>
 *   <li>先正常调用 {@code target.hurt(source, amount)}</li>
 *   <li>若返回 false 且攻击者持有见既斩 → 手动触发 {@link LivingHurtEvent}
 *       （让淬魂等附魔正常追加伤害），再通过 {@code actuallyHurt} 穿透</li>
 *   <li>返回 true，确保原版击退/火焰附加等附魔效果正常执行</li>
 * </ul>
 * <p>
 * 与 Layer 2 配合：若 {@code hurt()} 内部调用了 {@code super.hurt()}，Layer 2
 * 已通过 {@code cir.setReturnValue(true)} 将返回值改为 true，本 Mixin 看到 true
 * 时直接放行，不会重复处理。
 *
 * @see SeeAndSlashMixin
 * @see SeeAndSlashLivingEntityMixin
 */
@Mixin(Player.class)
public class SeeAndSlashPlayerAttackMixin {

    /**
     * 替换 {@code Player.attack()} 内部的 {@code target.hurt(source, amount)} 调用。
     * <p>
     * 目标 {@code Entity.hurt} 为实例方法，SRG 名 {@code m_6469_}。
     * 使用 {@code require = 0} 避免因字节码结构变化导致注入失败。
     */
    @Redirect(
        method = "m_5706_",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z"
        ),
        require = 0
    )
    private boolean redirectAttackHurt(LivingEntity target, DamageSource source, float amount) {
        Player self = (Player)(Object)this;
        // 仅在服务端处理，客户端侧走原版管线
        if (self.level().isClientSide()) {
            return target.hurt(source, amount);
        }

        boolean hurtResult = target.hurt(source, amount);

        // 伤害已成功 → 无需穿透
        if (hurtResult) return true;

        LivingEntity living = target;

        // 检查攻击者是否持有见既斩
        if (!hasSeeAndSlash(self)) return false;

        // 友好火力保护
        if (FriendlyFireProtection.isOwnerTarget(self, living)) return false;

        // 穿透：手动触发 LivingHurtEvent（让淬魂/淬魂Plus 等正常追加伤害），
        // 再调用 actuallyHurt 直写伤害。与 Layer 2 的穿透逻辑完全一致。
        float effectiveAmount = amount;
        LivingHurtEvent hurtEvent = new LivingHurtEvent(living, source, amount);
        MinecraftForge.EVENT_BUS.post(hurtEvent);

        // 即使其他模组取消了 LivingHurtEvent，见既斩仍穿透——
        // 作为万能钥匙，不受外部事件取消的影响
        effectiveAmount = hurtEvent.getAmount();

        // 通过 Accessor 接口直写伤害。Mixin 接口注入使 LivingEntity 实现
        // SeeAndSlashLivingEntityAccessor，无需反射或跨 Mixin 强转。
        ((SeeAndSlashLivingEntityAccessor)living).invokeActuallyHurt(source, effectiveAmount);

        return true; // 返回 true 让击退/火焰附加等附魔正常执行
    }

    /**
     * 检查玩家是否持有见既斩附魔且满足门禁条件。
     * 与 {@link SeeAndSlashMixin#hasSeeAndSlash} 和
     * {@link SeeAndSlashLivingEntityMixin#hasSeeAndSlash} 逻辑一致。
     */
    @Unique
    private static boolean hasSeeAndSlash(Player player) {
        return AdventurePower.hasPiercingGaze(player)
            && AdventureProgressCapability.isAbilityAvailable(player, "piercing_gaze");
    }
}
