package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 休养生息 — 脱战后自动获得生命恢复效果。
 * 解锁条件：2 里程碑
 * 成长公式：amplifier = base + step × ((count - required) / 2)
 * 默认：里程碑2=再生I(0), 4=再生II(1), 10=再生V(4)
 */
public class RapidRecoveryAbility implements Ability {

    @Override
    public String id() {
        return "rapid_recovery";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.rapid_recovery");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.rapid_recovery.desc");
    }

    /**
     * 返回再生效果 amplifier（0=再生I, 4=再生V）。
     * 每 2 个里程碑升一级。
     */

    private int countAtUnlock = 2;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        int steps = (count - countAtUnlock) / 2;
        return ModConfig.RAPID_RECOVERY_AMPLIFIER_BASE.get()
            + ModConfig.RAPID_RECOVERY_AMPLIFIER_STEP.get() * steps;
    }
}
