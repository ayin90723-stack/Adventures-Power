package com.ayin90723.adventure_power.util;

/**
 * 集中管理所有自定义 persistentData / 物品 NBT key 常量。
 * <p>
 * 保留原有 key 字符串值不变（存档兼容），只将散落的字面量集中定义。
 * 所有字符串字面量替换为 {@code PersistentDataKeys.XXX} 引用后，
 * key 值仍与旧版存档/物品 NBT 完全一致。
 * <p>
 * 注意：本类仅包含<b>自定义</b> key（MME_ 前缀、AP_ 前缀等），
 * 不包含原版 NBT key（如 "Damage", "Enchantments", "display" 等）。
 */
public final class PersistentDataKeys {

    // ==================== 冒险进度系统 — 玩家 persistentData ====================

    /** Capability 持久数据根键，存储完整的冒险进度快照（CompoundTag） */
    public static final String ADVENTURE_PROGRESS = "MME_AdventureProgress";

    /** 首次发放冒险的开始标记，防止重复发放（boolean） */
    public static final String GOT_BEGIN_KEY = "MME_GotAdventureBegin";

    /** 旧版永久解锁标记（v1.0 遗留），登录时迁移后清除（boolean） */
    public static final String OLD_UNLOCKED_KEY = "MME_AdventureUnlocked";

    /** Buff 排除列表（CompoundTag，key=效果ID, value=true） */
    public static final String BUFF_BLACKLIST_KEY = "MME_BuffBlacklist";

    // ==================== 里程碑 NBT — 物品 NBT（冒险饰品） ====================

    /** 旧版阶段标记（v1.0 遗留），用于迁移旧物品 NBT（int） */
    public static final String OLD_STAGE_KEY = "AdventureStage";

    /**
     * 构造里程碑在物品 NBT 中使用的键名（与旧版兼容）。
     * <p>
     * 旧版 getNbtKey() 返回 "MME_Milestone_Nether" 等 PascalCase 格式，
     * 新版 Milestone.getId() 返回小写，此处还原为旧格式以保持存档兼容。
     *
     * @param id 里程碑 ID（小写，如 "nether"）
     * @return NBT 键名，如 "MME_Milestone_Nether"
     */
    public static String milestoneNbtKey(String id) {
        return "MME_Milestone_" + Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    // ==================== 禁疗之触 — 实体 persistentData ====================

    /** 禁疗之触到期时间（long, gameTime） */
    public static final String HEALING_BLOCK_END_TIME = "MME_UndyingSlashEndTime";

    /** 强制击杀标记（boolean），用于跨事件优先级传递 */
    public static final String HEALING_BLOCK_FORCE_KILL = "MME_UndyingSlash_ForceKill";

    // ==================== 影杀 — 攻击者（玩家）persistentData ====================

    /** 影杀影子血量数据根键（CompoundTag，key=目标UUID） */
    public static final String SHADOW_HP_DATA = "AP_ShadowHP_Data";

    /** 影杀影子血量 — 目标最大血量快照（float，子键） */
    public static final String SHADOW_HP_TOTAL = "totalHP";

    /** 影杀影子血量 — 当前影子血量（float，子键） */
    public static final String SHADOW_HP_CURRENT = "shadowHP";

    /** 影杀影子血量 — 数据过期时间（long, gameTime，子键） */
    public static final String SHADOW_HP_END_TIME = "endTime";

    // ==================== 禁疗之触觉醒 — 目标 persistentData ====================

    /** 禁疗之触觉醒易伤到期时间（long, gameTime） */
    public static final String HEALING_BLOCK_VULN_END = "AP_HealingBlock_Vuln_End";

    // ==================== 破敌之眼觉醒 — 目标 persistentData ====================

    /** 破敌之眼觉醒禁无敌帧到期时间（long, gameTime） */
    public static final String PIERCING_GAZE_NO_IFRAME_END = "AP_PiercingGaze_NoIframe_End";

    // ==================== 灵魂绑定 — 玩家 persistentData ====================

    /** 灵魂绑定保存的 Buff 列表（CompoundTag） */
    public static final String SOUL_BIND_BUFFS = "AP_SoulBind_Buffs";

    /** 灵魂绑定保存的经验数据（CompoundTag，含 level/progress/total） */
    public static final String SOUL_BIND_EXP = "AP_SoulBind_Exp";

    /** 灵魂绑定 Buff 列表子键（ListTag of MobEffectInstance） */
    public static final String SOUL_BIND_EFFECTS = "effects";

    /** 灵魂绑定经验 — 等级（int，子键） */
    public static final String SOUL_BIND_EXP_LEVEL = "level";

    /** 灵魂绑定经验 — 进度（float，子键） */
    public static final String SOUL_BIND_EXP_PROGRESS = "progress";

    /** 灵魂绑定经验 — 总经验（int，子键） */
    public static final String SOUL_BIND_EXP_TOTAL = "total";

    private PersistentDataKeys() {
        // 工具类，禁止实例化
    }
}