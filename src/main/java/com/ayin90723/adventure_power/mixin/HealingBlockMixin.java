package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.effect.HealingBlockEffect;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 禁疗之触 —— 双向 Mixin：跟踪血量 + 深层钳制。
 * <p>
 * <b>跟踪层 ({@code setHealth} RETURN)</b>：记录每次合法血量下降后的新低点，
 * 只要实体走 {@code LivingEntity.setHealth()} 路径就会同步追踪值。
 * <p>
 * <b>钳制层 ({@code tick()} TAIL)</b>：灵感来自 Jerotes 泽林变体的"灾害凝视"——
 * 在实体 tick 末尾（所有 AI、回血、Forge 事件全部执行完毕之后）做最终检查。
 * 若血量高于追踪的低点，用 {@link HealthUtil#setHealthDirect} 直接写入
 * SynchedEntityData 兜底，不依赖 Forge 事件优先级。
 * <p>
 * 对于重写 {@code setHealth()} 不调用 super 的 Boss，跟踪层不会触发，
 * 但钳制层仍会在 tick 末尾将其血量拉回——覆盖所有回血路径，
 * 无论回血走了什么方法调用链。
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public class HealingBlockMixin {

    /** 测试日志：禁疗钳制追踪（亚波伦测试用，调试完移除） */

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
            // 分级直写：通用层 → 对象图插针 → DataItem 兜底
            HealthUtil.setHealthLikeAny(self, tracked);
            current = tracked;
        }
        HealingBlockEffect.updateTrackedHealth(self, Math.min(current, tracked));
    }
}
