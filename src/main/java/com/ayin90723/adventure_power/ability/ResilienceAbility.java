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

    @Override
    public int requiredMilestones() {
        return 8;
    }

    /**
     * 返回最大减伤层数。从配置读取各里程碑对应的值。
     */
    @Override
    public float value(int milestones) {
        return switch (milestones) {
            case 8 -> ModConfig.RESILIENCE_STACKS_8.get();
            case 9 -> ModConfig.RESILIENCE_STACKS_9.get();
            case 10 -> ModConfig.RESILIENCE_STACKS_10.get();
            default -> 0;
        };
    }

    /**
     * 觉醒版本：在基础层数上额外 +6 层。
     */
    public float value(int milestones, boolean awakened) {
        int base = switch (milestones) {
            case 8 -> ModConfig.RESILIENCE_STACKS_8.get();
            case 9 -> ModConfig.RESILIENCE_STACKS_9.get();
            case 10 -> ModConfig.RESILIENCE_STACKS_10.get();
            default -> 0;
        };
        return awakened ? base + 6 : base;
    }
}
