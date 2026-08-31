package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 破敌之眼 Layer 0.5 - 弹射物攻击发起侧穿透拦截（v1.4.8）。
 * <p>
 * 近战 {@code Player.attack}（Layer 0）自 v1.4.5 起是完整穿透触发器，但弹射物命中走
 * {@code AbstractArrow.onHitEntity} 的 {@code target.hurt()} 调用点——近战侧的拦截
 * 覆盖不到。对覆写 {@code hurt()} 且不调 super、不发 Forge 事件的 Boss（读层绕开
 * {@code LivingEntity.hurt} 基类注入的形态），弹射物路径上：
 * <ul>
 *   <li>淬魂/禁疗/嗜血等挂 {@code LivingHurtEvent} 的结算无从触发（事件不补发）；</li>
 *   <li>破敌穿透三连无从发起（Layer 2 钩子失明）。</li>
 * </ul>
 * 本层在 {@code onHitEntity} 的 hurt 调用点做同款 @Redirect，主体逻辑与近战层
 * 完全共用（{@link PiercingGazeUtil#interceptAttackHurt}）：扣血/扣吸收即放行
 * （未 post 事件则补发），未生效且持破敌之眼则穿透三连。伤害归属追溯由
 * {@code shouldPierce} 内的 {@code resolveAttacker} 走弹射物 owner。覆盖面：
 * 普通箭/药水箭（Arrow）/光灵箭（SpectralArrow）等未覆写 onHitEntity 的
 * AbstractArrow 子类；<b>ThrownTrident 完全覆写 onHitEntity</b>，由独立的
 * {@link PiercingGazeTridentMixin} 锚定；第三方箭覆写 onHitEntity 全链的属已知边界。
 * <p>
 * 注入 {@code m_5790_}（onHitEntity，SRG 已在 tsrg 核实）；{@code require = 0} 沿用
 * 近战层纪律（调用点被其他模组抢占时静默让位），{@code expect = 1} 输出缺失 WARN。
 * 客户端侧（箭的本地命中预测）由公共入口内的 isClientSide 判定直接走原版。
 *
 * @see PiercingGazePlayerAttackMixin
 * @see PiercingGazeUtil
 */
@Mixin(AbstractArrow.class)
public class PiercingGazeArrowMixin {

    @Redirect(
        method = "m_5790_",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z"
        ),
        require = 0,
        expect = 1
    )
    private boolean redirectArrowHurt(Entity target, DamageSource source, float amount) {
        return PiercingGazeUtil.interceptAttackHurt((AbstractArrow)(Object)this, target, source, amount);
    }
}
