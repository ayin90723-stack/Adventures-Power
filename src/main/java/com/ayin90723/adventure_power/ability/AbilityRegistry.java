package com.ayin90723.adventure_power.ability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 能力注册表 — 18 种冒险能力按里程碑顺序排列。
 */
public class AbilityRegistry {

    public static final Map<String, Ability> ALL = new LinkedHashMap<>();

    static {
        register(new AgilityAbility());
        register(new DiggingPowerAbility());
        register(new PerpetualBlessingAbility());
        register(new VoidStepAbility());
        register(new RapidRecoveryAbility());
        register(new SoulBindAbility());
        register(new KnockbackResistAbility());
        register(new DamageResistAbility());
        register(new ExtendedReachAbility());
        register(new UndyingGearAbility());
        register(new FortuneFavorAbility());
        register(new EnvImmunityAbility());
        register(new LifestealAbility());
        register(new HealingBlockAbility());
        register(new VitalityAbility());
        register(new ResilienceAbility());
        register(new PurifiedSoulAbility());
        register(new SoarAbility());
        register(new SoulQuenchAbility());
        register(new PiercingGazeAbility());
        register(new DeathDefyAbility());
        register(new ShadowKillAbility());
        register(new TrueHealthAbility());
        register(new RejectManipAbility());
        register(new ActiveSkillAbility());
    }

    private static void register(Ability ability) {
        ALL.put(ability.id(), ability);
    }

    public static Ability get(String id) {
        return ALL.get(id);
    }

    /** 获取指定里程碑解锁的所有能力 */
    public static Set<Ability> getByMilestone(int milestone) {
        return ALL.values().stream()
            .filter(a -> a.requiredMilestones() == milestone)
            .collect(Collectors.toSet());
    }

    /** 获取 milestone 数足够的所有可用能力 */
    public static Set<Ability> getAvailable(int unlockedMilestones) {
        return ALL.values().stream()
            .filter(a -> a.requiredMilestones() <= unlockedMilestones)
            .collect(Collectors.toSet());
    }
}
