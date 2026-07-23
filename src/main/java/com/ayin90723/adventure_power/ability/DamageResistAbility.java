package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 伤害抗性 — 减少所受伤害百分比。
 * 解锁条件：4 里程碑
 * 成长公式：base + per_milestone × (count - required)
 * 默认范围：10% → 40%
 */
public class DamageResistAbility extends AbstractAbility {

    public DamageResistAbility() {
        super(4);
    }

    @Override
    public String id() {
        return "damage_resist";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.damage_resist");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.damage_resist.desc");
    }

    /**
     * 返回伤害减免百分比。从配置读取 base + per_milestone × (count - required)，
     * 最低为 0（防止负值）。
     */
    @Override
    public float value(int count) {
        return Math.max(0, ModConfig.DAMAGE_RESIST_BASE.get() + ModConfig.DAMAGE_RESIST_PER_MILESTONE.get() * (count - countAtUnlock));
    }
}