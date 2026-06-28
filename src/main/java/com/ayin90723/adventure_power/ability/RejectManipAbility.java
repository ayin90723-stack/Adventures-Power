package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 拒绝篡改 — 免疫外部强制修改生命值上限的恶意操作。
 * 解锁条件：10 里程碑
 * 无成长，解锁即完整。
 */
public class RejectManipAbility implements Ability {

    @Override
    public String id() {
        return "reject_manip";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.reject_manip");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.reject_manip.desc");
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
