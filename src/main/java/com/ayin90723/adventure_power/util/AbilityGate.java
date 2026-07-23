package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * 能力门禁与觉醒倍率公共工具。
 * 统一各 handler 重复的"取进度->冒险者/觉醒->能力启用"三连门禁，
 * 以及"百分比能力 × 觉醒倍率 × cap"的计算。
 */
public final class AbilityGate {

    private AbilityGate() {}

    /**
     * 能力门禁三连：玩家持有冒险饰品(冒险者或觉醒) 且 指定能力已启用。
     * @return true 表示该能力对玩家生效
     */
    public static boolean isAbilityActive(Player player, String abilityId) {
        return AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> (p.isAdventurer() || p.isFullyUnlocked()) && p.isAbilityEnabled(abilityId))
            .orElse(false);
    }

    /**
     * 能力门禁三连 + 返回 progress 对象。
     * <p>
     * 门禁通过（冒险者/觉醒 + 能力启用）时返回 progress，否则 empty。
     * 供需要复用 progress 对象的 handler 使用，避免 {@link #isAbilityActive} + 再查一次
     * {@code getAdventureProgress} 的双重查询。
     *
     * @return 通过门禁的 progress；未通过或无进度数据时为 empty
     */
    public static Optional<IAdventureProgress> getActiveProgress(Player player, String abilityId) {
        return AdventureProgressCapability.getAdventureProgress(player)
            .filter(p -> (p.isAdventurer() || p.isFullyUnlocked()) && p.isAbilityEnabled(abilityId));
    }

    /**
     * 百分比能力觉醒倍率（value/100 形式，cap 0.95）。
     * 用于灵巧/伤害抗性等：value 是 0-100 的百分比，结果为 0-0.95 的比率。
     */
    public static float awakenedRatio(Ability ability, int milestones, boolean fullyUnlocked) {
        float v = ability.value(milestones) / 100.0f;
        if (fullyUnlocked) {
            v = Math.min(v * ModConfig.AWAKEN_MULTIPLIER.get().floatValue(), 0.95f);
        }
        return v;
    }

    /**
     * 百分比能力觉醒倍率（value 不除100，cap 由参数指定）。
     * 用于不动如山等：value 直接是属性百分比，cap 来自配置。
     */
    public static float awakenedPercent(Ability ability, int milestones, boolean fullyUnlocked, float cap) {
        float v = ability.value(milestones);
        if (fullyUnlocked) {
            v = Math.min(v * ModConfig.AWAKEN_MULTIPLIER.get().floatValue(), cap);
        }
        return v;
    }
}
