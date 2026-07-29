package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;

/**
 * 经验加成·智识加冕 - 拾取经验球时经验×倍率。
 * 解锁条件：5 里程碑（初次附魔）
 * 成长公式：mult = base + perMilestone × (count - required)
 * 觉醒：倍率再×倍率（由 XpBoostHandler 实现）
 */
public class XpBoostAbility extends LinearGrowthAbility {

    public XpBoostAbility() {
        super(5);
    }

    @Override
    public String id() {
        return "xp_boost";
    }

    @Override
    protected float base() {
        return ModConfig.XP_BOOST_BASE.get().floatValue();
    }

    @Override
    protected float perMilestone() {
        return ModConfig.XP_BOOST_PER_MILESTONE.get().floatValue();
    }
}
