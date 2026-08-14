package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 冒险能力接口。
 * 所有能力实现此接口。countAtUnlock 由 MilestoneRegistry 在加载 JSON 后设置。
 * <p>
 * 注意：能力效果统一走 handler 事件层（PlayerTickDispatcher 分发 onTick），
 * 接口不再提供生命周期回调——避免「覆写了但无人调用」的静默失效陷阱。
 */
public interface Ability {

    /** 唯一标识，如 "agility" */
    String id();

    /** 显示名称 */
    Component name();

    /** 描述文本 */
    Component description();

    /**
     * 当前数值。count 必须传 {@code AbilityGate.effectiveCount(progress, id)} 的平移结果
     * （核心设计约定 12，禁止直接传 getUnlockedMilestoneCount）——无成长的能力可返回 -1
     * 表示"已解锁即完整"。成长公式内部以 countAtUnlock 为解锁时刻基准。
     */
    float value(int count);

    /**
     * MilestoneRegistry 加载 JSON 后调用，设置该能力解锁所需的里程碑数。
     * 默认实现为空。
     */
    default void setCountAtUnlock(int n) {}

    /** 当前 countAtUnlock（指令后门解锁的被禁用能力做 count 平移时使用） */
    default int getCountAtUnlock() { return 0; }
}
