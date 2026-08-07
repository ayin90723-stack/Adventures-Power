package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.effect.HealingBlockEffect;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 禁疗之触 —— 三层拦截：源头改写 + 低点跟踪 + tick 末钳制。
 * <p>
 * <b>源头层 ({@code setHealth} HEAD)</b>：禁疗期间，若外部把血量往<b>高</b>写
 * （回血请求），立即用 {@link HealthUtil#setHealthLikeAny} 压回低点并 cancel
 * 原调用——真血字段和原版槽都不会被写高，血条不动。仿 Jerotes 泽林变体
 * "灾害凝视"的 {@code setHealth} 空实现（外部改血直接作废）精神。
 * <p>
 * <b>跟踪层 ({@code setHealth} RETURN)</b>：记录每次合法血量下降后的新低点，
 * 只要实体走 {@code LivingEntity.setHealth()} 路径就会同步追踪值。
 * <p>
 * <b>钳制层 ({@code tick()} TAIL + ServerTickEnd)</b>：tick 末尾做最终检查，
 * 血量高于低点则先直写真血，再走完整 {@code setHealth()} 链——对覆写
 * setHealth 且自写字段/自发包的 Boss（如妖怪的归家灵梦，setHealth →
 * setCombatProgress → 原版槽 + progress 字段 + 网络同步包），仅直写字段
 * 不会触发其客户端同步，血条会停在回满值；走完整链才能让它的同步机制
 * 把低值推给客户端，血条稳定在低点。
 * <p>
 * 覆盖所有回血路径：原版 heal()（LivingHealEvent cancel）、原版 setHealth
 * （HEAD 改写）、重写 setHealth 不调 super 的 Boss（tick 末直写真血兜底）。
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public class HealingBlockMixin {

    /** 源头层：{@code setHealth()} HEAD —— 禁疗期间回血请求直接改写为低点 */
    @Inject(method = "m_21153_", at = @At("HEAD"), cancellable = true)
    private void onSetHealthHead(float health, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        if (!HealingBlockEffect.isActive(self)) {
            return;
        }
        Float tracked = HealingBlockEffect.getTrackedHealth(self);
        if (tracked == null) {
            return;
        }
        // 回血请求（写高）：源头掐断 —— 真血压回低点 + cancel 原调用，
        // 原版槽/自写字段都不会被写高，客户端血条纹丝不动
        if (health > tracked) {
            DebugLog.healingBlock("[禁疗] setHealth HEAD 拦截回血: {} → 低点 {}（原请求 {}）",
                self, tracked, health);
            HealthUtil.setHealthLikeAny(self, tracked);
            ci.cancel();
        }
    }

    /** 跟踪层：{@code setHealth()} RETURN 时同步追踪低点 */
    @Inject(method = "m_21153_", at = @At("RETURN"))
    private void onSetHealthReturn(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        if (!HealingBlockEffect.isActive(self)) {
            return;
        }
        Float tracked = HealingBlockEffect.getTrackedHealth(self);
        // 架空参照读数：自定义血条实体（亚波伦）原版槽被架空，getHealthDirect 读到不动值，
        // 跟踪层必须取真实血量才能正确记录低点
        float current = HealthUtil.getEffectiveHealth(self);
        if (tracked != null) {
            float newTracked = Math.min(current, tracked);
            if (newTracked < tracked) {
                DebugLog.healingBlock("[禁疗] setHealth 跟踪: {} → 新低点 {}（原低点 {}）", self, newTracked, tracked);
            }
            HealingBlockEffect.updateTrackedHealth(self, newTracked);
        }
    }

    /**
     * 钳制层：{@code tick()} TAIL —— 所有 tick 逻辑结束后做最终血量钳制。
     * <p>
     * 在 tick 末尾注入，时序晚于 Forge 的 {@code LivingTickEvent}
     * 和 {@code ServerTickEvent.END}，覆盖所有回血路径。
     * 对标泽林变体"灾害凝视"在自身 tick 末尾做血量钳制的设计。
     */
    @Inject(method = "m_8119_", at = @At("TAIL"))
    private void onTickTailClamp(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        if (!HealingBlockEffect.isActive(self)) {
            return;
        }
        Float tracked = HealingBlockEffect.getTrackedHealth(self);
        if (tracked == null) {
            return;
        }
        // 架空参照读数：与 onSetHealthReturn 一致，自定义血条实体取真实血量检测回血
        float current = HealthUtil.getEffectiveHealth(self);
        if (current > tracked) {
            DebugLog.healingBlock("[禁疗] tick 末钳制: {} > {} → 直写 {}", current, tracked, tracked);
            HealingBlockEffect.clampBack(self, tracked);
            current = tracked;
        }
        HealingBlockEffect.updateTrackedHealth(self, Math.min(current, tracked));
    }
}
