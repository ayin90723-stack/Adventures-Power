package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 淬魂之力 — 真实百分比伤害，绕过护甲/抗性。
 * 解锁条件：9 里程碑
 * 固定伤害和百分比从配置读取，默认：9→2+1%, 10→4+2%
 */
public class SoulQuenchAbility extends AbstractAbility {

    public SoulQuenchAbility() {
        super(9);
    }

    @Override
    public String id() {
        return "soul_quench";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.soul_quench");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.soul_quench.desc");
    }

    /** 固定伤害，从配置读取。countAtUnlock+1 起为第二档。 */
    public int flatDamage(int count) {
        if (count >= countAtUnlock + 1) return ModConfig.SOUL_QUENCH_FLAT_DAMAGE_10.get();
        return ModConfig.SOUL_QUENCH_FLAT_DAMAGE_9.get();
    }

    /** 生命百分比伤害，从配置读取。countAtUnlock+1 起为第二档。 */
    public float hpRatio(int count) {
        if (count >= countAtUnlock + 1) return ModConfig.SOUL_QUENCH_HP_RATIO_10.get().floatValue();
        return ModConfig.SOUL_QUENCH_HP_RATIO_9.get().floatValue();
    }
}