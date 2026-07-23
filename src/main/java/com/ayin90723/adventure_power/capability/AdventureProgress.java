package com.ayin90723.adventure_power.capability;

import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import net.minecraft.nbt.CompoundTag;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * IAdventureProgress 默认实现。
 * 不可持久化不变式：一旦 adventurer / fullyUnlocked 激活，永不回退。
 * 里程碑使用字符串 ID 存储，由 MilestoneRegistry 动态定义。
 */
public class AdventureProgress implements IAdventureProgress {

    private static final String TAG_ADVENTURER = "adventurer";
    private static final String TAG_FULLY_UNLOCKED = "fullyUnlocked";
    private static final String TAG_MILESTONES = "milestones";
    private static final String TAG_DEATH_DEFY_INVUL_END = "deathDefyInvulEnd";
    private static final String TAG_DEATH_DEFY_COOLDOWN_END = "deathDefyCooldownEnd";
    private static final String TAG_RESILIENCE_STACKS = "resilienceStacks";
    private static final String TAG_LAST_HURT_TIME = "lastHurtTime";
    private static final String TAG_SOAR_GRANTED_FLIGHT = "soarGrantedFlight";
    private static final String TAG_ACTIVE_SKILL_INDEX = "activeSkillIndex";
    private static final String TAG_JUDGMENT_COOLDOWN_END = "judgmentCooldownEnd";
    private static final String TAG_SANCTUARY_COOLDOWN_END = "sanctuaryCooldownEnd";
    private static final String TAG_SANCTUARY_INVUL_END = "sanctuaryInvulEnd";
    private static final String TAG_ACTIVE_SKILL_GCD_END = "activeSkillGcdEnd";
    private static final String TAG_BACKUP_HEALTH = "backupHealth";
    private static final String TAG_DISABLED_ABILITIES = "disabledAbilities";

    private boolean adventurer;
    private boolean fullyUnlocked;
    private final Set<String> unlockedMilestones = new HashSet<>();
    private final Set<String> disabledAbilities = new HashSet<>();

    /**
     * 缓存当前已解锁里程碑对应的所有可用能力 ID。
     * 由 rebuildAbilityCache() 维护，isAbilityEnabled() 直接 O(1) 查询。
     * 仅限主线程访问（Forge Capability 生命周期约束）。
     */
    private final Set<String> enabledAbilityCache = new HashSet<>();

