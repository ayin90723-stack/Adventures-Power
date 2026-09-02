package com.ayin90723.adventure_power.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.*;

import java.util.List;

public class ModConfig {
    public static final Builder BUILDER = new Builder();
    public static final ForgeConfigSpec SPEC;

    // === 调试 ===
    /** 全局调试日志总开关：关闭时所有能力日志一律不输出 */
    public static final BooleanValue DEBUG_LOG;
    /** 淬魂之力调试日志 */
    public static final BooleanValue DEBUG_LOG_SOUL_QUENCH;
    /** 禁疗之触调试日志 */
    public static final BooleanValue DEBUG_LOG_HEALING_BLOCK;
    /** 影杀调试日志 */
    public static final BooleanValue DEBUG_LOG_SHADOW_KILL;
    /** 破敌之眼调试日志 */
    public static final BooleanValue DEBUG_LOG_PIERCING_GAZE;
    /** 嗜血调试日志 */
    public static final BooleanValue DEBUG_LOG_LIFESTEAL;
    /** 真实血量调试日志 */
    public static final BooleanValue DEBUG_LOG_TRUE_HEALTH;
    /** 容器守护调试日志（v1.4.9） */
    public static final BooleanValue DEBUG_LOG_CONTAINER;

    // ==================== 系统与引擎（基础设施分组，v1.4.9-b 配置重构：能力共用的引擎/系统开关
    // 不再混入「能力数值」——与「能力数值」「调试」平级，见 static 块「系统与引擎」分组） ====================

    // --- 淬魂之力·五层改血引擎（v1.4.2，docs/quench-upgrade-proposal.md） ---
    public static final BooleanValue QUENCH_ENGINE_ENABLED;
    public static final DoubleValue QUENCH_PROBE_EPS_BASE;
    public static final DoubleValue QUENCH_REPROBE_RATIO;
    public static final BooleanValue QUENCH_LAYER3_ENABLED;
    public static final BooleanValue QUENCH_LAYER4_ENABLED;
    public static final IntValue QUENCH_GRAPH_BUDGET;
    // --- 淬魂之力·多存储合成血（v1.4.3，docs/gate-oracle-proposal.md §5/§11-4） ---
    /** v1.4.8 JVM 只读字节码快照（淬魂分组，默认开——v1.4.8 大整合包实测通过后转开）。 */
    public static final BooleanValue JVM_SNAPSHOT_ENABLED;
    public static final BooleanValue QUENCH_MULTI_STORE_ENABLED;
    // --- GateOracle 存活语义反推（v1.4.3，docs/gate-oracle-proposal.md） ---
    public static final BooleanValue GATE_ORACLE_ENABLED;
    public static final BooleanValue GATE_ORACLE_KILL_TOOL_ENABLED;
    public static final IntValue GATE_ORACLE_WAIT_TICKS;
    /** v1.4.9 死亡判据钥匙（第三部分）：静态反推位打包判据的 codec 配对并直接写钥匙。 */
    public static final BooleanValue GATE_ORACLE_DEATH_KEY_ENABLED;
    // --- v1.4.9 L5 通用数值反演（第四部分） ---
    public static final BooleanValue QUENCH_NUMERIC_INVERSION_ENABLED;
    public static final IntValue QUENCH_INVERSION_BUDGET_MS;
    public static final IntValue QUENCH_INVERSION_MAX_CELLS;
    // --- v1.4.9 容器守护（第一部分：审计与重建链，独立分组——七轮评审定落位） ---
    public static final BooleanValue CONTAINER_AUDIT_ENABLED;
    public static final IntValue CONTAINER_AUDIT_INTERVAL_TICKS;
    public static final BooleanValue CONTAINER_REBUILD_ENABLED;
    public static final IntValue CONTAINER_REBUILD_BACKOFF_THRESHOLD;

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
    public static final IntValue SOUL_QUENCH_PARTICLE_COUNT;

