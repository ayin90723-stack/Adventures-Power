package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;

/**
 * 伤害抗性 - 减少所受伤害百分比。
 * 解锁条件：4 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：10% -> 40%
 */
public class DamageResistAbility extends LinearGrowthAbility {

    public DamageResistAbility() {
        super(4);
    }

    @Override
    public String id() {
        return "damage_resist";
    }

    @Override
    protected float base() {
        return ModConfig.DAMAGE_RESIST_BASE.get();
    }

    @Override
    protected float perMilestone() {
        return ModConfig.DAMAGE_RESIST_PER_MILESTONE.get();
    }

    /** 最低 0 防负值（防御性：countAtUnlock 未设置或数据包异常时） */
    @Override
    public float value(int count) {
        return Math.max(0, super.value(count));
    }
}
