package com.ayin90723.adventure_power.util;

/**
 * 能力 ID 常量集中管理。
 * <p>
 * 项目约定：{@code Ability.id()} 返回值必须与 {@code KNOWN_ABILITIES} key、
 * {@code AbilityRegistry} 注册 key、里程碑 JSON abilities 数组值、所有
 * {@code isAbilityEnabled} 调用字符串完全一致。集中为常量后，重命名/纠错可由
 * IDE 全局重命名，typo 从「运行时静默失效」降为「编译可查」。
 * <p>
 * 注意：伤害类型 ID（soul_strike / judgment / shadow_kill 的 damage_type）与
 * 能力 ID 同名但语义不同，不在此类中，保持 DamageUtil 中的字面量。
 */
public final class AbilityIds {

    private AbilityIds() {}

    public static final String AGILITY = "agility";
    public static final String DIGGING_POWER = "digging_power";
    public static final String VOID_STEP = "void_step";
    public static final String RAPID_RECOVERY = "rapid_recovery";
    public static final String SOUL_BIND = "soul_bind";
    public static final String KNOCKBACK_RESIST = "knockback_resist";
    public static final String SWIFT = "swift";
    public static final String ALL_SEEING = "all_seeing";
    public static final String EXTENDED_REACH = "extended_reach";
    public static final String MAGNET = "magnet";
    public static final String UNDYING_GEAR = "undying_gear";
    public static final String FORTUNE_FAVOR = "fortune_favor";
    public static final String XP_BOOST = "xp_boost";
    public static final String ENV_IMMUNITY = "env_immunity";
    public static final String LIFESTEAL = "lifesteal";
    public static final String DAMAGE_RESIST = "damage_resist";
    public static final String HEALING_BLOCK = "healing_block";
    public static final String VITALITY = "vitality";
    public static final String DEATH_DEFY = "death_defy";
    public static final String RESILIENCE = "resilience";
    public static final String PURIFIED_SOUL = "purified_soul";
    public static final String LOOT_ALL = "loot_all";
    public static final String SOAR = "soar";
    public static final String SOUL_QUENCH = "soul_quench";
    public static final String PIERCING_GAZE = "piercing_gaze";
    public static final String PERPETUAL_BLESSING = "perpetual_blessing";
    public static final String SHADOW_KILL = "shadow_kill";
    public static final String TRUE_HEALTH = "true_health";
    public static final String REJECT_MANIP = "reject_manip";
    public static final String ACTIVE_SKILL = "active_skill";
}
