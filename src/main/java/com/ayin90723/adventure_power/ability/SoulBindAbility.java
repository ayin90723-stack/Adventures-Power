package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 灵魂绑定 — 死亡不掉落物品。
 * 解锁条件：3 里程碑
 * 无成长，解锁即完整。
 */
public class SoulBindAbility extends AbstractAbility {

    public SoulBindAbility() {
        super(3);
    }

    @Override
    public String id() {
        return "soul_bind";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.soul_bind");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.soul_bind.desc");
    }
}