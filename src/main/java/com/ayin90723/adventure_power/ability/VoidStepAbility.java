package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 虚空踏步 — 跳跃高度提升（跳跃倍率）。
 * 解锁条件：2 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：1.0x → 1.24x
 */
public class VoidStepAbility implements Ability {

    @Override
    public String id() {
        return "void_step";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.void_step");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.void_step.desc");
    }

    @Override
    public int requiredMilestones() {
        return 2;
    }

    /**
     * 返回跳跃倍率。从配置读取 base + per_milestone × (milestones - required)。
     */
    @Override
    public float value(int milestones) {
        return (float)(ModConfig.VOID_STEP_BASE.get() + ModConfig.VOID_STEP_PER_MILESTONE.get() * (milestones - requiredMilestones()));
    }
}
