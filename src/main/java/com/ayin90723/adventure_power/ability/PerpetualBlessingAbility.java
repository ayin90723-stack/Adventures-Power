package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 恩赐永驻 — 药水效果持续时间延长。
 * 解锁条件：1 里程碑
 * 成长公式：base + step × ((count - required) / 2)（每 2 里程碑一跳）
 * 默认范围：400 tick (20s) → 1200 tick (60s)
 */
public class PerpetualBlessingAbility implements Ability {

    @Override
    public String id() {
        return "perpetual_blessing";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.perpetual_blessing");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.perpetual_blessing.desc");
    }

    /**
     * 返回延长的 tick 数。从配置读取 base + step × ((count - required) / 2)。
     */

    private int countAtUnlock = 1;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return ModConfig.PERPETUAL_BLESSING_BASE.get() + ModConfig.PERPETUAL_BLESSING_STEP.get() * ((count - countAtUnlock) / 2);
    }
}
