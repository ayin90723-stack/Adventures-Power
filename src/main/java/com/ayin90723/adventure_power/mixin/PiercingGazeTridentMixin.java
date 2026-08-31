package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownTrident;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 破敌之眼 Layer 0.5·三叉戟 - {@code ThrownTrident.onHitEntity} 穿透拦截（v1.4.8）。
 * <p>
 * 复查修（P2）：{@link PiercingGazeArrowMixin} 只锚定 {@code AbstractArrow.onHitEntity}，
 * 而 ThrownTrident <b>完全覆写</b>了 onHitEntity（tsrg 与字节码双重核实：自身方法体内
 * 独立的 hurt 调用、无 super 委托）——基类 Mixin 对它不可见，三叉戟命中路径需独立锚定。
 * 主体与近战/弓箭层共用 {@link PiercingGazeUtil#interceptAttackHurt}。
 * <p>
 * 药水箭（Arrow）/光灵箭（SpectralArrow）未覆写 onHitEntity，由
 * {@link PiercingGazeArrowMixin} 覆盖；第三方箭覆写 onHitEntity 全链的属已知边界。
 *
 * @see PiercingGazeArrowMixin
 */
@Mixin(ThrownTrident.class)
public class PiercingGazeTridentMixin {

    @Redirect(
        method = "m_5790_",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z"
        ),
        require = 0,
        expect = 1
    )
    private boolean redirectTridentHurt(Entity target, DamageSource source, float amount) {
        return PiercingGazeUtil.interceptAttackHurt((ThrownTrident)(Object)this, target, source, amount);
    }
}
