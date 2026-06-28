package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

public class PurifiedSoulAbility implements Ability {

    @Override
    public String id() {
        return "purified_soul";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.purified_soul");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.purified_soul.desc");
    }

    @Override
    public int requiredMilestones() {
        return 8;
    }

    @Override
    public float value(int milestones) {
        return -1;
    }
}
