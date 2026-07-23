package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 真实血量 — 生命值受独立备份系统保护，无法被外部篡改。
 * 解锁条件：10 里程碑
 * 无成长，解锁即完整。
 */
public class TrueHealthAbility extends AbstractAbility {

    public TrueHealthAbility() {
        super(10);
    }

    @Override
    public String id() {
        return "true_health";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.true_health");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.true_health.desc");
    }
}