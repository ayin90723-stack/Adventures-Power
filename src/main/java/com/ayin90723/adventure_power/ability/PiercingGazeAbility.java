package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 破敌之眼 — 攻击无视目标护甲和抗性。
 * 解锁条件：9 里程碑
 * 无成长，解锁即完整。
 */
public class PiercingGazeAbility implements Ability {

    @Override
    public String id() {
        return "piercing_gaze";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.piercing_gaze");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.piercing_gaze.desc");
    }

        private int countAtUnlock = 9;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return -1;
    }
}
