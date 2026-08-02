package com.ayin90723.adventure_power.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.*;

public class ModConfig {
    public static final Builder BUILDER = new Builder();
    public static final ForgeConfigSpec SPEC;

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
    public static final BooleanValue HEALING_BLOCK_ALLOW_BOSS_PHASE_TWO;

    // --- 虚空踏步 ---
    public static final DoubleValue VOID_STEP_POWER;

    // --- 恩赐永驻 ---（保底阈值 + 延长量）
    public static final IntValue BUFF_MIN_DURATION;
    public static final IntValue BUFF_EXTEND_AMOUNT;

    // --- 受击坚韧 ---（重置 + 层数 + 减伤）
    public static final IntValue RESILIENCE_RESET_TICKS;
    public static final IntValue RESILIENCE_STACKS_8;
    public static final IntValue RESILIENCE_STACKS_9;
    public static final IntValue RESILIENCE_STACKS_10;
    public static final DoubleValue RESILIENCE_DAMAGE_REDUCTION_PER_STACK;

    // --- 淬魂之力 ---
    public static final IntValue SOUL_QUENCH_FLAT_DAMAGE_9;
    public static final IntValue SOUL_QUENCH_FLAT_DAMAGE_10;
    public static final DoubleValue SOUL_QUENCH_HP_RATIO_9;
    public static final DoubleValue SOUL_QUENCH_HP_RATIO_10;
    public static final DoubleValue SOUL_QUENCH_HEALING_BLOCK_MULTIPLIER;

    // --- 影杀 ---
    public static final IntValue SHADOW_KILL_FLAT_DAMAGE;
    public static final DoubleValue SHADOW_KILL_HP_RATIO;
    public static final IntValue SHADOW_KILL_DATA_EXPIRE_TICKS;
    public static final IntValue SHADOW_KILL_CLEANUP_INTERVAL;

    // --- 休养生息 ---
    public static final IntValue RAPID_RECOVERY_AMPLIFIER_BASE;
    public static final IntValue RAPID_RECOVERY_AMPLIFIER_STEP;
    public static final IntValue RAPID_RECOVERY_DELAY_TICKS;
    public static final IntValue RAPID_RECOVERY_CHECK_INTERVAL;
    public static final DoubleValue RAPID_RECOVERY_HEAL_PER_AMPLIFIER;

    // --- 不动如山 ---
    public static final IntValue KNOCKBACK_RESIST_BASE;
    public static final IntValue KNOCKBACK_RESIST_PER_MILESTONE;
    public static final DoubleValue KNOCKBACK_RESIST_HARD_CAP;

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

    // --- 满载而归 ---
    public static final IntValue LOOT_ALL_COPIES;
    public static final IntValue LOOT_ALL_MAX_ITEMS;

    // --- 死亡抗拒 ---
    public static final IntValue DEATH_DEFY_INVUL_DURATION;
    public static final IntValue DEATH_DEFY_COOLDOWN_DURATION;

    // --- 主动技能 ---
    public static final DoubleValue ACTIVE_SKILL_JUDGMENT_BASE_DAMAGE;
    public static final DoubleValue ACTIVE_SKILL_JUDGMENT_HP_RATIO;
    public static final DoubleValue ACTIVE_SKILL_JUDGMENT_RADIUS;
    public static final IntValue ACTIVE_SKILL_JUDGMENT_COOLDOWN;
    public static final IntValue ACTIVE_SKILL_SANCTUARY_DURATION;
    public static final IntValue ACTIVE_SKILL_SANCTUARY_COOLDOWN;
    public static final IntValue ACTIVE_SKILL_GCD;

    // --- 磁吸 ---
    public static final DoubleValue MAGNET_RADIUS_BASE;
    public static final DoubleValue MAGNET_RADIUS_PER_MILESTONE;
    public static final DoubleValue MAGNET_PICKUP_RADIUS;
    public static final IntValue MAGNET_SCAN_INTERVAL;
    public static final DoubleValue MAGNET_PULL_FACTOR;

    // --- 经验加成 ---
    public static final DoubleValue XP_BOOST_BASE;
    public static final DoubleValue XP_BOOST_PER_MILESTONE;

    // --- 全视之眼 ---
    public static final IntValue ALL_SEEING_NIGHT_VISION_DURATION;

