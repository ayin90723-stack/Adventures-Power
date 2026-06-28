package com.rosycentury.adventure_power.capability;

import com.rosycentury.adventure_power.milestone.Milestone;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;

import java.util.Set;

/**
 * 冒险进度 Capability 接口。
 * 3 层状态：激活层 → 里程碑层 → 查询层。
 * 18 种能力开关 + 主动技能/死亡抗拒 计时器 + 真实血量备份。
 */
@AutoRegisterCapability
public interface IAdventureProgress {

    // ===== 激活状态 =====

    boolean isAdventurer();
    void activateAdventurer();
    boolean isFullyUnlocked();
    void activateFullyUnlocked();

    // ===== 里程碑（10 个） =====

    boolean isMilestoneUnlocked(Milestone m);
    boolean unlockMilestone(Milestone m);
    int getUnlockedMilestoneCount();
    boolean areAllMilestonesUnlocked();

    // ===== 能力开关 =====

    Set<String> getDisabledAbilities();
    default boolean isAbilityEnabled(String id) { return !getDisabledAbilities().contains(id); }
    boolean toggleAbility(String id);

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

    // ===== 无敌状态判定（DeathDefyMixin / 主动技能 等） =====

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
