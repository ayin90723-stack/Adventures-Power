package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 无形之手 — 增加方块交互距离。
 * 解锁条件：4 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：+1.0 → +2.8 格
 */
public class ExtendedReachAbility implements Ability {

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

        private int countAtUnlock = 4;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return (float) (ModConfig.EXTENDED_REACH_BASE.get()
            + ModConfig.EXTENDED_REACH_PER_MILESTONE.get() * (count - countAtUnlock));
    }
}
