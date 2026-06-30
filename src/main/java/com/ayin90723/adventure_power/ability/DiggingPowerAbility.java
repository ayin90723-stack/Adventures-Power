package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 大地之力 — 提升挖掘速度。
 * 解锁条件：1 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：1.3x → 1.75x
 */
public class DiggingPowerAbility implements Ability {

    @Override
    public String id() {
        return "digging_power";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.digging_power");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.digging_power.desc");
    }

        private int countAtUnlock = 1;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return (float) (ModConfig.DIGGING_POWER_BASE.get()
            + ModConfig.DIGGING_POWER_PER_MILESTONE.get() * (count - countAtUnlock));
    }
}
