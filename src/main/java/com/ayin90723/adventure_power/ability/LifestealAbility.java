package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;

/**
 * 嗜血 — 攻击造成伤害时回复自身生命值。
 * 解锁条件：6 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：5% → 13%
 */
public class LifestealAbility extends LinearGrowthAbility {

    public LifestealAbility() {
        super(6);
    }

    @Override
    public String id() {
        return "lifesteal";
    }

    @Override
    protected float base() {
        return ModConfig.LIFESTEAL_BASE.get();
    }

    @Override
    protected float perMilestone() {
        return ModConfig.LIFESTEAL_PER_MILESTONE.get();
    }
}