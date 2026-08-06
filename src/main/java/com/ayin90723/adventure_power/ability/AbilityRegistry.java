package com.ayin90723.adventure_power.ability;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 能力注册表 — 30 种冒险能力，按面板显示顺序排列。
 * countAtUnlock 由 MilestoneRegistry 在加载 JSON 后写入各 Ability 实例（驱动成长公式）。
 */
public class AbilityRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbilityRegistry.class);

    public static final Map<String, Ability> ALL = new LinkedHashMap<>();

    static {
        register(new AgilityAbility());
        register(new DiggingPowerAbility());
        register(new VoidStepAbility());
        register(new RapidRecoveryAbility());
        register(new SoulBindAbility());
        register(new KnockbackResistAbility());
        register(new SwiftAbility());
        register(new AllSeeingAbility());
        register(new ExtendedReachAbility());
        register(new MagnetAbility());
        register(new UndyingGearAbility());
        register(new FortuneFavorAbility());
        register(new XpBoostAbility());
        register(new EnvImmunityAbility());
        register(new LifestealAbility());
        register(new DamageResistAbility());
        register(new HealingBlockAbility());
        register(new VitalityAbility());
        register(new DeathDefyAbility());
        register(new ResilienceAbility());
        register(new PurifiedSoulAbility());
        register(new LootAllAbility());
        register(new SoarAbility());
        register(new SoulQuenchAbility());
        register(new PiercingGazeAbility());
        register(new PerpetualBlessingAbility());
        register(new ShadowKillAbility());
        register(new TrueHealthAbility());
        register(new RejectManipAbility());
        register(new ActiveSkillAbility());
    }

    private static void register(Ability ability) {
        Ability prev = ALL.put(ability.id(), ability);
        if (prev != null) {
            LOGGER.warn("[AbilityRegistry] 能力 ID 重复注册：{}（后者覆盖前者，请检查注册表）", ability.id());
        }
    }

    public static Ability get(String id) {
        return ALL.get(id);
    }

    /**
     * MilestoneRegistry 加载 JSON 后调用，为指定能力设置 countAtUnlock。
     * 该值写入 Ability 实例，驱动成长公式 value(count) = base + perMilestone × (count - countAtUnlock)。
     */
    public static void setCountAtUnlock(String id, int count) {
        Ability ability = ALL.get(id);
        if (ability != null) {
            ability.setCountAtUnlock(count);
        }
    }

    /**
     * 重置所有能力实例的 countAtUnlock（数据包重载前清空，之后由新 JSON 重新填充）。
     * 注意：被新 JSON 移除的能力其 countAtUnlock 回 0，避免残留旧值。
     */
    public static void clearCountAtUnlockOverrides() {
        for (Ability ability : ALL.values()) {
            ability.setCountAtUnlock(0);
        }
    }
}