    // --- 破敌之眼 ---
    /** 穿透兜底补刀上限（目标最大生命值比例，v1.4.4：防饰品加伤放大后 clamp 0 变处决写 0；最大生命基准防等比收敛） */
    public static final DoubleValue PIERCING_GAZE_FALLBACK_CAP_PERCENT;
    public static final BooleanValue PIERCING_GAZE_FEEDBACK_ENABLED;
    public static final IntValue PIERCING_GAZE_FEEDBACK_PARTICLE_COUNT;

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
    public static final BooleanValue RAPID_RECOVERY_ALLOW_EAT_AT_FULL;

    // --- 不动如山 ---
    public static final IntValue KNOCKBACK_RESIST_BASE;
    public static final IntValue KNOCKBACK_RESIST_PER_MILESTONE;
    public static final DoubleValue KNOCKBACK_RESIST_HARD_CAP;

    // --- 嗜血 ---
    public static final IntValue LIFESTEAL_BASE;
    public static final IntValue LIFESTEAL_PER_MILESTONE;
    public static final DoubleValue LIFESTEAL_CAP_RATIO;
    public static final DoubleValue LIFESTEAL_KILL_HEAL;

    // --- 大地之力 ---
    public static final DoubleValue DIGGING_POWER_BASE;
    public static final DoubleValue DIGGING_POWER_PER_MILESTONE;

    // --- 无形之手 ---
    public static final DoubleValue EXTENDED_REACH_BASE;
    public static final DoubleValue EXTENDED_REACH_PER_MILESTONE;

    // --- 鸿运当头 ---
    public static final IntValue FORTUNE_FAVOR_BONUS_BASE;
    public static final IntValue FORTUNE_FAVOR_BONUS_STEP;
    public static final DoubleValue FORTUNE_FAVOR_LUCK_SCALE;

    // --- 坚韧之躯 ---
    public static final DoubleValue VITALITY_BASE;
    public static final DoubleValue VITALITY_PER_MILESTONE;
    public static final DoubleValue VITALITY_HEAL_BONUS_BASE;
    public static final DoubleValue VITALITY_HEAL_BONUS_PER_MILESTONE;

    // --- 满载而归 ---
    public static final IntValue LOOT_ALL_COPIES;
    public static final IntValue LOOT_ALL_MAX_ITEMS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> LOOT_ALL_BLACKLIST;
    public static final BooleanValue LOOT_ALL_DROP_MUSIC_DISCS;
    public static final BooleanValue LOOT_ALL_DROP_SKULLS;

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
    public static final DoubleValue AWAKEN_LIFESTEAL_KILL_HEAL;
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
    public static final DoubleValue AWAKEN_VITALITY_HEAL_MULTIPLIER;
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
        DEBUG_LOG = BUILDER.comment("全局调试日志总开关（关闭时所有能力日志一律不输出，默认关闭）")
            .define("debug_log", false);
        DEBUG_LOG_SOUL_QUENCH = BUILDER.comment("淬魂之力调试日志（需 debug_log 开启）")
            .define("debug_log_soul_quench", false);
        DEBUG_LOG_HEALING_BLOCK = BUILDER.comment("禁疗之触调试日志（需 debug_log 开启）")
            .define("debug_log_healing_block", false);
        DEBUG_LOG_SHADOW_KILL = BUILDER.comment("影杀调试日志（需 debug_log 开启）")
            .define("debug_log_shadow_kill", false);
        DEBUG_LOG_PIERCING_GAZE = BUILDER.comment("破敌之眼调试日志（需 debug_log 开启）")
            .define("debug_log_piercing_gaze", false);
        DEBUG_LOG_LIFESTEAL = BUILDER.comment("嗜血调试日志（需 debug_log 开启）")
            .define("debug_log_lifesteal", false);
        DEBUG_LOG_TRUE_HEALTH = BUILDER.comment("真实血量调试日志（需 debug_log 开启）")
            .define("debug_log_true_health", false);
        DEBUG_LOG_CONTAINER = BUILDER.comment("容器守护调试日志（需 debug_log 开启；审计告警与重建失败为无条件 WARN/ERROR，本开关只控诊断细节）")
            .define("debug_log_container", false);
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
        SOUL_QUENCH_PARTICLE_COUNT = BUILDER.comment("命中时灵魂蓝火粒子数量（0=关闭粒子反馈）")
            .defineInRange("soul_quench_particle_count", 6, 0, 100);
        BUILDER.pop();

