package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 坚韧之躯 — 提高最大生命值。
 * 解锁条件：7 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：+4 → +12（+2心 → +6心）
 */
public class VitalityAbility implements Ability {

    @Override
    public String id() {
        return "vitality";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.vitality");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.vitality.desc");
    }

        private int countAtUnlock = 7;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return (float) (ModConfig.VITALITY_BASE.get()
            + ModConfig.VITALITY_PER_MILESTONE.get() * (count - countAtUnlock));
    }
}
