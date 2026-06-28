package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 旅者之力 — 主动技能，提供强大的爆发性增益。
 * 解锁条件：10 里程碑
 * 无成长，解锁即完整。
 */
public class ActiveSkillAbility implements Ability {

    @Override
    public String id() {
        return "active_skill";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.active_skill");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.active_skill.desc");
    }

    @Override
    public int requiredMilestones() {
        return 10;
    }

    @Override
    public float value(int milestones) {
        return -1;
    }
}
