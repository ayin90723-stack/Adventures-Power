package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 灵巧 — 攻击速度提升。
 * 解锁条件：1 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：10% → 73%
 */
public class AgilityAbility implements Ability {

    @Override
    public String id() {
        return "agility";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.agility");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.agility.desc");
    }

    @Override
    public int requiredMilestones() {
        return 1;
    }

    /**
     * 返回攻击速度提升百分比。从配置读取 base + per_milestone × (milestones - required)。
     */
    @Override
    public float value(int milestones) {
        return ModConfig.AGILITY_BASE.get() + ModConfig.AGILITY_PER_MILESTONE.get() * (milestones - requiredMilestones());
    }
}
