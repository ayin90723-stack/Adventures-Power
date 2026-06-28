package com.rosycentury.adventure_power.ability;

import net.minecraft.network.chat.Component;

public class EnvImmunityAbility implements Ability {

    @Override
    public String id() {
        return "env_immunity";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.env_immunity");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.env_immunity.desc");
    }

    @Override
    public int requiredMilestones() {
        return 6;
    }

    @Override
    public float value(int milestones) {
        return -1;
    }
}