        BUILDER.push("破敌之眼");
        PIERCING_GAZE_FALLBACK_CAP_PERCENT = BUILDER.comment("破敌之眼穿透兜底补刀上限（目标最大生命值比例，默认 1%）——穿透后血量未降（Boss 免疫/hurt 被拦）时兜底最多补刀该比例：原公式\"血量−穿透伤害\"在伤害被饰品加伤放大到 ≥ 血量时 clamp 0 变成处决写 0（UomWither 血量 0 直接触发其终式，满搭配饰下\"秒杀\"观感实测）；最大生命基准每刀恒定（等差，约 100 刀磨完归零→正规终式收尾——当前生命基准会等比收敛[血越低每刀越少]配合保活 Boss=磨血僵局打不死，实测推翻）")
            .defineInRange("piercing_gaze_fallback_cap_percent", 0.01, 0.0, 1.0);
        PIERCING_GAZE_FEEDBACK_ENABLED = BUILDER.comment("穿透成功时播放屏障破碎音效+粒子反馈")
            .define("piercing_gaze_feedback_enabled", true);
        PIERCING_GAZE_FEEDBACK_PARTICLE_COUNT = BUILDER.comment("穿透反馈粒子数量")
            .defineInRange("piercing_gaze_feedback_particle_count", 10, 0, 100);
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
        RAPID_RECOVERY_ALLOW_EAT_AT_FULL = BUILDER.comment("饱食度满时仍可食用食物（兼容农夫乐事等通过进食成长的模组，默认开启）")
            .define("rapid_recovery_allow_eat_at_full", true);
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
        LIFESTEAL_KILL_HEAL = BUILDER.comment("击杀回馈固定回血量（HP，0=关闭；觉醒后叠加 AWAKEN_LIFESTEAL_KILL_HEAL）")
            .defineInRange("lifesteal_kill_heal", 3.0, 0.0, 20.0);
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
        FORTUNE_FAVOR_LUCK_SCALE = BUILDER.comment("幸运属性倍率（乘在时运/抢夺等级上写入 Attributes.LUCK，0=关闭幸运加成）")
            .defineInRange("fortune_favor_luck_scale", 1.0, 0.0, 10.0);
        BUILDER.pop();

        BUILDER.push("坚韧之躯");
        VITALITY_BASE = BUILDER.comment("基础生命值加成（半格），里程碑7时的值")
            .defineInRange("vitality_base", 4.0, 0.0, 100.0);
        VITALITY_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的生命值")
            .defineInRange("vitality_per_milestone", 2.0, 0.0, 50.0);
        VITALITY_HEAL_BONUS_BASE = BUILDER.comment("治疗量加成基础比例（0.1=+10%），里程碑7时的值（作用于外部治疗，自家直写回血不受影响）")
            .defineInRange("vitality_heal_bonus_base", 0.1, 0.0, 2.0);
        VITALITY_HEAL_BONUS_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的治疗加成比例")
            .defineInRange("vitality_heal_bonus_per_milestone", 0.05, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("满载而归");
        LOOT_ALL_COPIES = BUILDER.comment("基础：每样掉落物给几份")
            .defineInRange("loot_all_copies", 1, 0, 64);
        LOOT_ALL_MAX_ITEMS = BUILDER.comment("单次击杀额外掉落物总数量上限（防极端配置卡服）")
            .defineInRange("loot_all_max_items", 100, 1, 1000);
        LOOT_ALL_BLACKLIST = BUILDER.comment("黑名单：满载而归额外掉落中过滤的物品（物品注册 ID，如 \"minecraft:player_head\"；仅过滤额外掉落，原版掉落不受影响）")
            .defineListAllowEmpty("loot_all_blacklist", List.of(), obj -> obj instanceof String);
        LOOT_ALL_DROP_MUSIC_DISCS = BUILDER.comment("是否允许满载而归额外掉落唱片（false = 过滤所有唱片类物品，含模组唱片）")
            .define("loot_all_drop_music_discs", true);
        LOOT_ALL_DROP_SKULLS = BUILDER.comment("是否允许满载而归额外掉落头颅（false = 过滤所有头颅类物品，如骷髅头/玩家头/龙首）")
            .define("loot_all_drop_skulls", true);
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
        ACTIVE_SKILL_JUDGMENT_HP_RATIO = BUILDER.comment("旅者审判每里程碑生命值百分比（双基准：最大生命+当前生命各乘此值，如淬魂口径；1里程碑≈6%总伤，5里程碑≈30%）")
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
            + "注意：原版夜视剩余<200tick（10秒）时画面会闪烁，刷新阈值自适应 min(400, max(200, 本值-1))——"
            + "本值高于400时在剩余400tick刷新，低于400时随本值自适应，均远离闪烁线）")
            .defineInRange("all_seeing_night_vision_duration", 2400, 60, 24000);
        BUILDER.pop();

