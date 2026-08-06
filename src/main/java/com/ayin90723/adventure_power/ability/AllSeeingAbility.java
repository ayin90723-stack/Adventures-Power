package com.ayin90723.adventure_power.ability;

/**
 * 全视之眼 - 夜视 + 去雾（下界/末地/水下雾消除）。
 * 解锁条件：4 里程碑（初探地底）
 * 无成长数值，常驻效果。
 * 觉醒：威胁雷达（AllSeeingHandler + AllSeeingRadarOverlay，屏幕角落显示附近敌对生物方位）
 */
public class AllSeeingAbility extends AbstractAbility {

    public AllSeeingAbility() {
        super(4);
    }

    @Override
    public String id() {
        return "all_seeing";
    }
}
