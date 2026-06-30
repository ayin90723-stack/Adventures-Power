package com.ayin90723.adventure_power.ability;

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

        private int countAtUnlock = 6;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return -1;
    }
}
