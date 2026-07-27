package com.ayin90723.adventure_power.ability;


/**
 * 旅者之力 — 主动技能，提供强大的爆发性增益。
 * 解锁条件：10 里程碑
 * 无成长，解锁即完整。
 */
public class ActiveSkillAbility extends AbstractAbility {

    public ActiveSkillAbility() {
        super(10);
    }

    @Override
    public String id() {
        return "active_skill";
    }

}