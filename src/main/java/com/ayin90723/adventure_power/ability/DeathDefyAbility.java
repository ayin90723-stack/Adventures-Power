package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 死亡抗拒 — 致命伤害触发无敌并回血。
 * 解锁条件：9 里程碑
 * 无敌/冷却时间从配置读取。
 */
public class DeathDefyAbility implements Ability {

    @Override
    public String id() {
        return "death_defy";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.death_defy");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.death_defy.desc");
    }

    /**
     * value 返回无敌 tick 数，从配置读取。
     */

    private int countAtUnlock = 9;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return ModConfig.DEATH_DEFY_INVUL_DURATION.get();
    }
}
