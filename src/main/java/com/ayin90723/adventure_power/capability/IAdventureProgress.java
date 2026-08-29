package com.ayin90723.adventure_power.capability;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;

import java.util.Set;

/**
 * 冒险进度 Capability 接口。
 * 3 层状态：激活层 → 里程碑层 → 查询层。
 * 里程碑存储使用字符串 ID，由 MilestoneRegistry 动态定义。
 */
@AutoRegisterCapability
public interface IAdventureProgress {

    // ===== 激活状态 =====

    boolean isAdventurer();
    void activateAdventurer();
    boolean isFullyUnlocked();
    void activateFullyUnlocked();

    // ===== 里程碑（动态加载，存储为字符串 ID） =====

    boolean isMilestoneUnlocked(String id);
    boolean unlockMilestone(String id);
    int getUnlockedMilestoneCount();
    boolean areAllMilestonesUnlocked();
    /**
     * 已解锁里程碑 ID 原始集合（含当前注册表外的死 ID——/reload 缩水窗口期保留下来的
     * 解锁记录，审查修 P2#1）。物品 NBT 第三层备份回写需要它：只按注册表写会把死 ID
     * 覆写成 false，注册表恢复后进度永久丢失。
     */
    Set<String> getUnlockedMilestoneIds();

    // ===== 能力开关 =====

    Set<String> getDisabledAbilities();
    default boolean isAbilityEnabled(String id) { return !getDisabledAbilities().contains(id); }
    /** 该能力是否属于某已解锁里程碑（仅里程碑归属，不检查手动开关） */
    boolean isAbilityUnlocked(String id);
    boolean toggleAbility(String id);

    // ===== 指令后门解锁的被禁用能力（/ap unlock ability） =====

    /** 通过指令解锁的被禁用能力 ID 集合（NBT 持久化，per-player） */
    Set<String> getCommandGrantedAbilities();
    boolean isCommandGranted(String id);
    /** 指令解锁一个被禁用能力，返回是否新增 */
    boolean grantAbilityByCommand(String id);
    /** 指令解锁该能力时的已解锁里程碑数（成长基准：解锁后数值=基础值，之后随进度正常成长） */
    int getCommandGrantedAtCount(String id);

    /** 从物品 NBT 兜底恢复指令解锁记录（默认空实现——仅 AdventureProgress 需要） */
    default void setCommandGrantedAtCount(String id, int count) {}

    // ===== 死亡抗拒 =====

    long getDeathDefyInvulEnd();
    void setDeathDefyInvulEnd(long time);
    long getDeathDefyCooldownEnd();
    void setDeathDefyCooldownEnd(long time);

    // ===== 真实血量 =====

    float getBackupHealth();
    void setBackupHealth(float health);

    // ===== 受击坚韧 =====

    int getResilienceStacks();
    void setResilienceStacks(int stacks);
    long getLastHurtTime();
    void setLastHurtTime(long time);

    // ===== 翱翔飞行追踪 =====

    boolean isSoarGrantedFlight();
    void setSoarGrantedFlight(boolean granted);

    // ===== 主动技能 =====

    int getActiveSkillIndex();
    void setActiveSkillIndex(int index);
    long getJudgmentCooldownEnd();
    void setJudgmentCooldownEnd(long time);
    long getSanctuaryCooldownEnd();
    void setSanctuaryCooldownEnd(long time);
    long getSanctuaryInvulEnd();
    void setSanctuaryInvulEnd(long time);
    long getActiveSkillGcdEnd();
    void setActiveSkillGcdEnd(long time);

    // ===== 无敌状态判定 =====

    default boolean isDeathDefyInvulnerable(long currentGameTime) {
        long end = getDeathDefyInvulEnd();
        return end > 0 && currentGameTime < end;
    }

    default boolean isSanctuaryInvulnerable(long currentGameTime) {
        long end = getSanctuaryInvulEnd();
        return end > 0 && currentGameTime < end;
    }

    // ===== NBT =====

    CompoundTag serializeNBT();
    void deserializeNBT(CompoundTag nbt);
}
