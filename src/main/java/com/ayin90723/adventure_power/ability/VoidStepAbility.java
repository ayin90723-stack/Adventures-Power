package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 虚空踏步 - 空中多段跳。
 * 解锁条件：2 里程碑
 * 数值：固定跳跃力度倍率（{@link ModConfig#VOID_STEP_POWER}），无里程碑成长。
 * 觉醒后由 {@link ModConfig#AWAKEN_VOID_STEP_JUMPS} 控制三段跳。
 */
public class VoidStepAbility implements Ability {

    @Override
    public String id() {
        return "void_step";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.void_step");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.void_step.desc");
    }

    /**
     * 返回空中跳跃力度倍率（固定值，由 {@code VOID_STEP_POWER} 配置）。
     */
    @Override
    public float value(int count) {
        return ModConfig.VOID_STEP_POWER.get().floatValue();
    }
}
