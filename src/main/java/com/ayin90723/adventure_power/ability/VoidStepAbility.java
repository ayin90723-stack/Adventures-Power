package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;

/**
 * 虚空踏步 - 空中二段跳。
 * 解锁条件：2 里程碑
 * 数值：固定跳跃力度倍率（{@link ModConfig#VOID_STEP_POWER}），无里程碑成长。
 * 觉醒后获得「御风」：疾跑时二段跳朝视角方向水平冲刺（{@link ModConfig#AWAKEN_VOID_STEP_DASH}），松开疾跑为纯垂直跳。
 */
public class VoidStepAbility extends AbstractAbility {

    public VoidStepAbility() {
        super(2);
    }

    @Override
    public String id() {
        return "void_step";
    }

    /**
     * 返回空中跳跃力度倍率（固定值，由 {@code VOID_STEP_POWER} 配置）。
     */
    @Override
    public float value(int count) {
        return ModConfig.VOID_STEP_POWER.get().floatValue();
    }
}
