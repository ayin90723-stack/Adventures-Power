package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.FriendlyFireProtection;
import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 破敌之眼 Layer 0 - 穿透通过重写 {@code hurt()} 且不调用 {@code super.hurt()} 实现的自定义无敌。
 * <p>
 * 第一层 {@link PiercingGazeMixin} 拦截 {@code isInvulnerableTo()}、第二层
 * {@link PiercingGazeLivingEntityMixin} 拦截 {@code LivingEntity.hurt()} 的 RETURN，
 * 但部分 Boss（如暮色森林巫妖、Mowzie 钢铁守护者）完全重写 {@code hurt()} 方法，在自身护盾/
 * vulnerable 检查失败后直接 {@code return false}，从不调用 {@code super.hurt()}。
 * 这导致 {@code LivingEntity.hurt()} 内的所有注入点（含 Layer 2 / Layer 2.5）永远不会触发。
 * <p>
 * 本 Mixin 在 {@link Player#attack(Entity)} 中拦截 {@code target.hurt()} 调用：
 * <ul>
 *   <li>先正常调用 {@code target.hurt(source, amount)}</li>
 *   <li>若返回 false 且攻击者持有破敌之眼 -> 手动触发 {@code LivingHurtEvent}
 *       （让淬魂等附魔正常追加伤害），再通过 {@code actuallyHurt} + 血量直写兜底穿透</li>
 *   <li>返回 true，确保原版击退/火焰附加等附魔效果正常执行</li>
 * </ul>
 * <p>
 * 与 Layer 2 配合：若 {@code hurt()} 内部调用了 {@code super.hurt()}，Layer 2
 * 已通过 {@code cir.setReturnValue(true)} 将返回值改为 true，本 Mixin 看到 true
 * 时直接放行，不会重复处理。
 *
 * <h3>已知局限</h3>
 * 本层注入 {@code Player.attack}，属"攻击发起侧"。整合包中的战斗优化模组
 * （BetterCombat / Epic Fight 等）会接管攻击流程、绕开 {@code Player.attack}，
 * 导致本层注入点不触发。此场景下对"重写 hurt 不调 super"的 Boss 暂无纯 Mixin 通用解，
 * 需 ASM CoreMod 或针对性 Mixin 兜底（见 docs/piercing-gaze-asm-proposal.md）。
 *
 * @see PiercingGazeMixin
 * @see PiercingGazeLivingEntityMixin
 * @see PiercingGazeUtil
 */
@Mixin(Player.class)
public class PiercingGazePlayerAttackMixin {

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

        // 伤害已成功 -> 无需穿透
        if (hurtResult) return true;

        // hurt 返回 false：Boss 重写 hurt() 且不调 super（如巫妖护盾、钢铁守护者 vulnerable=false）
        // -> 攻击者持破敌之眼时走自有伤害链穿透
        if (!PiercingGazeUtil.hasPiercingGaze(self)) return false;

        // 友好火力保护
        if (FriendlyFireProtection.isOwnerTarget(self, target)) return false;

        // 穿透结算三连（与 Layer 2 情况 A 完全一致）：
        // 1. post LivingHurtEvent（取 max 防限伤，让淬魂/影杀 正常追加伤害）
        // 2. actuallyHurt 直写（绕过 hurt 内护甲/无敌判定）
        // 3. 血量直写兜底 + 清自定义无敌字段（防 Boss 注入 setHealth 恢复 / 锁死影杀 NBT）
        float healthBefore = target.getHealth();
        float effective = PiercingGazeUtil.postHurtEvent(target, source, amount);
        PiercingGazeUtil.invokeActuallyHurt(target, source, effective);
        PiercingGazeUtil.afterPierceFallback(target, effective, healthBefore);

        return true; // 返回 true 让击退/火焰附加等附魔正常执行
    }
}
