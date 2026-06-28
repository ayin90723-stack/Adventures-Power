package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 不动如山 — 百分比减少受到的击退距离。
 * 解锁条件：3 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：30% → 79%
 */
public class KnockbackResistAbility implements Ability {

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
    public int requiredMilestones() {
        return 3;
    }

    @Override
    public float value(int milestones) {
        return ModConfig.KNOCKBACK_RESIST_BASE.get()
            + ModConfig.KNOCKBACK_RESIST_PER_MILESTONE.get() * (milestones - requiredMilestones());
    }
}
