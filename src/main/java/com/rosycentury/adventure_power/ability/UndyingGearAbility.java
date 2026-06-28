package com.rosycentury.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 不朽装备 — 装备不会消耗耐久。
 * 解锁条件：5 里程碑
 * 无成长，解锁即完整。
 */
public class UndyingGearAbility implements Ability {

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

    @Override
    public int requiredMilestones() {
        return 5;
    }

    @Override
    public float value(int milestones) {
        return -1;
    }
}
