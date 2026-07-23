package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

public class EnvImmunityAbility extends AbstractAbility {

    public EnvImmunityAbility() {
        super(6);
    }

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
}