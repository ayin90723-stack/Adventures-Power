package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 不动如山 — 百分比减少受到的击退距离。
 * 解锁条件：3 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：30% → 79%
 */
public class KnockbackResistAbility extends LinearGrowthAbility {

    public KnockbackResistAbility() {
        super(3);
    }

    @Override
    public String id() {
        return "knockback_resist";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.knockback_resist");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.knockback_resist.desc");
    }

    @Override
    protected float base() {
        return ModConfig.KNOCKBACK_RESIST_BASE.get();
    }

    @Override
    protected float perMilestone() {
        return ModConfig.KNOCKBACK_RESIST_PER_MILESTONE.get();
    }
}