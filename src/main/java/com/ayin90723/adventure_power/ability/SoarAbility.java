package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

public class SoarAbility extends AbstractAbility {

    public SoarAbility() {
        super(9);
    }

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
}