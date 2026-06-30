package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 嗜血 — 攻击造成伤害时回复自身生命值。
 * 解锁条件：6 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：5% → 13%
 */
public class LifestealAbility implements Ability {

    @Override
    public String id() {
        return "lifesteal";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.lifesteal");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.lifesteal.desc");
    }

        private int countAtUnlock = 6;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return ModConfig.LIFESTEAL_BASE.get()
            + ModConfig.LIFESTEAL_PER_MILESTONE.get() * (count - countAtUnlock);
    }
}
