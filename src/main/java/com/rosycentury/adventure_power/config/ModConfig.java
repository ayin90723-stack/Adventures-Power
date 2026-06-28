package com.rosycentury.adventure_power.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.*;

public class ModConfig {
    public static final Builder BUILDER = new Builder();
    public static final ForgeConfigSpec SPEC;

    // === 死亡抗拒 ===
    public static final IntValue DEATH_DEFY_INVUL_DURATION;
    public static final IntValue DEATH_DEFY_COOLDOWN_DURATION;

    // === 受击坚韧 ===
    public static final IntValue RESILIENCE_RESET_TICKS;

    // === 恩赐永驻 ===
    public static final IntValue BUFF_MIN_DURATION;
    public static final IntValue BUFF_EXTEND_AMOUNT;

    // === 主动技能 ===
    public static final DoubleValue ACTIVE_SKILL_JUDGMENT_BASE_DAMAGE;
    public static final DoubleValue ACTIVE_SKILL_JUDGMENT_HP_RATIO;
    public static final DoubleValue ACTIVE_SKILL_JUDGMENT_RADIUS;
    public static final IntValue ACTIVE_SKILL_JUDGMENT_COOLDOWN;
    public static final IntValue ACTIVE_SKILL_SANCTUARY_DURATION;
    public static final IntValue ACTIVE_SKILL_SANCTUARY_COOLDOWN;
    public static final IntValue ACTIVE_SKILL_GCD;

    // === 调试 ===
    public static final BooleanValue TRUE_HEALTH_DEBUG_LOG;

    // ==================== 能力数值 ====================

    // --- 灵巧 ---
    public static final IntValue AGILITY_BASE;
    public static final IntValue AGILITY_PER_MILESTONE;

    // --- 伤害抗性 ---
    public static final IntValue DAMAGE_RESIST_BASE;
    public static final IntValue DAMAGE_RESIST_PER_MILESTONE;

    // --- 禁疗之触 ---
    public static final IntValue HEALING_BLOCK_BASE;
    public static final IntValue HEALING_BLOCK_PER_MILESTONE;

    // --- 虚空踏步 ---
    public static final DoubleValue VOID_STEP_BASE;
    public static final DoubleValue VOID_STEP_PER_MILESTONE;

    // --- 恩赐永驻 ---
    public static final IntValue PERPETUAL_BLESSING_BASE;
    public static final IntValue PERPETUAL_BLESSING_STEP;

    // --- 受击坚韧 ---
    public static final IntValue RESILIENCE_STACKS_8;
    public static final IntValue RESILIENCE_STACKS_9;
    public static final IntValue RESILIENCE_STACKS_10;

    // --- 淬魂之力 ---
    public static final IntValue SOUL_QUENCH_FLAT_DAMAGE_9;
    public static final IntValue SOUL_QUENCH_FLAT_DAMAGE_10;
    public static final DoubleValue SOUL_QUENCH_HP_RATIO_9;
    public static final DoubleValue SOUL_QUENCH_HP_RATIO_10;

    // --- 影杀 ---
    public static final IntValue SHADOW_KILL_FLAT_DAMAGE;
    public static final DoubleValue SHADOW_KILL_HP_RATIO;

    // --- 休养生息 ---
    public static final IntValue RAPID_RECOVERY_AMPLIFIER_BASE;
    public static final IntValue RAPID_RECOVERY_AMPLIFIER_STEP;
    public static final IntValue RAPID_RECOVERY_DELAY_TICKS;

    // --- 不动如山 ---
    public static final IntValue KNOCKBACK_RESIST_BASE;
    public static final IntValue KNOCKBACK_RESIST_PER_MILESTONE;

    // --- 嗜血 ---
    public static final IntValue LIFESTEAL_BASE;
    public static final IntValue LIFESTEAL_PER_MILESTONE;
    public static final DoubleValue LIFESTEAL_CAP_RATIO;

    // --- 大地之力 ---
    public static final DoubleValue DIGGING_POWER_BASE;
    public static final DoubleValue DIGGING_POWER_PER_MILESTONE;