    // --- 加速 ---
    public static final DoubleValue SWIFT_SPEED_BASE;
    public static final DoubleValue SWIFT_SPEED_PER_MILESTONE;
    public static final DoubleValue SWIFT_EXHAUSTION_REDUCTION;
    public static final IntValue SWIFT_WATER_DURATION;

    // ==================== 觉醒强化 ====================
    public static final DoubleValue AWAKEN_MULTIPLIER;
    public static final DoubleValue AWAKEN_PERCENT_CAP;
    public static final DoubleValue AWAKEN_VOID_STEP_DASH;
    public static final DoubleValue AWAKEN_SOAR_SPEED;
    public static final DoubleValue AWAKEN_SOUL_QUENCH_EXECUTE_THRESHOLD;
    public static final DoubleValue AWAKEN_SOUL_QUENCH_EXECUTE_MULTIPLIER;
    public static final DoubleValue AWAKEN_SHADOW_KILL_AOE_RADIUS;
    public static final DoubleValue AWAKEN_SHADOW_KILL_AOE_RATIO;
    public static final IntValue AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS;
    public static final DoubleValue AWAKEN_LIFESTEAL_SHIELD_CAP;
    public static final IntValue AWAKEN_PURIFIED_SOUL_RADIUS;
    public static final IntValue AWAKEN_PURIFIED_SOUL_AURA_INTERVAL;
    public static final IntValue AWAKEN_PURIFIED_SOUL_WEAKNESS_AMPLIFIER;
    public static final IntValue AWAKEN_PURIFIED_SOUL_WEAKNESS_DURATION;
    public static final DoubleValue AWAKEN_JUDGMENT_RANGE_MULT;
    public static final DoubleValue AWAKEN_SANCTUARY_SPEED;
    public static final DoubleValue AWAKEN_UNDYING_ARMOR_BONUS;
    public static final DoubleValue AWAKEN_UNDYING_WEAPON_BONUS;
    public static final DoubleValue AWAKEN_REJECT_MANIP_REFLECT_RATIO;
    public static final IntValue AWAKEN_FORTUNE_FAVOR_BONUS;
    public static final IntValue AWAKEN_RESILIENCE_BONUS_STACKS;
    public static final DoubleValue AWAKEN_HEALING_BLOCK_VULN;
    public static final IntValue AWAKEN_PIERCING_GAZE_NO_IFRAME_TICKS;
    public static final IntValue AWAKEN_RAPID_RECOVERY_BONUS;
    public static final BooleanValue LOOT_ALL_AWAKENED_MAX_COUNT;
    public static final IntValue LOOT_ALL_AWAKENED_COPIES;
    public static final DoubleValue AWAKEN_MAGNET_RADIUS_MULT;
    public static final BooleanValue AWAKEN_MAGNET_INCLUDE_XP;
    public static final DoubleValue AWAKEN_XP_BOOST_MULT;
    // 注意：TOML 键名统一 SEEING 拼写（v1.3.1 起；旧键 awaken_all_seing_* 会失效重置为默认值）
    public static final IntValue AWAKEN_ALL_SEEING_RADIUS;
    public static final IntValue AWAKEN_ALL_SEEING_RADAR_MAX;
    public static final IntValue AWAKEN_ALL_SEEING_RADAR_SCAN_INTERVAL;
    public static final DoubleValue AWAKEN_SWIFT_PUSH_RADIUS;
    public static final DoubleValue AWAKEN_SWIFT_PUSH_STRENGTH;

