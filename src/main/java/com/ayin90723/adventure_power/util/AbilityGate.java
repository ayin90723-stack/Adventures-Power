package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
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
            .filter(p -> isActive(p, abilityId));
    }

    /**
     * 门禁断言（已有 progress 对象时）：冒险者/觉醒 且 指定能力已启用。
     * 收敛各 handler 重复的「{@code if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;}
     * + {@code if (!progress.isAbilityEnabled(x)) return;}」双行样板。
     */
    public static boolean isActive(IAdventureProgress progress, String abilityId) {
        return (progress.isAdventurer() || progress.isFullyUnlocked())
            && progress.isAbilityEnabled(abilityId);
    }

    /**
     * 有效里程碑数：指令后门解锁的被禁用能力按解锁时刻快照平移，其余能力原样返回。
     * <p>
     * 平移公式 {@code count' = count - grantedAt + countAtUnlock}：
     * 解锁瞬间（count == grantedAt）数值 = 基础值 base，之后每解锁一个里程碑
     * {@code count - grantedAt} +1 → 正常吃成长，不受原归属里程碑位置限制。
     * 对线性/阶梯/档位公式均成立（档位判断等价于「解锁后第 N 个里程碑」）。
     * 所有能力数值调用点必须经此方法取 count（或调用 {@link #value}），
     * 否则指令解锁的能力会按错误基准计算。
     */
    public static int effectiveCount(IAdventureProgress progress, String abilityId) {
        int count = progress.getUnlockedMilestoneCount();
        if (progress.isCommandGranted(abilityId)) {
            Ability ability = AbilityRegistry.get(abilityId);
            int global = ability != null ? ability.getCountAtUnlock() : 0;
            int grantedAt = progress.getCommandGrantedAtCount(abilityId);
            // 下限防护：/reload 删除里程碑后 count 可能小于 grantedAt，平移可为负，
            // 消费方（ActiveSkillHandler 等）只防护 ==0，负值会削掉技能伤害
            return Math.max(0, count - grantedAt + global);
        }
        return count;
    }

    /**
     * 取能力当前数值（能力未注册返回 empty）。
     * 与「{@code Ability ability = AbilityRegistry.get(id); if (ability == null) return;}」样板
     * 完全等价——empty 即「不处理」。
     */
    public static Optional<Float> value(IAdventureProgress progress, String abilityId) {
        Ability ability = AbilityRegistry.get(abilityId);
        if (ability == null) return Optional.empty();
        return Optional.of(ability.value(effectiveCount(progress, abilityId)));
    }

    /**
     * 百分比能力觉醒倍率（value/100 形式，cap 由配置 `awaken_percent_cap` 控制，默认 0.95）。
     * 用于灵巧/伤害抗性等：value 是 0-100 的百分比，结果为 0-cap 的比率。
     */
    public static float awakenedRatio(Ability ability, int milestones, boolean fullyUnlocked) {
        return applyAwakenedMultiplier(ability.value(milestones) / 100.0f, fullyUnlocked,
            ModConfig.AWAKEN_PERCENT_CAP.get().floatValue());
    }

    /**
     * 百分比能力觉醒倍率（value 不除100，cap 由参数指定）。
     * 用于不动如山等：value 直接是属性百分比，cap 来自配置。
     */
    public static float awakenedPercent(Ability ability, int milestones, boolean fullyUnlocked, float cap) {
        return applyAwakenedMultiplier(ability.value(milestones), fullyUnlocked, cap);
    }

    /** 觉醒倍率核心：fullyUnlocked 时 × AWAKEN_MULTIPLIER 并钳 cap，否则原值。
     *  awakenedRatio / awakenedPercent 共用，避免两处重复 */
    private static float applyAwakenedMultiplier(float v, boolean fullyUnlocked, float cap) {
        if (fullyUnlocked) {
            v = Math.min(v * ModConfig.AWAKEN_MULTIPLIER.get().floatValue(), cap);
        }
        return v;
    }
}