    // --- 无形之手 ---
    public static final DoubleValue EXTENDED_REACH_BASE;
    public static final DoubleValue EXTENDED_REACH_PER_MILESTONE;

    // --- 鸿运当头 ---
    public static final IntValue FORTUNE_FAVOR_BONUS_BASE;
    public static final IntValue FORTUNE_FAVOR_BONUS_STEP;

    // --- 坚韧之躯 ---
    public static final DoubleValue VITALITY_BASE;
    public static final DoubleValue VITALITY_PER_MILESTONE;

    // === 觉醒全局 ===
    public static final DoubleValue AWAKEN_MULTIPLIER;

    // === 觉醒 — 虚空踏步 ===
    public static final IntValue AWAKEN_VOID_STEP_JUMPS;

    // === 觉醒 — 翱翔 ===
    public static final DoubleValue AWAKEN_SOAR_SPEED;

    // === 觉醒 — 淬魂之力 ===
    public static final DoubleValue AWAKEN_SOUL_QUENCH_EXECUTE_THRESHOLD;

    // === 觉醒 — 影杀 ===
    public static final DoubleValue AWAKEN_SHADOW_KILL_AOE_RADIUS;
    public static final DoubleValue AWAKEN_SHADOW_KILL_AOE_RATIO;
    public static final IntValue AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS;

    // === 觉醒 — 嗜血 ===
    public static final DoubleValue AWAKEN_LIFESTEAL_SHIELD_CAP;
    public static final IntValue AWAKEN_LIFESTEAL_SHIELD_DURATION;

    // === 觉醒 — 净魂 ===
    public static final IntValue AWAKEN_PURIFIED_SOUL_RADIUS;

    // === 觉醒 — 旅者之力 ===
    public static final DoubleValue AWAKEN_JUDGMENT_RANGE_MULT;
    public static final DoubleValue AWAKEN_SANCTUARY_SPEED;

    // === 觉醒 — 不朽装备 ===
    public static final DoubleValue AWAKEN_UNDYING_ARMOR_BONUS;
    public static final DoubleValue AWAKEN_UNDYING_WEAPON_BONUS;

