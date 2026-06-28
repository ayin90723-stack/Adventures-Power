package com.rosycentury.adventure_power.ability;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 冒险能力接口。
 * 所有能力（灵巧、恩赐永驻、虚空踏步……18 种）实现此接口。
 */
public interface Ability {

    /** 唯一标识，如 "agility" */
    String id();

    /** 显示名称 */
    Component name();

    /** 描述文本 */
    Component description();

    /** 解锁所需里程碑数（0-9 映射到 Milestone.ordinal()） */
    int requiredMilestones();

    /**
     * 当前数值（随里程碑动态计算）。
     * 无成长的能力可返回 -1 表示"已解锁即完整"。
     */
    float value(int milestones);

    /** 启用时回调（服务端） */
    default void onEnable(Player player) {}

    /** 禁用时回调（服务端） */
    default void onDisable(Player player) {}

    /** 每 tick 回调（服务端），默认空 */
    default void onTick(Player player) {}
}
