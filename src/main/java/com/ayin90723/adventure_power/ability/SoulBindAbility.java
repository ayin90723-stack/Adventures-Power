package com.ayin90723.adventure_power.ability;


/**
 * 灵魂绑定 — 死亡时保留正面效果（觉醒额外保留经验）。
 * 解锁条件：3 里程碑（初次交易）
 * 无成长，解锁即完整。
 */
public class SoulBindAbility extends AbstractAbility {

    public SoulBindAbility() {
        super(3);
    }

    @Override
    public String id() {
        return "soul_bind";
    }

}
