package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 鸿运当头 — 提升时运和抢夺等级。
 * 解锁条件：5 里程碑
 * 成长公式：bonus = base + step × ((milestones - required) / 2)
 * 默认：里程碑5=+1, 7=+2, 9=+3
 */
public class FortuneFavorAbility implements Ability {

    @Override
    public String id() {
        return "fortune_favor";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.fortune_favor");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.fortune_favor.desc");
    }

    @Override
    public int requiredMilestones() {
        return 5;
    }

    @Override
    public float value(int milestones) {
        int steps = (milestones - requiredMilestones()) / 2;
        return ModConfig.FORTUNE_FAVOR_BONUS_BASE.get()
            + ModConfig.FORTUNE_FAVOR_BONUS_STEP.get() * steps;
    }
}
