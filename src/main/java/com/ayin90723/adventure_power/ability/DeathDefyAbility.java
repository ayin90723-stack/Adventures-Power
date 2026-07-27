package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;

/**
 * 死亡抗拒 — 致命伤害触发无敌并回血。
 * 解锁条件：9 里程碑
 * 无敌/冷却时间从配置读取。
 */
public class DeathDefyAbility extends AbstractAbility {

    public DeathDefyAbility() {
        super(9);
    }

    @Override
    public String id() {
        return "death_defy";
    }

    /**
     * value 返回无敌 tick 数，从配置读取。
     */
    @Override
    public float value(int count) {
        return ModConfig.DEATH_DEFY_INVUL_DURATION.get();
    }
}