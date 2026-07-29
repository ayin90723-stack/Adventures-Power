package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;

/**
 * 磁吸·万物引归 - 周期扫描半径内掉落物吸向玩家。
 * 解锁条件：4 里程碑（初探地底）
 * 成长公式：radius = base + perMilestone × (count - required)
 * 觉醒：吸取半径×倍率 + 经验球也吸入（由 MagnetHandler 实现）
 */
public class MagnetAbility extends LinearGrowthAbility {

    public MagnetAbility() {
        super(4);
    }

    @Override
    public String id() {
        return "magnet";
    }

    @Override
    protected float base() {
        return ModConfig.MAGNET_RADIUS_BASE.get().floatValue();
    }

    @Override
    protected float perMilestone() {
        return ModConfig.MAGNET_RADIUS_PER_MILESTONE.get().floatValue();
    }
}