    static {
        BUILDER.push("冒险能力配置");

        BUILDER.push("调试");
        TRUE_HEALTH_DEBUG_LOG = BUILDER.comment("真实血量调试日志")
            .define("true_health_debug_log", false);
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
        DAMAGE_RESIST_BASE = BUILDER.comment("基础减伤率（%），里程碑6时的值")
            .defineInRange("damage_resist_base", 10, 0, 100);
        DAMAGE_RESIST_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的减伤率（%）")
            .defineInRange("damage_resist_per_milestone", 5, 0, 50);
        BUILDER.pop();

        BUILDER.push("禁疗之触");
        HEALING_BLOCK_BASE = BUILDER.comment("基础禁疗持续时间（秒），里程碑7时的值")
            .defineInRange("healing_block_base", 3, 1, 60);
        HEALING_BLOCK_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的禁疗时间（秒）")
            .defineInRange("healing_block_per_milestone", 1, 0, 30);
        HEALING_BLOCK_ALLOW_BOSS_PHASE_TWO = BUILDER.comment("是否允许 Boss（凋零/末影龙）在禁疗下进入二阶段（true=允许二阶段，false=禁疗下直接击杀）")
            .define("healing_block_allow_boss_phase_two", true);
        BUILDER.pop();

        BUILDER.push("虚空踏步");
        VOID_STEP_POWER = BUILDER.comment("空中跳跃力度倍率（在原版 0.42 跳跃力基础上叠加），客户端预测与服务端权威共用，保证两端一致")
            .defineInRange("void_step_power", 1.2, 0.5, 5.0);
        BUILDER.pop();

        BUILDER.push("恩赐永驻");
        BUFF_MIN_DURATION = BUILDER.comment("正面效果最低保底持续时间（tick）")
            .defineInRange("buff_min_duration", 400, 0, 1200);
        BUFF_EXTEND_AMOUNT = BUILDER.comment("低于保底值时额外延长的量（tick）")
            .defineInRange("buff_extend_amount", 0, 0, 600);
        BUILDER.pop();

        BUILDER.push("受击坚韧");
        RESILIENCE_RESET_TICKS = BUILDER.comment("无受伤重置时间（tick）")
            .defineInRange("resilience_reset_ticks", 100, 20, 600);
        RESILIENCE_STACKS_8 = BUILDER.comment("里程碑8时的最大减伤层数")
            .defineInRange("resilience_stacks_8", 5, 0, 50);
        RESILIENCE_STACKS_9 = BUILDER.comment("里程碑9时的最大减伤层数")
            .defineInRange("resilience_stacks_9", 8, 0, 50);
        RESILIENCE_STACKS_10 = BUILDER.comment("里程碑10时的最大减伤层数")
            .defineInRange("resilience_stacks_10", 12, 0, 50);
        RESILIENCE_DAMAGE_REDUCTION_PER_STACK = BUILDER.comment("每层提供的减伤比例")
            .defineInRange("resilience_damage_reduction_per_stack", 0.05, 0.0, 1.0);
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
        SOUL_QUENCH_HEALING_BLOCK_MULTIPLIER = BUILDER.comment("对禁疗之触标记目标的额外伤害倍率")
            .defineInRange("soul_quench_healing_block_multiplier", 1.5, 1.0, 10.0);
        BUILDER.pop();

        BUILDER.push("影杀");
        SHADOW_KILL_FLAT_DAMAGE = BUILDER.comment("每次攻击削减影子血量的固定值")
            .defineInRange("shadow_kill_flat_damage", 4, 0, 1000);
        SHADOW_KILL_HP_RATIO = BUILDER.comment("每次攻击额外削减目标最大生命值的比例")
            .defineInRange("shadow_kill_hp_ratio", 0.04, 0.0, 1.0);
        SHADOW_KILL_DATA_EXPIRE_TICKS = BUILDER.comment("影子血量数据无操作过期时间（tick，默认6000=5分钟）")
            .defineInRange("shadow_kill_data_expire_ticks", 6000, 200, 72000);
        SHADOW_KILL_CLEANUP_INTERVAL = BUILDER.comment("全局清理过期影子血量的周期（tick，默认200=10秒）")
            .defineInRange("shadow_kill_cleanup_interval", 200, 20, 1200);
        BUILDER.pop();

        BUILDER.push("休养生息");
        RAPID_RECOVERY_AMPLIFIER_BASE = BUILDER.comment("基础再生等级amplifier（里程碑2时的值，0=再生I）")
            .defineInRange("rapid_recovery_amplifier_base", 0, 0, 10);
        RAPID_RECOVERY_AMPLIFIER_STEP = BUILDER.comment("每2个里程碑增加的amplifier")
            .defineInRange("rapid_recovery_amplifier_step", 1, 0, 5);
        RAPID_RECOVERY_DELAY_TICKS = BUILDER.comment("脱战后等待时间（tick），默认100=5秒")
            .defineInRange("rapid_recovery_delay_ticks", 100, 20, 1200);
        RAPID_RECOVERY_CHECK_INTERVAL = BUILDER.comment("脱战再生检查间隔（tick），默认60=3秒")
            .defineInRange("rapid_recovery_check_interval", 60, 10, 200);
        RAPID_RECOVERY_HEAL_PER_AMPLIFIER = BUILDER.comment("每级 amplifier 折算的回血量（HP/周期），默认1.0")
            .defineInRange("rapid_recovery_heal_per_amplifier", 1.0, 0.1, 10.0);
        BUILDER.pop();

        BUILDER.push("不动如山");
        KNOCKBACK_RESIST_BASE = BUILDER.comment("基础抗击退率（%），里程碑3时的值")
            .defineInRange("knockback_resist_base", 30, 0, 100);
        KNOCKBACK_RESIST_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的抗击退率（%）")
            .defineInRange("knockback_resist_per_milestone", 7, 0, 30);
        KNOCKBACK_RESIST_HARD_CAP = BUILDER.comment("抗击退率硬上限（%），觉醒后生效")
            .defineInRange("knockback_resist_hard_cap", 100.0, 0.0, 100.0);
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

        BUILDER.push("满载而归");
        LOOT_ALL_COPIES = BUILDER.comment("基础：每样掉落物给几份")
            .defineInRange("loot_all_copies", 1, 0, 64);
        LOOT_ALL_MAX_ITEMS = BUILDER.comment("单次击杀额外掉落物总数量上限（防极端配置卡服）")
            .defineInRange("loot_all_max_items", 100, 1, 1000);
        BUILDER.pop();

        BUILDER.push("死亡抗拒");
        DEATH_DEFY_INVUL_DURATION = BUILDER.comment("无敌持续时间（tick）")
            .defineInRange("death_defy_invul_duration", 1200, 20, 72000);
        DEATH_DEFY_COOLDOWN_DURATION = BUILDER.comment("冷却时间（tick）")
            .defineInRange("death_defy_cooldown_duration", 6000, 100, 720000);
        BUILDER.pop();

        BUILDER.push("主动技能");
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

        BUILDER.push("磁吸");
        MAGNET_RADIUS_BASE = BUILDER.comment("基础吸取半径（格），里程碑4时的值")
            .defineInRange("magnet_radius_base", 5.0, 0.0, 32.0);
        MAGNET_RADIUS_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的吸取半径")
            .defineInRange("magnet_radius_per_milestone", 0.5, 0.0, 8.0);
        MAGNET_PICKUP_RADIUS = BUILDER.comment("自动拾取距离（格），物品进入此距离立即吸入背包")
            .defineInRange("magnet_pickup_radius", 1.5, 0.5, 6.0);
        MAGNET_SCAN_INTERVAL = BUILDER.comment("吸取扫描间隔（tick，默认5=每0.25秒，降低性能开销）")
            .defineInRange("magnet_scan_interval", 5, 1, 40);
        MAGNET_PULL_FACTOR = BUILDER.comment("每次扫描朝玩家拉近的距离比例（默认0.3=30%）")
            .defineInRange("magnet_pull_factor", 0.3, 0.05, 1.0);
        BUILDER.pop();

        BUILDER.push("经验加成");
        XP_BOOST_BASE = BUILDER.comment("基础经验倍率，里程碑5时的值（1.25=+25%）")
            .defineInRange("xp_boost_base", 1.25, 1.0, 10.0);
        XP_BOOST_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的经验倍率")
            .defineInRange("xp_boost_per_milestone", 0.05, 0.0, 2.0);
        BUILDER.pop();

        BUILDER.push("全视之眼");
        ALL_SEEING_NIGHT_VISION_DURATION = BUILDER.comment("夜视刷新时长（tick，循环刷新保持常驻；默认2400=2分钟。"
            + "注意：原版夜视剩余<200tick（10秒）时画面会闪烁，刷新阈值固定在剩余400tick，"
            + "因此本值低于400会导致每tick刷新（功能正常但略有同步开销））")
            .defineInRange("all_seeing_night_vision_duration", 2400, 60, 24000);
        BUILDER.pop();

        BUILDER.push("加速");
        SWIFT_SPEED_BASE = BUILDER.comment("基础速度等级（SPEED amplifier，里程碑3时的值，0=速度I）")
            .defineInRange("swift_speed_base", 0.05, 0.0, 5.0);
        SWIFT_SPEED_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的速度等级（0.5=每2里程碑+1级）")
            .defineInRange("swift_speed_per_milestone", 0.02, 0.0, 3.0);
        SWIFT_EXHAUSTION_REDUCTION = BUILDER.comment("疾跑饱食消耗降低比例（0.8=降低80%，1.0=完全免费）")
            .defineInRange("swift_exhaustion_reduction", 0.8, 0.0, 1.0);
        SWIFT_WATER_DURATION = BUILDER.comment("水下加速（海豚祝福）刷新时长（tick，循环刷新保持常驻）")
            .defineInRange("swift_water_duration", 220, 60, 1200);
        BUILDER.pop();

        BUILDER.push("觉醒强化");
        AWAKEN_MULTIPLIER = BUILDER.comment("觉醒数值强化倍率")
            .defineInRange("awaken_multiplier", 1.5, 0.5, 10.0);
        AWAKEN_PERCENT_CAP = BUILDER.comment("觉醒百分比能力硬上限（灵巧/伤害抗性等，默认0.95=95%）")
            .defineInRange("awaken_percent_cap", 0.95, 0.5, 1.0);
        AWAKEN_VOID_STEP_DASH = BUILDER.comment("觉醒虚空踏步·御风 - 二段跳时朝视角方向的水平冲刺冲量")
            .defineInRange("awaken_void_step_dash", 0.6, 0.0, 2.0);
        AWAKEN_SOAR_SPEED = BUILDER.comment("觉醒翱翔 - 飞行速度倍率")
            .defineInRange("awaken_soar_speed", 1.5, 1.0, 5.0);
        AWAKEN_SOUL_QUENCH_EXECUTE_THRESHOLD = BUILDER.comment("觉醒淬魂之力 - 斩杀线阈值（生命比例）")
            .defineInRange("awaken_soul_quench_execute_threshold", 0.2, 0.0, 1.0);
        AWAKEN_SOUL_QUENCH_EXECUTE_MULTIPLIER = BUILDER.comment("觉醒淬魂之力 - 斩杀线触发时的伤害倍率（默认2.0=翻倍）")
            .defineInRange("awaken_soul_quench_execute_multiplier", 2.0, 1.0, 10.0);
        AWAKEN_SHADOW_KILL_AOE_RADIUS = BUILDER.comment("觉醒影杀 - AOE 半径（格）")
            .defineInRange("awaken_shadow_kill_aoe_radius", 8.0, 1.0, 64.0);
        AWAKEN_SHADOW_KILL_AOE_RATIO = BUILDER.comment("觉醒影杀 - AOE 影子血量削减比例")
            .defineInRange("awaken_shadow_kill_aoe_ratio", 0.15, 0.0, 1.0);
        AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS = BUILDER.comment("觉醒影杀 - AOE 最大目标数")
            .defineInRange("awaken_shadow_kill_aoe_max_targets", 16, 1, 200);
        AWAKEN_LIFESTEAL_SHIELD_CAP = BUILDER.comment("觉醒嗜血 - 吸收护盾上限（生命比例）")
            .defineInRange("awaken_lifesteal_shield_cap", 0.2, 0.0, 1.0);
        AWAKEN_PURIFIED_SOUL_RADIUS = BUILDER.comment("觉醒净魂 - 虚弱光环半径（格）")
            .defineInRange("awaken_purified_soul_radius", 16, 1, 128);
        AWAKEN_PURIFIED_SOUL_AURA_INTERVAL = BUILDER.comment("觉醒净魂 - 虚弱光环施加间隔（tick，默认40=2秒）")
            .defineInRange("awaken_purified_soul_aura_interval", 40, 1, 200);
        AWAKEN_PURIFIED_SOUL_WEAKNESS_AMPLIFIER = BUILDER.comment("觉醒净魂 - 虚弱光环等级（amplifier，默认1=虚弱II）")
            .defineInRange("awaken_purified_soul_weakness_amplifier", 1, 0, 4);
        AWAKEN_PURIFIED_SOUL_WEAKNESS_DURATION = BUILDER.comment("觉醒净魂 - 虚弱持续时间（tick，默认100=5秒）")
            .defineInRange("awaken_purified_soul_weakness_duration", 100, 20, 600);
        AWAKEN_JUDGMENT_RANGE_MULT = BUILDER.comment("觉醒旅者审判 - 范围倍率")
            .defineInRange("awaken_judgment_range_mult", 1.5, 1.0, 10.0);
        AWAKEN_SANCTUARY_SPEED = BUILDER.comment("觉醒旅者庇护 - 可移动速度倍率")
            .defineInRange("awaken_sanctuary_speed", 0.3, 0.0, 1.0);
        AWAKEN_UNDYING_ARMOR_BONUS = BUILDER.comment("觉醒不朽装备 - 每件护甲额外护甲值")
            .defineInRange("awaken_undying_armor_bonus", 2.0, 0.0, 10.0);
        AWAKEN_UNDYING_WEAPON_BONUS = BUILDER.comment("觉醒不朽装备 - 主手武器伤害倍率")
            .defineInRange("awaken_undying_weapon_bonus", 0.25, 0.0, 2.0);
        AWAKEN_REJECT_MANIP_REFLECT_RATIO = BUILDER.comment("觉醒拒绝篡改 - 反弹被拒绝伤害的比例")
            .defineInRange("awaken_reject_manip_reflect_ratio", 0.30, 0.0, 1.0);
        AWAKEN_FORTUNE_FAVOR_BONUS = BUILDER.comment("觉醒鸿运当头 - 额外时运/抢夺等级")
            .defineInRange("awaken_fortune_favor_bonus", 2, 0, 10);
        AWAKEN_RESILIENCE_BONUS_STACKS = BUILDER.comment("觉醒受击坚韧 - 额外最大层数")
            .defineInRange("awaken_resilience_bonus_stacks", 6, 0, 50);
        AWAKEN_HEALING_BLOCK_VULN = BUILDER.comment("觉醒禁疗之触 - 易伤倍率")
            .defineInRange("awaken_healing_block_vuln", 1.2, 1.0, 5.0);
        AWAKEN_PIERCING_GAZE_NO_IFRAME_TICKS = BUILDER.comment("觉醒破敌之眼 - 禁无敌帧时长（tick，默认60=3秒）")
            .defineInRange("awaken_piercing_gaze_no_iframe_ticks", 60, 1, 200);
        AWAKEN_RAPID_RECOVERY_BONUS = BUILDER.comment("觉醒休养生息 - 每周期额外回血量（HP）")
            .defineInRange("awaken_rapid_recovery_bonus", 5, 0, 20);
        LOOT_ALL_AWAKENED_MAX_COUNT = BUILDER.comment("觉醒满载而归 - 每样取掉落表最大数量")
            .define("loot_all_awakened_max_count", true);
        LOOT_ALL_AWAKENED_COPIES = BUILDER.comment("觉醒满载而归 - 每样份数")
            .defineInRange("loot_all_awakened_copies", 2, 0, 64);
        AWAKEN_MAGNET_RADIUS_MULT = BUILDER.comment("觉醒磁吸 - 吸取半径倍率")
            .defineInRange("awaken_magnet_radius_mult", 1.5, 1.0, 5.0);
        AWAKEN_MAGNET_INCLUDE_XP = BUILDER.comment("觉醒磁吸 - 是否吸取经验球")
            .define("awaken_magnet_include_xp", true);
        AWAKEN_XP_BOOST_MULT = BUILDER.comment("觉醒经验加成 - 倍率再乘此值")
            .defineInRange("awaken_xp_boost_mult", 1.5, 1.0, 5.0);
        // 觉醒全视之眼 = 威胁雷达（原「实体发光」旧方案已废弃，不再提供高亮相关配置）
        AWAKEN_ALL_SEEING_RADIUS = BUILDER.comment("觉醒全视之眼 - 威胁雷达扫描半径（格）")
            .defineInRange("awaken_all_seeing_radius", 24, 1, 128);
        AWAKEN_ALL_SEEING_RADAR_MAX = BUILDER.comment("觉醒全视之眼 - 威胁雷达最多显示目标数（防堵屏）")
            .defineInRange("awaken_all_seeing_radar_max", 6, 1, 16);
        AWAKEN_ALL_SEEING_RADAR_SCAN_INTERVAL = BUILDER.comment("觉醒全视之眼 - 雷达扫描间隔（tick，默认10=0.5秒）")
            .defineInRange("awaken_all_seeing_radar_scan_interval", 10, 1, 100);
        AWAKEN_SWIFT_PUSH_RADIUS = BUILDER.comment("觉醒加速 - 疾跑推开半径（格）")
            .defineInRange("awaken_swift_push_radius", 3.0, 0.0, 16.0);
        AWAKEN_SWIFT_PUSH_STRENGTH = BUILDER.comment("觉醒加速 - 疾跑推力强度")
            .defineInRange("awaken_swift_push_strength", 0.6, 0.0, 4.0);
        BUILDER.pop(); // 觉醒强化

        BUILDER.pop(); // 能力数值
        BUILDER.pop(); // 冒险能力配置
        SPEC = BUILDER.build();
    }
}
