package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 禁疗之触 — 攻击禁止目标回血。
 * 解锁条件：7 里程碑
 * 成长公式：base + per_milestone × (count - required)（秒）
 * 默认范围：3s → 6s
 */
public class HealingBlockAbility implements Ability {

    @Override
    public String id() {
        return "healing_block";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.healing_block");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.healing_block.desc");
    }

    /**
     * 返回禁疗持续时间（秒）。从配置读取 base + per_milestone × (count - required)。
     */

    private int countAtUnlock = 7;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return ModConfig.HEALING_BLOCK_BASE.get() + ModConfig.HEALING_BLOCK_PER_MILESTONE.get() * (count - countAtUnlock);
    }
}
