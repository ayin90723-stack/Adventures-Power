package com.ayin90723.adventure_power.ability;


/**
 * 拒绝篡改 — 免疫外部强制修改生命值上限的恶意操作。
 * 解锁条件：10 里程碑
 * 无成长，解锁即完整。
 */
public class RejectManipAbility extends AbstractAbility {

    public RejectManipAbility() {
        super(10);
    }

    @Override
    public String id() {
        return "reject_manip";
    }

}
