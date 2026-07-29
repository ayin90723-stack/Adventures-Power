package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;

/**
 * 加速·追风者 - 疾跑时获得速度加成 + 大幅降低疾跑饱食消耗。
 * 解锁条件：3 里程碑（初次交易）
 * 成长公式：speedAmplifier = base + perMilestone × (count - required)
 * 觉醒：疾跑碰撞推开敌对生物（由 SwiftHandler 实现）
 */
public class SwiftAbility extends LinearGrowthAbility {

    public SwiftAbility() {
        super(3);
    }

    @Override
    public String id() {
        return "swift";
    }

    @Override
    protected float base() {
        return ModConfig.SWIFT_SPEED_BASE.get().floatValue();
    }

    @Override
    protected float perMilestone() {
        return ModConfig.SWIFT_SPEED_PER_MILESTONE.get().floatValue();
    }
}
