package com.ayin90723.adventure_power.ability;

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

        private int countAtUnlock = 9;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return -1;
    }
}
