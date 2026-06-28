package com.ayin90723.adventure_power.capability;

import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.milestone.Milestone;
import net.minecraft.nbt.CompoundTag;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * IAdventureProgress 默认实现。
 * 不可持久化不变式：一旦 adventurer / fullyUnlocked 激活，永不回退。
 */
public class AdventureProgress implements IAdventureProgress {

    private static final String TAG_ADVENTURER = "adventurer";
    private static final String TAG_FULLY_UNLOCKED = "fullyUnlocked";
    private static final String TAG_MILESTONES = "milestones";
    private static final String TAG_DEATH_DEFY_INVUL_END = "deathDefyInvulEnd";
    private static final String TAG_DEATH_DEFY_COOLDOWN_END = "deathDefyCooldownEnd";
    private static final String TAG_RESILIENCE_STACKS = "resilienceStacks";
    private static final String TAG_LAST_HURT_TIME = "lastHurtTime";
    private static final String TAG_ACTIVE_SKILL_INDEX = "activeSkillIndex";
    private static final String TAG_JUDGMENT_COOLDOWN_END = "judgmentCooldownEnd";
    private static final String TAG_SANCTUARY_COOLDOWN_END = "sanctuaryCooldownEnd";
    private static final String TAG_SANCTUARY_INVUL_END = "sanctuaryInvulEnd";
    private static final String TAG_ACTIVE_SKILL_GCD_END = "activeSkillGcdEnd";
    private static final String TAG_BACKUP_HEALTH = "backupHealth";

    private boolean adventurer;
    private boolean fullyUnlocked;
    private final Set<Milestone> milestones = EnumSet.noneOf(Milestone.class);
    private static final String TAG_DISABLED_ABILITIES = "disabledAbilities";
    private final Set<String> disabledAbilities = new HashSet<>();
    private long deathDefyInvulEnd;
    private long deathDefyCooldownEnd;
    private int resilienceStacks;
    private long lastHurtTime;
    private int activeSkillIndex;
    private long judgmentCooldownEnd;
    private long sanctuaryCooldownEnd;
    private long sanctuaryInvulEnd;
    private long activeSkillGcdEnd;
    private float backupHealth;

    // ===== 激活状态 =====

    @Override
    public boolean isAdventurer() {
        return adventurer;
    }

    @Override
    public void activateAdventurer() {
        this.adventurer = true;
    }

    @Override
    public boolean isFullyUnlocked() {
        return fullyUnlocked;
    }

    @Override
    public void activateFullyUnlocked() {
        this.fullyUnlocked = true;
    }

    // ===== 里程碑 =====

    @Override
    public boolean isMilestoneUnlocked(Milestone m) {
        // fullyUnlocked 等价于全部里程碑已解锁
        if (fullyUnlocked) return true;
        return milestones.contains(m);
    }

    @Override
    public boolean unlockMilestone(Milestone m) {
        return milestones.add(m);
    }

    @Override
    public int getUnlockedMilestoneCount() {
        if (fullyUnlocked) return Milestone.values().length;
        return milestones.size();
    }

    @Override
    public boolean areAllMilestonesUnlocked() {
        return fullyUnlocked || milestones.size() >= Milestone.values().length;
    }

    // ===== 能力开关 =====

    @Override
    public Set<String> getDisabledAbilities() {
        return Collections.unmodifiableSet(new HashSet<>(disabledAbilities));
    }

    /**
     * 能力是否启用 — 双重门禁：玩家未手动关闭 + 已达成所需里程碑数。
     * <p>
     * 覆盖接口默认实现（仅检查 disabledAbilities），加入里程碑硬门禁。
     */
    @Override
    public boolean isAbilityEnabled(String id) {
        if (disabledAbilities.contains(id)) return false;
        Ability ability = AbilityRegistry.get(id);
        if (ability == null) return false;
        return getUnlockedMilestoneCount() >= ability.requiredMilestones();
    }

    @Override
    public boolean toggleAbility(String id) {
        if (disabledAbilities.contains(id)) {
            disabledAbilities.remove(id);
            return true; // 重新启用
        } else {
            disabledAbilities.add(id);
            return false; // 已禁用
        }
    }

    // ===== 死亡抗拒 =====

    @Override
    public long getDeathDefyInvulEnd() {
        return deathDefyInvulEnd;
    }

    @Override
    public void setDeathDefyInvulEnd(long time) {
        this.deathDefyInvulEnd = time;
    }

    @Override
    public long getDeathDefyCooldownEnd() {
        return deathDefyCooldownEnd;
    }

    @Override
    public void setDeathDefyCooldownEnd(long time) {
        this.deathDefyCooldownEnd = time;
    }

    // ===== 受击坚韧 =====

    @Override
    public int getResilienceStacks() {
        return resilienceStacks;
    }

    @Override
    public void setResilienceStacks(int stacks) {
        this.resilienceStacks = Math.max(0, stacks);
    }

