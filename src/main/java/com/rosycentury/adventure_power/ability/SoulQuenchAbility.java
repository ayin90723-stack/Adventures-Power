package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 淬魂之力 — 真实百分比伤害，绕过护甲/抗性。
 * 解锁条件：9 里程碑
 * 固定伤害和百分比从配置读取，默认：9→2+1%, 10→4+2%
 */
public class SoulQuenchAbility implements Ability {

    @Override
    public String id() {
        return "soul_quench";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.soul_quench");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.soul_quench.desc");
    }

    @Override
    public int requiredMilestones() {
        return 9;
    }

    /** value 无意义，实际数值由 flatDamage/hpRatio 提供 */
    @Override
    public float value(int milestones) {
        return -1;
    }

    /** 固定伤害，从配置读取 */
    public int flatDamage(int milestones) {
        return milestones >= 10 ? ModConfig.SOUL_QUENCH_FLAT_DAMAGE_10.get() : ModConfig.SOUL_QUENCH_FLAT_DAMAGE_9.get();
    }

    /** 生命百分比伤害，从配置读取 */
    public float hpRatio(int milestones) {
        return (float)(double)(milestones >= 10 ? ModConfig.SOUL_QUENCH_HP_RATIO_10.get() : ModConfig.SOUL_QUENCH_HP_RATIO_9.get());
    }
}
