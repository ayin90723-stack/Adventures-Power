package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 不朽装备 — 装备不会消耗耐久。
 * 解锁条件：5 里程碑
 * 无成长，解锁即完整。
 */
public class UndyingGearAbility extends AbstractAbility {

    public UndyingGearAbility() {
        super(5);
    }

    @Override
    public String id() {
        return "undying_gear";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.undying_gear");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.undying_gear.desc");
    }
}