    /**
     * MilestoneRegistry.getAll() 返回列表的 identityHashCode 快照。
     * 用于在 isAbilityEnabled() 中检测 /reload 热更新导致的注册表变更，
     * 若不一致则触发惰性重建，保证缓存不因数据包重载而变脏。
     */
    private int cachedRegistryHash;
    private long deathDefyInvulEnd;
    private long deathDefyCooldownEnd;
    private int resilienceStacks;
    private long lastHurtTime;
    private int activeSkillIndex;
    private boolean soarGrantedFlight;
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
        this.adventurer = true;  // 全解锁蕴含冒险者，防止 fullyUnlocked=true / adventurer=false 不一致
        rebuildAbilityCache();
    }

    // ===== 里程碑 =====

    @Override
    public boolean isMilestoneUnlocked(String id) {
        if (fullyUnlocked) return true;
        return unlockedMilestones.contains(id);
    }

    @Override
    public boolean unlockMilestone(String id) {
        boolean added = unlockedMilestones.add(id);
        if (added) rebuildAbilityCache();
        return added;
    }

    @Override
    public int getUnlockedMilestoneCount() {
        if (fullyUnlocked) return MilestoneRegistry.getMilestoneCount();
        return unlockedMilestones.size();
    }

    @Override
    public boolean areAllMilestonesUnlocked() {
        return fullyUnlocked || unlockedMilestones.size() >= MilestoneRegistry.getMilestoneCount();
    }

    // ===== 能力开关 =====

    @Override
    public Set<String> getDisabledAbilities() {
        return Collections.unmodifiableSet(new HashSet<>(disabledAbilities));
    }

    // ===== 能力可用性缓存 =====

    /**
     * 重建能力可用性缓存，从当前已解锁里程碑中收集所有可用能力 ID。
     * fullyUnlocked 时取所有里程碑的 abilities，否则只取已解锁的。
     * 同时记录 MilestoneRegistry 列表的 identityHashCode，用于 /reload 检测。
     * <p>
     * 幂等 —— 调用时机：unlockMilestone / activateFullyUnlocked / deserializeNBT 末尾，
     * 以及 isAbilityEnabled 检测到注册表版本变更时的惰性重建。
     */
    private void rebuildAbilityCache() {
        enabledAbilityCache.clear();
        cachedRegistryHash = System.identityHashCode(MilestoneRegistry.getAll());
        if (fullyUnlocked) {
            for (Milestone m : MilestoneRegistry.getAll()) {
                enabledAbilityCache.addAll(m.abilities());
            }
        } else {
            for (String milestoneId : unlockedMilestones) {
                Milestone m = MilestoneRegistry.getById(milestoneId);
                if (m != null) {
                    enabledAbilityCache.addAll(m.abilities());
                }
            }
        }
    }

    /**
     * 能力是否启用 — 双重门禁：玩家未手动关闭 + 已达成所需里程碑数。
     * 覆盖接口默认实现（仅检查 disabledAbilities），加入里程碑硬门禁。
     * <p>
     * 使用 enabledAbilityCache O(1) 查询替代旧版线性遍历所有里程碑。
     * 当 MilestoneRegistry 列表对象变更（/reload 热更新）时惰性重建缓存。
     */
    @Override
    public boolean isAbilityEnabled(String id) {
        if (disabledAbilities.contains(id)) return false;
        if (AbilityRegistry.get(id) == null) return false;
        if (cachedRegistryHash != System.identityHashCode(MilestoneRegistry.getAll())) {
            rebuildAbilityCache();
        }
        return enabledAbilityCache.contains(id);
    }

    @Override
    public boolean toggleAbility(String id) {
        if (disabledAbilities.contains(id)) {
            disabledAbilities.remove(id);
            return true;
        } else {
            disabledAbilities.add(id);
            return false;
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

    // ===== 翱翔飞行追踪 =====

    @Override
    public boolean isSoarGrantedFlight() { return soarGrantedFlight; }

    @Override
    public void setSoarGrantedFlight(boolean granted) { this.soarGrantedFlight = granted; }

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
        for (String id : unlockedMilestones) {
            milestonesTag.putBoolean(id, true);
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
        tag.putLong(TAG_JUDGMENT_COOLDOWN_END, judgmentCooldownEnd);
        tag.putLong(TAG_SANCTUARY_COOLDOWN_END, sanctuaryCooldownEnd);
        tag.putLong(TAG_SANCTUARY_INVUL_END, sanctuaryInvulEnd);
        tag.putLong(TAG_ACTIVE_SKILL_GCD_END, activeSkillGcdEnd);
        tag.putInt(TAG_ACTIVE_SKILL_INDEX, activeSkillIndex);
        tag.putBoolean(TAG_SOAR_GRANTED_FLIGHT, soarGrantedFlight);
        tag.putFloat(TAG_BACKUP_HEALTH, backupHealth);

        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        this.adventurer = nbt.getBoolean(TAG_ADVENTURER);
        this.fullyUnlocked = nbt.getBoolean(TAG_FULLY_UNLOCKED);

        this.unlockedMilestones.clear();
        CompoundTag milestonesTag = nbt.getCompound(TAG_MILESTONES);
        for (String key : milestonesTag.getAllKeys()) {
            if (milestonesTag.getBoolean(key) && MilestoneRegistry.contains(key)) {
                this.unlockedMilestones.add(key);
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
        this.judgmentCooldownEnd = nbt.getLong(TAG_JUDGMENT_COOLDOWN_END);
        this.sanctuaryCooldownEnd = nbt.getLong(TAG_SANCTUARY_COOLDOWN_END);
        this.sanctuaryInvulEnd = nbt.getLong(TAG_SANCTUARY_INVUL_END);
        this.activeSkillGcdEnd = nbt.getLong(TAG_ACTIVE_SKILL_GCD_END);
        this.activeSkillIndex = nbt.getInt(TAG_ACTIVE_SKILL_INDEX);
        this.soarGrantedFlight = nbt.getBoolean(TAG_SOAR_GRANTED_FLIGHT);
        this.backupHealth = nbt.getFloat(TAG_BACKUP_HEALTH);
        rebuildAbilityCache();
    }
}
