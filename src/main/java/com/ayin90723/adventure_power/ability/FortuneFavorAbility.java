package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 鸿运当头 — 提升时运和抢夺等级。
 * 解锁条件：5 里程碑
 * 成长公式：bonus = base + step × ((count - required) / 2)
 * 默认：里程碑5=+1, 7=+2, 9=+3
 */
public class FortuneFavorAbility extends StepGrowthAbility {

    public FortuneFavorAbility() {
        super(5);
    }

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
    protected float base() {
        return ModConfig.FORTUNE_FAVOR_BONUS_BASE.get();
    }

    @Override
    protected float step() {
        return ModConfig.FORTUNE_FAVOR_BONUS_STEP.get();
    }
}