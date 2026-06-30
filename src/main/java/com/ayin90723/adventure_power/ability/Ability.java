package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 冒险能力接口。
 * 所有能力实现此接口。countAtUnlock 由 MilestoneRegistry 在加载 JSON 后设置。
 */
public interface Ability {

    /** 唯一标识，如 "agility" */
    String id();

    /** 显示名称 */
    Component name();

    /** 描述文本 */
    Component description();

    /**
     * 当前数值。count = 玩家已解锁的里程碑总数。
     * 无成长的能力可返回 -1 表示"已解锁即完整"。
     */
    float value(int count);

    /**
     * MilestoneRegistry 加载 JSON 后调用，设置该能力解锁所需的里程碑数。
     * 默认实现为空。
     */
    default void setCountAtUnlock(int n) {}

    /** 启用时回调（服务端） */
    default void onEnable(Player player) {}

    /** 禁用时回调（服务端） */
    default void onDisable(Player player) {}

    /** 每 tick 回调（服务端），默认空 */
    default void onTick(Player player) {}
}