        BUILDER.push("加速");
        SWIFT_SPEED_BASE = BUILDER.comment("基础移速加成比例（0.05=+5% 移速，经 MULTIPLY_TOTAL 属性 modifier 生效）")
            .defineInRange("swift_speed_base", 0.05, 0.0, 5.0);
        SWIFT_SPEED_PER_MILESTONE = BUILDER.comment("每额外里程碑增加的移速加成比例（0.02=每里程碑+2% 移速）")
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
        AWAKEN_LIFESTEAL_KILL_HEAL = BUILDER.comment("觉醒嗜血 - 击杀回馈额外回血量（HP，叠加在 lifesteal_kill_heal 上）")
            .defineInRange("awaken_lifesteal_kill_heal", 2.0, 0.0, 20.0);
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
        AWAKEN_VITALITY_HEAL_MULTIPLIER = BUILDER.comment("觉醒坚韧之躯 - 治疗量加成倍率")
            .defineInRange("awaken_vitality_heal_multiplier", 1.5, 1.0, 5.0);
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

        // ==================== 系统与引擎（基础设施分组，v1.4.9-b 配置重构：能力共用的引擎/系统
        // 开关不再混入「能力数值」——五层改血引擎为淬魂/破敌/影杀/审判共用、GateOracle 接入
        // 点两处[影杀处决+禁疗终局]、容器守护为防御子系统基础设施，均非任何单一能力的数值参数） ====================
        BUILDER.push("系统与引擎");
        BUILDER.push("改血引擎");
        QUENCH_ENGINE_ENABLED = BUILDER.comment("五层改血引擎开关（关闭=退回 v1.4.1 三级直写链；引擎含 L1 通用setter/L2 对象图插针/L3 类静态容器/封存补探与 per-class 负缓存）")
            .define("quench_engine_enabled", true);
        QUENCH_PROBE_EPS_BASE = BUILDER.comment("探针步长基数 ε（量纲缩放下限：ε=max(基数, ulp(读数)×4)；高血量 Boss 的 ulp 地板会自动放大，无需调高）")
            .defineInRange("quench_probe_eps_base", 1.0, 0.1, 100.0);
        QUENCH_REPROBE_RATIO = BUILDER.comment("读数漂移重探阈值（读数较负缓存记录时刻漂移超过 最大生命×该比例 时失效重探；过大=重探滞后，过小=频繁重扫）")
            .defineInRange("quench_reprobe_ratio", 0.01, 0.001, 0.2);
        QUENCH_LAYER3_ENABLED = BUILDER.comment("L3 类静态容器层开关（扫描目标类静态 Map 缓存型血量存储，如 GraeMod UomWither；关闭=L2 失败后直接进 L4）")
            .define("quench_layer3_enabled", true);
        QUENCH_LAYER4_ENABLED = BUILDER.comment("L4 广义写路径层开关（行为学扫描目标模组类与可达 holder 上的单数值参数方法并验证 getHealth 联动，覆盖加密存血/双字段校验/不变量维护型 Boss；探针有界扰动，详见设计文档）")
            .define("quench_layer4_enabled", true);
        QUENCH_GRAPH_BUDGET = BUILDER.comment("L2 对象图扫描预算（访问对象数上限）：geckolib 动画类实体可达图可达数百万对象，超预算立即中止并封存该类（防数秒卡顿）；实测泽林变体 597 万对象单次全图 4.6 秒（靠 DataItem 槽插针覆盖，封存无碍）；灵梦变体 200001 对象卡线（v1.4.2 回归：20 万默认值差 1 个对象被中止→落 L4 触发其 setCombatProgress 反作弊 chaotic——30 万默认覆盖）。调大=覆盖更广但可能卡顿。v1.4.8 起 int/long 位打包字段扫描使全图扫描成本因子约 ×2（每对象每个 int/long 字段多一次解码+值闸过滤，普通怪走门禁直通道不受影响），默认 200 万的余量已按此评估")
            .defineInRange("quench_graph_budget", 2000000, 10000, Integer.MAX_VALUE);
        JVM_SNAPSHOT_ENABLED = BUILDER.comment("JVM 只读字节码快照（v1.4.8 实测通过后默认开）：开启后 GateAnalyzer 的覆写/存储情报分析改用「运行时真身」字节码（经 Mixin 与（若存在）对方 javaagent 全部 transformation 处理后的最终类形态，类路径读不到的注入钩子由此可见）。实现=运行时自附加（Unsafe 解禁 ALLOW_ATTACH_SELF + attach 自身 PID，无需 -javaagent 启动参数）+ dump-only transformer（对一切类返回 null 零修改）+ retransform 快照，只对 mod 层类启用（MC 核心类 retransform 会触发第三方 agent transformer 重跑叠层，排除之）。失败自动降级类路径读（现状行为），日志前缀 [JVM快照]")
            .define("jvm_snapshot_enabled", true);
        QUENCH_MULTI_STORE_ENABLED = BUILDER.comment("多存储合成血支持：getHealth 覆写为多存储之和的 Boss（真血+护盾/身体+护甲双分量形态），检测单分量写入后合成读数不到位 → 差值推断第二分量 → 分配双写（处决双清零/磨血次分量优先承伤）+ 下 tick 复验。关闭时回落 v1.4.2 行为（总读数验证仍生效，失败即作废缓存走既有梯）")
            .define("quench_multi_store_enabled", true);
        QUENCH_NUMERIC_INVERSION_ENABLED = BUILDER.comment("L5 通用数值反演（v1.4.9）：L1~L4 值闸只认已知形态（正向/反向/位打包/固定倍率集），变换超集（任意公式/多字段组合）时候选全弃最终封存。本层兜底黑盒求解——不识别公式，扰动观察响应、数值求解（割线下降），把「变换未知」从死路变成慢路。只在放宽补探失败后触发（常规 Boss 零开销）；求解成功缓存 (Cell 路径, 实测斜率) 供下一刀 O(1) 快路径（写前漂移验证，漂移即作废重解）；失败全量回滚后照常 exhausted。爆炸半径=仅 target 自身对象图+归属 target 的静态容器条目（非目标实体显式剪枝），位型字段与死亡判据字段排除，独立时间预算")
            .define("quench_numeric_inversion_enabled", true);
        QUENCH_INVERSION_BUDGET_MS = BUILDER.comment("L5 反演层独立预算（毫秒/次，不计入 quench_graph_budget 防叠加超时；默认 100=外部先例 200ms 减半——我方有 L1~L4 前置层兜底+缓存快路径，全量求解触发频率低）")
            .defineInRange("quench_inversion_budget_ms", 100, 10, 2000);
        QUENCH_INVERSION_MAX_CELLS = BUILDER.comment("L5 反演 Cell 收集上限（可扰动数值字段数；到达即停——防御性上限，常规 Boss 命中前几个 Cell 即收敛）")
            .defineInRange("quench_inversion_max_cells", 4096, 16, 65536);
        BUILDER.pop(); // 改血引擎
        BUILDER.push("GateOracle");