    @Override
    public long getLastHurtTime() {
        return lastHurtTime;
    }

    @Override
    public void setLastHurtTime(long time) {
        this.lastHurtTime = time;
    }

    // ===== 真实血量备份 =====

    @Override
    public float getBackupHealth() {
        return backupHealth;
    }

    @Override
    public void setBackupHealth(float health) {
        this.backupHealth = health;
    }

    // ===== 主动技能 =====

    @Override
    public int getActiveSkillIndex() { return activeSkillIndex; }

    @Override
    public void setActiveSkillIndex(int index) { this.activeSkillIndex = index; }

    @Override
    public long getJudgmentCooldownEnd() { return judgmentCooldownEnd; }

    @Override
    public void setJudgmentCooldownEnd(long time) { this.judgmentCooldownEnd = time; }

    @Override
    public long getSanctuaryCooldownEnd() { return sanctuaryCooldownEnd; }

    @Override
    public void setSanctuaryCooldownEnd(long time) { this.sanctuaryCooldownEnd = time; }

    @Override
    public long getSanctuaryInvulEnd() { return sanctuaryInvulEnd; }

    @Override
    public void setSanctuaryInvulEnd(long time) { this.sanctuaryInvulEnd = time; }

    @Override
    public long getActiveSkillGcdEnd() { return activeSkillGcdEnd; }

    @Override
    public void setActiveSkillGcdEnd(long time) { this.activeSkillGcdEnd = time; }

    // ===== NBT 序列化 =====

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_ADVENTURER, adventurer);
        tag.putBoolean(TAG_FULLY_UNLOCKED, fullyUnlocked);

        CompoundTag milestonesTag = new CompoundTag();
        for (Milestone m : Milestone.values()) {
            milestonesTag.putBoolean(m.name().toLowerCase(), milestones.contains(m));
        }
        tag.put(TAG_MILESTONES, milestonesTag);

        CompoundTag disabledTag = new CompoundTag();
        for (String id : disabledAbilities) {
            disabledTag.putBoolean(id, true);
        }
        tag.put(TAG_DISABLED_ABILITIES, disabledTag);

        tag.putLong(TAG_DEATH_DEFY_INVUL_END, deathDefyInvulEnd);
        tag.putLong(TAG_DEATH_DEFY_COOLDOWN_END, deathDefyCooldownEnd);
        tag.putInt(TAG_RESILIENCE_STACKS, resilienceStacks);
        tag.putLong(TAG_LAST_HURT_TIME, lastHurtTime);
        // activeSkillIndex 不序列化 — 纯客户端 UI 状态，服务端同步时不应覆盖
        tag.putLong(TAG_JUDGMENT_COOLDOWN_END, judgmentCooldownEnd);
        tag.putLong(TAG_SANCTUARY_COOLDOWN_END, sanctuaryCooldownEnd);
        tag.putLong(TAG_SANCTUARY_INVUL_END, sanctuaryInvulEnd);
        tag.putLong(TAG_ACTIVE_SKILL_GCD_END, activeSkillGcdEnd);
        tag.putFloat(TAG_BACKUP_HEALTH, backupHealth);

        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        this.adventurer = nbt.getBoolean(TAG_ADVENTURER);
        this.fullyUnlocked = nbt.getBoolean(TAG_FULLY_UNLOCKED);

        this.milestones.clear();
        CompoundTag milestonesTag = nbt.getCompound(TAG_MILESTONES);
        for (Milestone m : Milestone.values()) {
            if (milestonesTag.getBoolean(m.name().toLowerCase())) {
                this.milestones.add(m);
            }
        }

        this.disabledAbilities.clear();
        CompoundTag disabledTag = nbt.getCompound(TAG_DISABLED_ABILITIES);
        for (String key : disabledTag.getAllKeys()) {
            if (disabledTag.getBoolean(key)) {
                this.disabledAbilities.add(key);
            }
        }

        this.deathDefyInvulEnd = nbt.getLong(TAG_DEATH_DEFY_INVUL_END);
        this.deathDefyCooldownEnd = nbt.getLong(TAG_DEATH_DEFY_COOLDOWN_END);
        this.resilienceStacks = nbt.getInt(TAG_RESILIENCE_STACKS);
        this.lastHurtTime = nbt.getLong(TAG_LAST_HURT_TIME);
        // activeSkillIndex 不反序列化 — 纯客户端 UI 状态
        this.judgmentCooldownEnd = nbt.getLong(TAG_JUDGMENT_COOLDOWN_END);
        this.sanctuaryCooldownEnd = nbt.getLong(TAG_SANCTUARY_COOLDOWN_END);
        this.sanctuaryInvulEnd = nbt.getLong(TAG_SANCTUARY_INVUL_END);
        this.activeSkillGcdEnd = nbt.getLong(TAG_ACTIVE_SKILL_GCD_END);
        this.backupHealth = nbt.getFloat(TAG_BACKUP_HEALTH);
    }
}
