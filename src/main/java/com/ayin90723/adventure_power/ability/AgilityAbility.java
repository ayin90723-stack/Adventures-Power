package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 灵巧 - 概率完全闪避伤害。
 * 解锁条件：1 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：10% -> 73%
 */
public class AgilityAbility extends LinearGrowthAbility {

    public AgilityAbility() {
        super(1);
    }

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
    protected float base() {
        return ModConfig.AGILITY_BASE.get();
    }

    @Override
    protected float perMilestone() {
        return ModConfig.AGILITY_PER_MILESTONE.get();
    }
}