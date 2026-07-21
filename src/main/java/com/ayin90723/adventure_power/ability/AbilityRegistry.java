package com.ayin90723.adventure_power.ability;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 能力注册表 — 26 种冒险能力，按面板显示顺序排列。
 * countAtUnlock 映射由 MilestoneRegistry 在加载 JSON 后填充。
 */
public class AbilityRegistry {

    public static final Map<String, Ability> ALL = new LinkedHashMap<>();

    /** 能力 ID → 动态 countAtUnlock 覆盖值（由 MilestoneRegistry 加载后设置） */
    private static final Map<String, Integer> COUNT_AT_UNLOCK_OVERRIDES = new HashMap<>();

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
        register(new LootAllAbility());
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

    /**
     * MilestoneRegistry 加载 JSON 后调用，为指定能力设置动态 countAtUnlock。
     * 同时更新 Ability 实例内部的 countAtUnlock 字段。
     */
    public static void setCountAtUnlock(String id, int count) {
        COUNT_AT_UNLOCK_OVERRIDES.put(id, count);
        Ability ability = ALL.get(id);
        if (ability != null) {
            ability.setCountAtUnlock(count);
        }
    }

    /**
     * 查询某能力的 countAtUnlock（优先用覆盖值）。
     * 正常情况下 MilestoneRegistry 加载后一定被设置，未设置时返回 0 作为安全回退。
     */
    public static int getCountAtUnlock(String id) {
        Integer override = COUNT_AT_UNLOCK_OVERRIDES.get(id);
        return override != null ? override : 0;
    }

    /** 清除所有动态设置（用于数据包重载前重置） */
    public static void clearCountAtUnlockOverrides() {
        COUNT_AT_UNLOCK_OVERRIDES.clear();
    }
}