        GATE_ORACLE_ENABLED = BUILDER.comment("GateOracle 存活语义反推（v1.4.6 起接入点两处：影杀处决路径 + 禁疗 FORCE_KILL 终局复验，淬魂磨血不触发；分组仍在影杀但已非影杀专属）：五层引擎数值通道之外（含写 0 未死/die 拦截）反推存活许可并打开（许可标志/进度阈值/击杀工具/死亡序列触发），让目标走正规死亡链（战利品/事件/遭遇注销对方自清），失败退处决兜底（影杀善后/禁疗终局 ExecutionFinalizer；开关关闭时禁疗终局直接处决善后——终局性是能力承诺，开关只控制是否尝试正规死亡链这一手段）。v1.4.3 三大 Boss 实测通过后默认开启")
            .define("gate_oracle_enabled", true);
        GATE_ORACLE_KILL_TOOL_ENABLED = BUILDER.comment("GateOracle·KILL_TOOL 击杀工具反推：从目标 hurt/die 覆写反推其自己的静态击杀工具并反射调用（唯一实体作用域参数签名闸+调用点常量实参回放+双条件死亡验证，despawn 型不采用）——目标自清含其注册表/复活列表=真死。本末起源 KILL_TOOL 实测通过后默认开启")
            .define("gate_oracle_kill_tool_enabled", true);
        GATE_ORACLE_WAIT_TICKS = BUILDER.comment("GateOracle 轮询型有界等待窗口（tick）：许可静默写入后等待 Boss 自身 tick 消费（写完留着+看门狗，超时降级响写→同栈 die→处决兜底）；禁疗终局复验共用本窗口（归零放行后观察 die 结果的看门狗）；死亡判据钥匙（v1.4.9）共用本窗口——判据翻死后等 Boss 自身死亡序列接管，窗口末无硬证据回滚钥匙退原梯；tick 延迟耦合型 Boss 可调大")
            .defineInRange("gate_oracle_wait_ticks", 10, 2, 200);
        GATE_ORACLE_DEATH_KEY_ENABLED = BUILDER.comment("GateOracle·死亡判据钥匙（v1.4.9 第三部分）：静态反推死亡判据的编解码函数并直接写钥匙——对「死亡态藏在位打包字段」型 Boss（isDeadOrDying 覆写 = decoder(this.field) 形式），行为学试探可能永远猜不中编码，静态反推却能拿到确切的 encoder/decoder 配对。钥匙只翻判据不执行击杀（挂窗口等 Boss 自身逻辑接管）；codec 读回验证不过即回滚（防配对误判写坏字段）；超时回滚零残留退原梯。纯反射写字段+读回验证，激进性低于 KILL_TOOL（定序在其之前）")
            .define("gate_oracle_death_key_enabled", true);
        BUILDER.pop(); // GateOracle

