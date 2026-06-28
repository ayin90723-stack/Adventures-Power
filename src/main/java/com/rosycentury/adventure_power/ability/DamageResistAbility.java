package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 伤害抗性 — 减少所受伤害百分比。
 * 解锁条件：4 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：10% → 40%
 */
public class DamageResistAbility implements Ability {

    @Override
    public String id() {
        return "damage_resist";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.damage_resist");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.damage_resist.desc");
    }

    @Override
    public int requiredMilestones() {
        return 4;
    }

    /**
     * 返回伤害减免百分比。从配置读取 base + per_milestone × (milestones - required)。
     */
    @Override
    public float value(int milestones) {
        return Math.max(0, ModConfig.DAMAGE_RESIST_BASE.get() + ModConfig.DAMAGE_RESIST_PER_MILESTONE.get() * (milestones - requiredMilestones()));
    }
}
