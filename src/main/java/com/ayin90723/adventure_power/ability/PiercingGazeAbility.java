package com.ayin90723.adventure_power.ability;


/**
 * 破敌之眼 — 攻击无视目标护甲和抗性。
 * 解锁条件：9 里程碑
 * 无成长，解锁即完整。
 */
public class PiercingGazeAbility extends AbstractAbility {

    public PiercingGazeAbility() {
        super(9);
    }

    @Override
    public String id() {
        return "piercing_gaze";
    }

}
