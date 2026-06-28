package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 影杀 — 影子血量系统，归零时饱和式斩杀。
 * 解锁条件：10 里程碑
 * 数值从配置读取，默认：固伤 4，HP 比例 4%
 */
public class ShadowKillAbility implements Ability {

    @Override
    public String id() {
        return "shadow_kill";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.shadow_kill");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.shadow_kill.desc");
    }

    @Override
    public int requiredMilestones() {
        return 10;
    }

    /**
     * value 返回固伤值（另一值 hpRatio 由 handler 读取）。
     */
    @Override
    public float value(int milestones) {
        return flatDamage();
    }

    /** 每次攻击削减影子血量的固定值，从配置读取 */
    public int flatDamage() {
        return ModConfig.SHADOW_KILL_FLAT_DAMAGE.get();
    }

    /** 每次攻击额外削减目标最大生命值的比例，从配置读取 */
    public float hpRatio() {
        return (float)(double)ModConfig.SHADOW_KILL_HP_RATIO.get();
    }
}
