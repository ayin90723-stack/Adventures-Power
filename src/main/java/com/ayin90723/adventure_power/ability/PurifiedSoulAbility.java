package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

public class PurifiedSoulAbility extends AbstractAbility {

    public PurifiedSoulAbility() {
        super(8);
    }

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
}