        BUILDER.push("容器守护");
        CONTAINER_AUDIT_ENABLED = BUILDER.comment("容器审计与重建链总开关（v1.4.9 第一部分）：周期巡检冒险者的世界容器接纳状态（tick 表/UUID 注册/EntityLookup 双表/EntitySection/levelCallback/ChunkMap 追踪/PlayerList 名册），缺失时按分级动作修复（Callback 轻修复/原版全链重注册+追踪重建+名册重塞）。关闭时零开销（ServerTick 直接短路）。实体级防御的前提是「实体仍被世界容器接纳」——容器被字段级抹除后玩家「在线但冻结」，真血双通道修复全部失去入口")
            .define("container_audit_enabled", true);
        CONTAINER_AUDIT_INTERVAL_TICKS = BUILDER.comment("审计周期（tick，默认 20=每秒一轮；每轮对每个在线冒险者做纯读检查，预期 <0.1ms/轮）")
            .defineInRange("container_audit_interval_ticks", 20, 2, 1200);
        CONTAINER_REBUILD_ENABLED = BUILDER.comment("二级重建开关：A3~A9 容器条目缺失时的完整重建（原版全链重注册 addNewEntityWithoutEvent + tick 表直塞 + 追踪重建 onTrackingStart + PlayerList 名册重塞）。false 时仅审计告警 + 一级轻修复（Callback 重建）")
            .define("container_rebuild_enabled", true);
        CONTAINER_REBUILD_BACKOFF_THRESHOLD = BUILDER.comment("连续重建失败进入退避的轮数（退避 200 tick——防对抗性持续抹除下每秒全链重试的性能陷阱与日志风暴；重建计数持续增长=攻击仍在持续的表现而非重建失效）")
            .defineInRange("container_rebuild_backoff_threshold", 3, 1, 100);
        BUILDER.pop();
        BUILDER.pop(); // 系统与引擎

        BUILDER.pop(); // 冒险能力配置
        SPEC = BUILDER.build();
    }
}
