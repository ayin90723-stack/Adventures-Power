package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 恩赐永驻 - 正面药水效果低于保底阈值时自动续期，Buff 永不自然耗尽。
 * 解锁条件：1 里程碑
 * 无成长（解锁即完整），续期阈值与写入时长由 buff_min_duration / buff_extend_amount 配置。
 */
public class PerpetualBlessingAbility extends AbstractAbility {

    public PerpetualBlessingAbility() {
        super(1);
    }

    @Override
    public String id() {
        return "perpetual_blessing";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.perpetual_blessing");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.perpetual_blessing.desc");
    }
}
