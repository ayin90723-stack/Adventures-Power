package com.rosycentury.adventure_power.ability;

import net.minecraft.network.chat.Component;

public class SoarAbility implements Ability {

    @Override
    public String id() {
        return "soar";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.soar");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.soar.desc");
    }

    @Override
    public int requiredMilestones() {
        return 9;
    }

    @Override
    public float value(int milestones) {
        return -1;
    }
}