    static {
        BUILDER.push("冒险能力配置");

        BUILDER.push("调试");
        TRUE_HEALTH_DEBUG_LOG = BUILDER.comment("真实血量调试日志")
            .define("true_health_debug_log", false);
        BUILDER.pop();

        BUILDER.push("死亡抗拒");
        DEATH_DEFY_INVUL_DURATION = BUILDER.comment("无敌持续时间（tick）")
            .defineInRange("death_defy_invul_duration", 1200, 20, 72000);
        DEATH_DEFY_COOLDOWN_DURATION = BUILDER.comment("冷却时间（tick）")
            .defineInRange("death_defy_cooldown_duration", 6000, 100, 720000);
        BUILDER.pop();

        BUILDER.push("受击坚韧");
        RESILIENCE_RESET_TICKS = BUILDER.comment("无受伤重置时间（tick）")
            .defineInRange("resilience_reset_ticks", 100, 20, 600);
        BUILDER.pop();

        BUILDER.push("恩赐永驻");
        BUFF_MIN_DURATION = BUILDER.comment("正面效果最低保底持续时间（tick）")
            .defineInRange("buff_min_duration", 400, 0, 1200);
        BUFF_EXTEND_AMOUNT = BUILDER.comment("低于保底值时额外延长的量（tick）")
            .defineInRange("buff_extend_amount", 0, 0, 600);
        BUILDER.pop();

        BUILDER.push("主动技能配置");
        ACTIVE_SKILL_JUDGMENT_BASE_DAMAGE = BUILDER.comment("旅者审判基础固定伤害")
            .defineInRange("active_skill_judgment_base_damage", 10.0, 0.0, 1000.0);
        ACTIVE_SKILL_JUDGMENT_HP_RATIO = BUILDER.comment("旅者审判每里程碑生命值百分比（1里程碑=3%，5里程碑=15%）")
            .defineInRange("active_skill_judgment_hp_ratio", 0.03, 0.0, 0.5);
        ACTIVE_SKILL_JUDGMENT_RADIUS = BUILDER.comment("旅者审判AOE范围（格）")
            .defineInRange("active_skill_judgment_radius", 6.0, 1.0, 32.0);
        ACTIVE_SKILL_JUDGMENT_COOLDOWN = BUILDER.comment("旅者审判冷却时间（单位：tick，默认600=30秒）")
            .defineInRange("active_skill_judgment_cooldown", 600, 20, 72000);
        ACTIVE_SKILL_SANCTUARY_DURATION = BUILDER.comment("旅者庇护无敌持续时间（单位：tick，默认100=5秒）")
            .defineInRange("active_skill_sanctuary_duration", 100, 20, 72000);
        ACTIVE_SKILL_SANCTUARY_COOLDOWN = BUILDER.comment("旅者庇护冷却时间（单位：tick，默认1800=90秒）")
            .defineInRange("active_skill_sanctuary_cooldown", 1800, 20, 720000);
        ACTIVE_SKILL_GCD = BUILDER.comment("主动技能全局冷却（单位：tick，默认10=0.5秒）")
            .defineInRange("active_skill_gcd", 10, 0, 100);
        BUILDER.pop();

        // ==================== 能力数值 ====================
        BUILDER.push("能力数值");

        BUILDER.push("灵巧");
        AGILITY_BASE = BUILDER.comment("基础闪避率（%），里程碑1时的值")
            .defineInRange("agility_base", 10, 0, 100);
        AGILITY_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的闪避率（%）")
            .defineInRange("agility_per_milestone", 7, 0, 50);
        BUILDER.pop();

        BUILDER.push("伤害抗性");
        DAMAGE_RESIST_BASE = BUILDER.comment("基础减伤率（%），里程碑4时的值")
            .defineInRange("damage_resist_base", 10, 0, 100);
        DAMAGE_RESIST_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的减伤率（%）")
            .defineInRange("damage_resist_per_milestone", 5, 0, 50);
        BUILDER.pop();

        BUILDER.push("禁疗之触");
        HEALING_BLOCK_BASE = BUILDER.comment("基础禁疗持续时间（秒），里程碑7时的值")
            .defineInRange("healing_block_base", 3, 1, 60);
        HEALING_BLOCK_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的禁疗时间（秒）")
            .defineInRange("healing_block_per_milestone", 1, 0, 30);
        BUILDER.pop();

        BUILDER.push("虚空踏步");
        VOID_STEP_BASE = BUILDER.comment("基础跳跃倍率，里程碑2时的值")
            .defineInRange("void_step_base", 1.0, 0.5, 5.0);
        VOID_STEP_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的跳跃倍率")
            .defineInRange("void_step_per_milestone", 0.03, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("恩赐永驻");
        PERPETUAL_BLESSING_BASE = BUILDER.comment("Buff延长基础值（tick），里程碑1时的值")
            .defineInRange("perpetual_blessing_base", 400, 0, 3600);
        PERPETUAL_BLESSING_STEP = BUILDER.comment("每2个里程碑增加的延长量（tick）")
            .defineInRange("perpetual_blessing_step", 200, 0, 1200);
        BUILDER.pop();

        BUILDER.push("受击坚韧");
        RESILIENCE_STACKS_8 = BUILDER.comment("里程碑8时的最大减伤层数")
            .defineInRange("resilience_stacks_8", 5, 0, 50);
        RESILIENCE_STACKS_9 = BUILDER.comment("里程碑9时的最大减伤层数")
            .defineInRange("resilience_stacks_9", 8, 0, 50);
        RESILIENCE_STACKS_10 = BUILDER.comment("里程碑10时的最大减伤层数")
            .defineInRange("resilience_stacks_10", 12, 0, 50);
        BUILDER.pop();

        BUILDER.push("淬魂之力");
        SOUL_QUENCH_FLAT_DAMAGE_9 = BUILDER.comment("里程碑9时的固定伤害")
            .defineInRange("soul_quench_flat_damage_9", 2, 0, 100);
        SOUL_QUENCH_FLAT_DAMAGE_10 = BUILDER.comment("里程碑10时的固定伤害")
            .defineInRange("soul_quench_flat_damage_10", 4, 0, 100);
        SOUL_QUENCH_HP_RATIO_9 = BUILDER.comment("里程碑9时的生命百分比伤害")
            .defineInRange("soul_quench_hp_ratio_9", 0.01, 0.0, 1.0);
        SOUL_QUENCH_HP_RATIO_10 = BUILDER.comment("里程碑10时的生命百分比伤害")
            .defineInRange("soul_quench_hp_ratio_10", 0.02, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("影杀");
        SHADOW_KILL_FLAT_DAMAGE = BUILDER.comment("每次攻击削减影子血量的固定值")
            .defineInRange("shadow_kill_flat_damage", 4, 0, 1000);
        SHADOW_KILL_HP_RATIO = BUILDER.comment("每次攻击额外削减目标最大生命值的比例")
            .defineInRange("shadow_kill_hp_ratio", 0.04, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("休养生息");
        RAPID_RECOVERY_AMPLIFIER_BASE = BUILDER.comment("基础再生等级amplifier（里程碑2时的值，0=再生I）")
            .defineInRange("rapid_recovery_amplifier_base", 0, 0, 10);
        RAPID_RECOVERY_AMPLIFIER_STEP = BUILDER.comment("每2个里程碑增加的amplifier")
            .defineInRange("rapid_recovery_amplifier_step", 1, 0, 5);
        RAPID_RECOVERY_DELAY_TICKS = BUILDER.comment("脱战后等待时间（tick），默认100=5秒")
            .defineInRange("rapid_recovery_delay_ticks", 100, 20, 1200);
        BUILDER.pop();

        BUILDER.push("不动如山");
        KNOCKBACK_RESIST_BASE = BUILDER.comment("基础抗击退率（%），里程碑3时的值")
            .defineInRange("knockback_resist_base", 30, 0, 100);
        KNOCKBACK_RESIST_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的抗击退率（%）")
            .defineInRange("knockback_resist_per_milestone", 7, 0, 30);
        BUILDER.pop();

        BUILDER.push("嗜血");
        LIFESTEAL_BASE = BUILDER.comment("基础吸血率（%），里程碑6时的值")
            .defineInRange("lifesteal_base", 5, 0, 50);
        LIFESTEAL_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的吸血率（%）")
            .defineInRange("lifesteal_per_milestone", 2, 0, 20);
        LIFESTEAL_CAP_RATIO = BUILDER.comment("单次吸血上限（最大生命值比例）")
            .defineInRange("lifesteal_cap_ratio", 0.2, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("大地之力");
        DIGGING_POWER_BASE = BUILDER.comment("基础挖掘速度倍数，里程碑1时的值")
            .defineInRange("digging_power_base", 1.3, 0.5, 10.0);
        DIGGING_POWER_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的倍数")
            .defineInRange("digging_power_per_milestone", 0.05, 0.0, 2.0);
        BUILDER.pop();

        BUILDER.push("无形之手");
        EXTENDED_REACH_BASE = BUILDER.comment("基础额外触及距离（格），里程碑4时的值")
            .defineInRange("extended_reach_base", 1.0, 0.0, 32.0);
        EXTENDED_REACH_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的格数")
            .defineInRange("extended_reach_per_milestone", 0.2, 0.0, 5.0);
        BUILDER.pop();

        BUILDER.push("鸿运当头");
        FORTUNE_FAVOR_BONUS_BASE = BUILDER.comment("基础时运/抢夺加成等级，里程碑5时的值")
            .defineInRange("fortune_favor_bonus_base", 1, 0, 10);
        FORTUNE_FAVOR_BONUS_STEP = BUILDER.comment("每2个里程碑增加的等级")
            .defineInRange("fortune_favor_bonus_step", 1, 0, 5);
        BUILDER.pop();

        BUILDER.push("坚韧之躯");
        VITALITY_BASE = BUILDER.comment("基础生命值加成（半格），里程碑7时的值")
            .defineInRange("vitality_base", 4.0, 0.0, 100.0);
        VITALITY_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的生命值")
            .defineInRange("vitality_per_milestone", 2.0, 0.0, 50.0);
        BUILDER.pop();

        BUILDER.push("觉醒强化");
        AWAKEN_MULTIPLIER = BUILDER.comment("觉醒数值强化倍率")
            .defineInRange("awaken_multiplier", 1.3, 0.5, 10.0);

        AWAKEN_VOID_STEP_JUMPS = BUILDER.comment("觉醒虚空踏步 — 总跳跃次数")
            .defineInRange("awaken_void_step_jumps", 3, 2, 10);
        AWAKEN_SOAR_SPEED = BUILDER.comment("觉醒翱翔 — 飞行速度倍率")
            .defineInRange("awaken_soar_speed", 1.5, 1.0, 5.0);
        AWAKEN_SOUL_QUENCH_EXECUTE_THRESHOLD = BUILDER.comment("觉醒淬魂之力 — 斩杀线阈值（生命比例）")
            .defineInRange("awaken_soul_quench_execute_threshold", 0.2, 0.0, 1.0);
        AWAKEN_SHADOW_KILL_AOE_RADIUS = BUILDER.comment("觉醒影杀 — AOE 半径（格）")
            .defineInRange("awaken_shadow_kill_aoe_radius", 8.0, 1.0, 64.0);
        AWAKEN_SHADOW_KILL_AOE_RATIO = BUILDER.comment("觉醒影杀 — AOE 影子血量削减比例")
            .defineInRange("awaken_shadow_kill_aoe_ratio", 0.15, 0.0, 1.0);
        AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS = BUILDER.comment("觉醒影杀 — AOE 最大目标数")
            .defineInRange("awaken_shadow_kill_aoe_max_targets", 16, 1, 200);
        AWAKEN_LIFESTEAL_SHIELD_CAP = BUILDER.comment("觉醒嗜血 — 吸收护盾上限（生命比例）")
            .defineInRange("awaken_lifesteal_shield_cap", 0.2, 0.0, 1.0);
        AWAKEN_LIFESTEAL_SHIELD_DURATION = BUILDER.comment("觉醒嗜血 — 吸收护盾持续时间（tick）")
            .defineInRange("awaken_lifesteal_shield_duration", 100, 20, 72000);
        AWAKEN_PURIFIED_SOUL_RADIUS = BUILDER.comment("觉醒净魂 — 虚弱光环半径（格）")
            .defineInRange("awaken_purified_soul_radius", 16, 1, 128);
        AWAKEN_JUDGMENT_RANGE_MULT = BUILDER.comment("觉醒旅者审判 — 范围倍率")
            .defineInRange("awaken_judgment_range_mult", 1.5, 1.0, 10.0);
        AWAKEN_SANCTUARY_SPEED = BUILDER.comment("觉醒旅者庇护 — 可移动速度倍率")
            .defineInRange("awaken_sanctuary_speed", 0.3, 0.0, 1.0);
        AWAKEN_UNDYING_ARMOR_BONUS = BUILDER.comment("觉醒不朽装备 — 每件护甲额外护甲值")
            .defineInRange("awaken_undying_armor_bonus", 1.0, 0.0, 10.0);
        AWAKEN_UNDYING_WEAPON_BONUS = BUILDER.comment("觉醒不朽装备 — 主手武器伤害倍率")
            .defineInRange("awaken_undying_weapon_bonus", 0.15, 0.0, 2.0);
        BUILDER.pop(); // 觉醒强化

        BUILDER.pop(); // 能力数值
        BUILDER.pop(); // 冒险能力配置
        SPEC = BUILDER.build();
    }
}
