package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 无形之手 — 增加方块交互距离。
 * 解锁条件：4 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：+1.0 → +2.8 格
 */
public class ExtendedReachAbility extends LinearGrowthAbility {

    public ExtendedReachAbility() {
        super(4);
    }

    @Override
    public String id() {
        return "extended_reach";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.extended_reach");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.extended_reach.desc");
    }

    @Override
    protected float base() {
        return ModConfig.EXTENDED_REACH_BASE.get().floatValue();
    }

    @Override
    protected float perMilestone() {
        return ModConfig.EXTENDED_REACH_PER_MILESTONE.get().floatValue();
    }
}