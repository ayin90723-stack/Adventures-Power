package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 受击坚韧 — 受击叠层获得减伤。
 * 解锁条件：8 里程碑
 * 最大层数从配置读取，默认：8→5, 9→8, 10→12
 */
public class ResilienceAbility implements Ability {

    @Override
    public String id() {
        return "resilience";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.resilience");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.resilience.desc");
    }

    /**
     * 返回最大减伤层数。从配置读取三档值，按 countAtUnlock 偏移计算。
     * 默认 countAtUnlock=8 → 偏移0=8里程碑, 偏移1=9里程碑, 偏移2+=10里程碑。
     */

    private int countAtUnlock = 8;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        int offset = count - countAtUnlock;
        if (offset >= 2) return ModConfig.RESILIENCE_STACKS_10.get();
        if (offset == 1) return ModConfig.RESILIENCE_STACKS_9.get();
        return ModConfig.RESILIENCE_STACKS_8.get();
    }

    /**
     * 觉醒版本：在基础层数上额外增加层数（由配置决定，默认 6 层）。
     */
    public float value(int count, boolean awakened) {
        int base = (int) value(count);
        return awakened ? base + ModConfig.AWAKEN_RESILIENCE_BONUS_STACKS.get() : base;
    }
}
