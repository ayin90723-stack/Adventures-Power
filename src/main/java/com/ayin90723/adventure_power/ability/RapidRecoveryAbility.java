package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;

/**
 * 休养生息 — 脱战后直写 SynchedEntityData 回血 + 恢复饱食度。
 * 解锁条件：2 里程碑
 * 成长公式：amplifier = base + step × ((count - required) / 2)
 * 默认：里程碑2=1HP/3s, 4=2HP/3s, 10=5HP/3s
 * <p>
 * 不使用药水效果（addEffect），避免被 MobEffectEvent / removeAllEffects 拦截。
 */
public class RapidRecoveryAbility extends StepGrowthAbility {

    public RapidRecoveryAbility() {
        super(2);
    }

    @Override
    public String id() {
        return "rapid_recovery";
    }

    @Override
    protected float base() {
        return ModConfig.RAPID_RECOVERY_AMPLIFIER_BASE.get();
    }

    @Override
    protected float step() {
        return ModConfig.RAPID_RECOVERY_AMPLIFIER_STEP.get();
    }
}