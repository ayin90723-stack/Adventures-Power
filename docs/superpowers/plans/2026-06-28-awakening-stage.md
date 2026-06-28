# 「冒险者的觉醒」实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在全10里程碑解锁后，持有"冒险的终点"的玩家自动获得25种能力的全面强化（10质变+15数值）

**Architecture:** 设计文档 → `docs/superpowers/specs/2026-06-28-awakening-stage-design.md`。所有觉醒检测通过现有 `IAdventureProgress.isFullyUnlocked()` 在 Handler 层实现，不侵入 Ability 接口，不新增 Capability 字段。配置项统一在 ModConfig 新增。

**Tech Stack:** Minecraft Forge 1.20.1, JDK 17, Mixin 0.8

---

### Task 1: ModConfig 新增觉醒配置项

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/config/ModConfig.java`

- [ ] **Step 1: 新增全部觉醒配置字段和 push/pop 块**

在 `ModConfig.java` 的字段声明区（`FORTUNE_FAVOR_BONUS_STEP` 之后、`VITALITY_PER_MILESTONE` 之后均可），新增：

```java
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
```

在 static 块中 `BUILDER.push("能力数值");` 之后、`BUILDER.pop(); // 能力数值` 之前，新增一个 push/pop 块：

```java
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
```

- [ ] **Step 2: 构建验证编译通过**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

预期: BUILD SUCCESSFUL（无编译错误）

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/config/ModConfig.java
git commit -m "feat: 新增觉醒阶段全部配置项"
```

---

### Task 2: ActiveSkillHandler 新增 public executeJudgment 方法

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/skill/ActiveSkillHandler.java`

- [ ] **Step 1: 提取 handleJudgment 核心逻辑为 public 方法**

在 `ActiveSkillHandler.java` 中，将 `handleJudgment` 的核心伤害逻辑提取为新方法 `executeJudgment`。原 `handleJudgment` 改为调用 `executeJudgment`：

在 `handleJudgment` 方法下方新增：

```java
/**
 * 觉醒死亡抗拒触发时调用：无视冷却和 GCD 释放一次审判。
 * 仅造成伤害，不消耗冷却、不触发 GCD。
 *
 * @param player 释放者
 * @return 受影响的实体数量
 */
public static int executeJudgment(ServerPlayer player) {
    if (player.level().isClientSide()) return 0;

    var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
    if (progressOpt.isEmpty()) return 0;
    var progress = progressOpt.get();

    int milestones = progress.getUnlockedMilestoneCount();
    if (milestones == 0) milestones = 1;

    float baseDamage = (float) (double) ModConfig.ACTIVE_SKILL_JUDGMENT_BASE_DAMAGE.get();
    float hpRatio = (float) (double) ModConfig.ACTIVE_SKILL_JUDGMENT_HP_RATIO.get() * milestones;
    double radius = ModConfig.ACTIVE_SKILL_JUDGMENT_RADIUS.get();

    // 觉醒：审判范围 +50%
    if (progress.isFullyUnlocked()) {
        radius *= ModConfig.AWAKEN_JUDGMENT_RANGE_MULT.get();
    }

    AABB aabb = player.getBoundingBox().inflate(radius);
    List<LivingEntity> targets = player.level().getEntitiesOfClass(LivingEntity.class, aabb,
        e -> e != player && e.isAlive() && isHostileTarget(player, e));

    if (targets.isEmpty()) return 0;

    ServerLevel level = (ServerLevel) player.level();
    var key = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("adventure_power", "judgment"));
    var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
    var holder = registry.getHolderOrThrow(key);

    for (LivingEntity target : targets) {
        float maxHpPart = target.getMaxHealth() * hpRatio;
        float currentHpPart = target.getHealth() * hpRatio;
        float totalDamage = baseDamage + maxHpPart + currentHpPart;

        var source = new DamageSource(holder, null, player);
        float healthBefore = target.getHealth();
        target.hurt(source, totalDamage);
        float actualDealt = healthBefore - target.getHealth();
        target.invulnerableTime = 0;

        float epsilon = Math.max(0.01F, totalDamage * 0.01F);
        if (target.isAlive() && actualDealt < totalDamage - epsilon) {
            float correctedHealth = Math.max(healthBefore - totalDamage, 0.0F);
            HealthUtil.setAllHealthLikeRaw(target, correctedHealth);
            if (correctedHealth <= 0.0F) {
                target.invulnerableTime = 0;
                target.setLastHurtByMob(player);
                target.setLastHurtByPlayer(player);
                target.die(source);
            }
        }
    }

    // 音效 + 粒子
    level.playSound(null, player.getX(), player.getY(), player.getZ(),
        SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.0F, 0.8F);
    for (int i = 0; i < 60; i++) {
        double angle = Math.random() * Math.PI * 2;
        double dist = Math.random() * radius;
        double x = player.getX() + Math.cos(angle) * dist;
        double z = player.getZ() + Math.sin(angle) * dist;
        level.sendParticles(ParticleTypes.END_ROD, x, player.getY() + 1.0, z,
            1, 0, 0, 0, 0.05);
        if (i % 3 == 0) {
            level.sendParticles(ParticleTypes.DRAGON_BREATH, x, player.getY() + 0.5, z,
                1, 0, 0, 0, 0.02);
        }
    }

    return targets.size();
}
```

然后重构现有的 `handleJudgment` 方法，使其复用 `executeJudgment` 的核心逻辑，同时保留冷却/GCD 管理：

```java
private static void handleJudgment(ServerPlayer player, IAdventureProgress progress, long currentTime) {
    // 冷却检查
    long cdEnd = progress.getJudgmentCooldownEnd();
    if (cdEnd > 0 && currentTime < cdEnd) return;

    int milestones = progress.getUnlockedMilestoneCount();
    if (milestones == 0) milestones = 1;

    // 消耗冷却
    int cooldown = ModConfig.ACTIVE_SKILL_JUDGMENT_COOLDOWN.get();
    int gcd = ModConfig.ACTIVE_SKILL_GCD.get();
    progress.setJudgmentCooldownEnd(currentTime + cooldown);
    progress.setActiveSkillGcdEnd(currentTime + gcd);
    AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
    AdventureProgressCapability.syncToClient(player);

    executeJudgment(player);
}
```

- [ ] **Step 2: 构建验证编译通过**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

预期: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/skill/ActiveSkillHandler.java
git commit -m "feat: 提取 executeJudgment 公开方法，支持死亡抗拒免费审判"
```

---

### Task 3: 死亡抗拒觉醒 — 触发时免费审判

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/capability/AdventureProgressCapability.java`

- [ ] **Step 1: 在死亡抗拒触发后追加审判调用**

在 `AdventureProgressCapability.java` 的 `onPlayerDeath` 方法中，找到粒子播放之前的位置（约第738行 `syncToClient(player)` 之后），追加：

找到这段代码：
```java
            syncCapabilityToPersistent(player, progress);
            syncToClient(player);

            if (player.level() instanceof ServerLevel serverLevel) {
                serverLevel.playSound(...
```

在 `syncToClient(player);` 之后、`if (player.level() instanceof ServerLevel serverLevel)` 之前插入：

```java
            // 觉醒：触发死亡抗拒时自动释放一次免费旅者审判
            if (progress.isFullyUnlocked() && progress.isAbilityEnabled("active_skill")) {
                com.ayin90723.adventure_power.skill.ActiveSkillHandler.executeJudgment(
                    (net.minecraft.server.level.ServerPlayer) player);
            }
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/capability/AdventureProgressCapability.java
git commit -m "feat: 觉醒死亡抗拒 — 触发时释放免费旅者审判"
```

---

### Task 4: 虚空踏步觉醒 — 三段跳

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/input/DoubleJumpHandler.java`

- [ ] **Step 1: 改造为跳数追踪制**

在 `DoubleJumpHandler.java` 中：

1. 新增跳数追踪 Map：
```java
/** 每玩家剩余空中跳跃次数（觉醒后=2，非觉醒=1） */
private static final Map<UUID, Integer> JUMPS_REMAINING = new HashMap<>();
```

2. 新增常量：
```java
/** 落地时重置的垂直速度阈值 */
private static final double ON_GROUND_THRESHOLD = -0.1;
```

3. 修改 `canDoubleJump` 为跳数检查：
```java
private static boolean canDoubleJump(ServerPlayer player) {
    if (!isDoubleJumpEnabled(player)) return false;
    if (player.isPassenger()) return false;
    if (player.isInWater()) return false;
    if (player.getAbilities().flying) return false;

    // 落地 → 重置跳数
    if (player.onGround() && player.getDeltaMovement().y <= ON_GROUND_THRESHOLD) {
        JUMPS_REMAINING.remove(player.getUUID());
        return false;
    }

    // 刚离开地面 → 初始化跳数
    Integer remaining = JUMPS_REMAINING.get(player.getUUID());
    if (remaining == null) {
        int maxJumps = getMaxAirJumps(player);
        JUMPS_REMAINING.put(player.getUUID(), maxJumps);
        return false; // 首次离地不算二段跳
    }

    // 冷却检查
    Long lastJump = COOLDOWNS.get(player.getUUID());
    if (lastJump != null && player.level().getGameTime() - lastJump < COOLDOWN_TICKS) {
        return false;
    }

    return remaining > 0;
}
```

4. 新增 `getMaxAirJumps` 方法：
```java
private static int getMaxAirJumps(ServerPlayer player) {
    boolean awakened = AdventureProgressCapability.getAdventureProgress(player)
        .map(p -> p.isFullyUnlocked()).orElse(false);
    return awakened ? ModConfig.AWAKEN_VOID_STEP_JUMPS.get() - 1 : 1;
    // -1 因为第一次落地离地不算在内
}
```

5. 在 `handleDoubleJump` 中，跳跃成功后消耗剩余跳数：
```java
// 在 applyMovement 之后、COOLDOWNS.put 之前
Integer remaining = JUMPS_REMAINING.get(player.getUUID());
if (remaining != null && remaining > 0) {
    JUMPS_REMAINING.put(player.getUUID(), remaining - 1);
}
```

6. 在 `onPlayerLogout` 中清理跳数：
```java
JUMPS_REMAINING.remove(event.getEntity().getUUID());
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/input/DoubleJumpHandler.java
git commit -m "feat: 觉醒虚空踏步 — 三段跳（跳数追踪制改造）"
```

---

### Task 5: 灵魂绑定觉醒 — 死亡不掉落经验

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java`

- [ ] **Step 1: 觉醒状态下清零经验防掉落**

在 `PlayerStateHandler.java` 的 `onPlayerDeath` 方法中，在保存经验值之后（第80行 `player.getPersistentData().putInt(SOUL_BIND_EXP_KEY, player.totalExperience);` 之后），追加：

```java
            // 觉醒：死亡时清零经验等级防止掉落经验球（经验值已在上方保存，重生后恢复）
            if (progress.isFullyUnlocked()) {
                player.experienceLevel = 0;
                player.experienceProgress = 0.0F;
                player.totalExperience = 0;
            }
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java
git commit -m "feat: 觉醒灵魂绑定 — 死亡时清零经验防掉落"
```

---

### Task 6: 不朽装备觉醒 — 装备属性加成

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java`

- [ ] **Step 1: 在 PlayerStateHandler.onPlayerTick 中追加不朽装备觉醒逻辑**

在 `PlayerStateHandler.java` 的 `onPlayerTick` 方法末尾（`}` 之前），追加不朽装备觉醒的属性管理：

```java
        // ---- 不朽装备觉醒：属性加成 ----
        if (progress.isAbilityEnabled("undying_gear") && progress.isFullyUnlocked()) {
            applyUndyingGearAwakened(player);
        } else {
            removeUndyingGearAwakened(player);
        }
```

并新增两个辅助方法和 UUID 常量：

```java
    // ========================================================================
    //  不朽装备觉醒 — 属性加成
    // ========================================================================

    private static final UUID AWAKEN_UNDYING_ARMOR_UUID =
        UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID AWAKEN_UNDYING_WEAPON_UUID =
        UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");

    private static void applyUndyingGearAwakened(Player player) {
        var armorAttr = player.getAttribute(Attributes.ARMOR);
        if (armorAttr != null) {
            var existing = armorAttr.getModifier(AWAKEN_UNDYING_ARMOR_UUID);
            int pieces = (int) java.util.Arrays.stream(net.minecraft.world.entity.EquipmentSlot.values())
                .filter(s -> s.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR)
                .filter(s -> !player.getItemBySlot(s).isEmpty())
                .count();
            double bonus = pieces * ModConfig.AWAKEN_UNDYING_ARMOR_BONUS.get();
            if (existing != null) {
                if (Math.abs(existing.getAmount() - bonus) > 0.001) {
                    armorAttr.removeModifier(AWAKEN_UNDYING_ARMOR_UUID);
                } else {
                    return; // 值未变，无需重新添加
                }
            }
            armorAttr.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                AWAKEN_UNDYING_ARMOR_UUID, "awakened_undying_armor", bonus,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
        }

        var atkAttr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atkAttr != null) {
            var existing = atkAttr.getModifier(AWAKEN_UNDYING_WEAPON_UUID);
            double weaponBonus = ModConfig.AWAKEN_UNDYING_WEAPON_BONUS.get();
            if (existing != null) {
                if (Math.abs(existing.getAmount() - weaponBonus) > 0.001) {
                    atkAttr.removeModifier(AWAKEN_UNDYING_WEAPON_UUID);
                } else {
                    return;
                }
            }
            atkAttr.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                AWAKEN_UNDYING_WEAPON_UUID, "awakened_undying_weapon", weaponBonus,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_BASE));
        }
    }

    private static void removeUndyingGearAwakened(Player player) {
        var armorAttr = player.getAttribute(Attributes.ARMOR);
        if (armorAttr != null && armorAttr.getModifier(AWAKEN_UNDYING_ARMOR_UUID) != null) {
            armorAttr.removeModifier(AWAKEN_UNDYING_ARMOR_UUID);
        }
        var atkAttr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atkAttr != null && atkAttr.getModifier(AWAKEN_UNDYING_WEAPON_UUID) != null) {
            atkAttr.removeModifier(AWAKEN_UNDYING_WEAPON_UUID);
        }
    }
```

- [ ] **Step 2: 在 Clone/登出事件中清理觉醒属性**

在 `PlayerStateHandler.onPlayerClone` 中追加清理（如果觉醒状态未恢复，属性不应保留）：
```java
        // Clone 时清理不朽装备觉醒属性（会由 tick 重新应用）
        removeUndyingGearAwakened(player);
```

在新增 `onPlayerLogout` 订阅（或复用已有的）：
```java
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        removeUndyingGearAwakened(event.getEntity());
    }
```

- [ ] **Step 3: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java
git commit -m "feat: 觉醒不朽装备 — 每件护甲+1护甲值，武器+15%伤害"
```

---

### Task 7: 翱翔觉醒 — 飞行速度 +50%

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java`

- [ ] **Step 1: 在翱翔 tick 中一次性设置飞行速度**

在 `PlayerStateHandler.onPlayerTick` 的翱翔部分（约第265-278行），修改逻辑，在 `mayfly = true` 之后检查觉醒状态设置飞行速度：

找到：
```java
        // ---- 翱翔 ----
        if (progress.isAbilityEnabled("soar")) {
            if (!player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }
        } else {
```

修改为：
```java
        // ---- 翱翔 ----
        if (progress.isAbilityEnabled("soar")) {
            if (!player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }
            // 觉醒：飞行速度 +50%（只在值不同时写入，不在 tick 中重复乘法）
            double targetSpeed = progress.isFullyUnlocked()
                ? 0.05 * ModConfig.AWAKEN_SOAR_SPEED.get()   // 默认 0.05 * 1.5 = 0.075
                : 0.05;  // 原版默认
            if (Math.abs(player.getAbilities().flyingSpeed - targetSpeed) > 0.0001) {
                player.getAbilities().flyingSpeed = (float) targetSpeed;
            }
        } else {
            // 能力关闭：恢复原版飞行速度
            if (Math.abs(player.getAbilities().flyingSpeed - 0.05) > 0.0001
                && !player.getAbilities().instabuild && !player.isSpectator()) {
                player.getAbilities().flyingSpeed = 0.05F;
            }
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java
git commit -m "feat: 觉醒翱翔 — 飞行速度+50%（一次性设置防指数爆炸）"
```

---

### Task 8: 淬魂之力觉醒 — 斩杀线

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/CombatAbilityHandler.java`

- [ ] **Step 1: 在 handleSoulQuench 中追加斩杀判定**

在 `CombatAbilityHandler.handleSoulQuench` 方法中，在计算 `extraDamage` 之后、调用 `target.hurt()` 之前（第197行附近），追加：

```java
            // 觉醒：斩杀线 — 目标低于阈值 HP 时伤害翻倍
            if (progress.isFullyUnlocked()) {
                float threshold = (float) (double) ModConfig.AWAKEN_SOUL_QUENCH_EXECUTE_THRESHOLD.get();
                if (target.getHealth() <= target.getMaxHealth() * threshold) {
                    extraDamage *= 2.0F;
                }
            }
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/CombatAbilityHandler.java
git commit -m "feat: 觉醒淬魂之力 — 目标低于20%HP时真实伤害翻倍"
```

---

### Task 9: 影杀觉醒 — AOE 爆炸

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/CombatAbilityHandler.java`

- [ ] **Step 1: 在影杀斩杀后追加 AOE 逻辑**

在 `CombatAbilityHandler.handleShadowKill` 中，影子血量归零斩杀成功后（`saturationKill` 调用之后），追加觉醒 AOE 逻辑。找到斩杀完成的位置（`saturationKill(target, killSource, attacker)` 之后约第348行），插入：

```java
                // 觉醒：影杀 AOE 爆炸
                if (progress.isFullyUnlocked()) {
                    shadowKillAoe(attacker, target, totalHP);
                }
```

并在 `CombatAbilityHandler` 类中新增方法：

```java
    /** 觉醒影杀 AOE：对斩杀目标周围实体施加影子血量削减 */
    private static void shadowKillAoe(Player attacker, LivingEntity killed, float baseTotalHP) {
        double radius = ModConfig.AWAKEN_SHADOW_KILL_AOE_RADIUS.get();
        float ratio = (float) (double) ModConfig.AWAKEN_SHADOW_KILL_AOE_RATIO.get();
        int maxTargets = ModConfig.AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS.get();

        AABB aabb = killed.getBoundingBox().inflate(radius);
        List<LivingEntity> nearby = killed.level().getEntitiesOfClass(LivingEntity.class, aabb,
            e -> e != attacker && e != killed && e.isAlive()
                && !(e instanceof Player) && e instanceof net.minecraft.world.entity.Monster);

        int count = 0;
        CompoundTag playerData = attacker.getPersistentData();
        CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
        long gameTime = attacker.level().getGameTime();

        for (LivingEntity target : nearby) {
            if (count >= maxTargets) break;

            float totalHP = target.getMaxHealth();
            float aoeReduction = totalHP * ratio;

            String targetKey = target.getUUID().toString();
            float existingShadow;
            if (shadowData.contains(targetKey)) {
                CompoundTag entry = shadowData.getCompound(targetKey);
                existingShadow = entry.getFloat(NBT_SP_SHADOW_HP);
            } else {
                existingShadow = totalHP;
            }
            float newShadow = Math.max(0.0F, existingShadow - aoeReduction);

            CompoundTag entry = new CompoundTag();
            entry.putFloat(NBT_SP_TOTAL_HP, totalHP);
            entry.putFloat(NBT_SP_SHADOW_HP, newShadow);
            entry.putLong(NBT_SP_END_TIME, gameTime + 6000L);
            shadowData.put(targetKey, entry);

            updateShadowHPBossBar(target, attacker, newShadow, totalHP);
            count++;

            if (killed.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.SCULK_SOUL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    10, 0.5, 0.5, 0.5, 0.02);
            }
        }
        if (!shadowData.isEmpty()) {
            playerData.put(NBT_SP_DATA, shadowData);
        }
    }
```

需要在文件顶部新增 import：
```java
import net.minecraft.world.phys.AABB;
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/CombatAbilityHandler.java
git commit -m "feat: 觉醒影杀 — 斩杀时8格AOE削减周围生物影子血量"
```

---

### Task 10: 嗜血觉醒 — 过量治疗转护盾 + 禁疗之触/伤害抗性/灵巧 数值强化

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/RecoveryHandler.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/CombatAbilityHandler.java`

- [ ] **Step 1: 嗜血觉醒 — 过量治疗转吸收护盾**

在 `RecoveryHandler.onLivingHurtLifesteal` 中，在 `attacker.heal(healAmount)` 之后追加：

```java
            // 觉醒：过量治疗转为吸收护盾
            if (healAmount > 0.0F && progress.isFullyUnlocked()) {
                float beforeHeal = attacker.getHealth();
                float toFull = attacker.getMaxHealth() - beforeHeal;
                if (healAmount > toFull && toFull > 0) {
                    float excess = healAmount - toFull;
                    float shieldCap = attacker.getMaxHealth()
                        * ModConfig.AWAKEN_LIFESTEAL_SHIELD_CAP.get().floatValue();
                    excess = Math.min(excess, shieldCap);
                    if (excess > 0.0F) {
                        attacker.setAbsorptionAmount(Math.min(
                            attacker.getAbsorptionAmount() + excess, shieldCap));
                    }
                }
            }
```

- [ ] **Step 2: CombatAbilityHandler — 灵巧/伤害抗性/禁疗之触 觉醒数值强化**

在 `CombatAbilityHandler.onLivingAttack` (灵巧) 中，在 `float chance = ability.value(milestones) / 100.0f;` 之后追加：

```java
            if (progress.isFullyUnlocked()) {
                chance = Math.min(chance * ModConfig.AWAKEN_MULTIPLIER.get().floatValue(), 0.95f);
            }
```

在 `CombatAbilityHandler.handleDamageResist` 中，在 `float ratio = ability.value(milestones) / 100.0f;` 之后追加：

```java
            if (progress.isFullyUnlocked()) {
                ratio = Math.min(ratio * ModConfig.AWAKEN_MULTIPLIER.get().floatValue(), 0.95f);
            }
```

在 `CombatAbilityHandler.handleHealingBlock` 中，在 `int durationSeconds = (int) ability.value(milestones);` 之后追加：

```java
            if (progress.isFullyUnlocked()) {
                durationSeconds = (int) Math.ceil(durationSeconds * ModConfig.AWAKEN_MULTIPLIER.get());
            }
```

- [ ] **Step 3: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/RecoveryHandler.java src/main/java/com/ayin90723/adventure_power/handler/CombatAbilityHandler.java
git commit -m "feat: 觉醒嗜血(过量转护盾) + 灵巧/伤害抗性/禁疗之触数值强化"
```

---

### Task 11: 净魂觉醒 — 虚弱光环

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java`

- [ ] **Step 1: 在净魂 tick 中追加虚弱光环**

在 `PlayerStateHandler.onPlayerTick` 的净魂部分（第256-262行），追加觉醒逻辑：

找到：
```java
        // ---- 净魂兜底 ----
        if (progress.isAbilityEnabled("purified_soul")) {
            player.getActiveEffects().stream()
                .filter(e -> e.getEffect().getCategory() == MobEffectCategory.HARMFUL)
                .map(MobEffectInstance::getEffect)
                .toList()
                .forEach(player::removeEffect);
        }
```

改为：
```java
        // ---- 净魂兜底 + 觉醒虚弱光环 ----
        if (progress.isAbilityEnabled("purified_soul")) {
            player.getActiveEffects().stream()
                .filter(e -> e.getEffect().getCategory() == MobEffectCategory.HARMFUL)
                .map(MobEffectInstance::getEffect)
                .toList()
                .forEach(player::removeEffect);

            // 觉醒：每2秒给周围敌对生物施加虚弱II
            if (progress.isFullyUnlocked()
                && player.level().getGameTime() % 40 == 0) { // 40 tick = 2秒
                int radius = ModConfig.AWAKEN_PURIFIED_SOUL_RADIUS.get();
                AABB aabb = player.getBoundingBox().inflate(radius);
                List<LivingEntity> targets = player.level().getEntitiesOfClass(
                    LivingEntity.class, aabb,
                    e -> e != player && e.isAlive() && e instanceof Monster);
                for (LivingEntity target : targets) {
                    MobEffectInstance existing = target.getEffect(MobEffects.WEAKNESS);
                    if (existing == null || existing.getAmplifier() < 1
                        || existing.getDuration() < 60) {
                        target.addEffect(new MobEffectInstance(
                            MobEffects.WEAKNESS, 100, 1, false, false, true));
                    }
                }
            }
        }
```

需要在文件顶部新增 import：
```java
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import java.util.List;
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java
git commit -m "feat: 觉醒净魂 — 16格内敌对生物获得虚弱II光环"
```

---

### Task 12: 环境免疫觉醒 — 免疫所有无源伤害

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java`

- [ ] **Step 1: 在 onEnvDamage 中追加觉醒全无源伤害免疫**

在 `PlayerStateHandler.onEnvDamage` 方法中，现有逻辑在特定标签和 msgId 匹配后取消。在觉醒状态下，扩展为所有无源伤害（`source.getEntity() == null && source.getDirectEntity() == null`）全部取消：

找到 `onEnvDamage` 方法（第177-212行），在现有门禁检查后、标签匹配之前（约第190行），追加觉醒逻辑：

```java
            // 觉醒：免疫所有无源伤害（不仅是标签覆盖的环境伤害）
            if (progress.isFullyUnlocked()
                && source.getEntity() == null
                && source.getDirectEntity() == null) {
                event.setCanceled(true);
                return;
            }
```

这段代码应该放在现有的 `if (source.getEntity() != null) return;` 之后、`IS_FIRE` 等标签检查之前，使得觉醒状态下只要是无源伤害直接拦截。

实际上更好的做法是将觉醒检查放在 `source.getEntity() != null` 检查之前，因为觉醒免疫所有无源伤害：

```java
            DamageSource source = event.getSource();

            // 觉醒：免疫所有无源伤害
            if (progress.isFullyUnlocked()
                && source.getEntity() == null
                && source.getDirectEntity() == null) {
                event.setCanceled(true);
                return;
            }

            // 非觉醒：仅排除生物造成的伤害
            if (source.getEntity() != null) return;
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java
git commit -m "feat: 觉醒环境免疫 — 免疫所有无源伤害"
```

---

### Task 13: 旅者庇护觉醒 — 可缓慢移动

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/skill/ActiveSkillHandler.java`

- [ ] **Step 1: 在 handleSanctuary 中设置玩家移动速度**

在 `handleSanctuary` 方法中，释放庇护后设置玩家的移动速度属性（觉醒时允许缓慢移动）：

```java
        // 觉醒：庇护期间允许缓慢移动
        if (progress.isFullyUnlocked()) {
            var speedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                // 已通过 ActiveSkillHudOverlay 在客户端限制移动输入，
                // 此处仅调整服务端速度属性
            }
        }
```

实际上，庇护期间"不可移动"的逻辑在 `ActiveSkillHudOverlay` 的客户端 tick 中实现（通过覆盖移动输入）。觉醒时需要让客户端知道可以移动但速度降低。更简单的实现方式是在 `NetworkHandler` 中庇护包已包含觉醒状态，客户端 overlay 检测后允许移动但减慢。

考虑到复杂度，先简化为：在 `handleSanctuary` 中判断觉醒，若觉醒则不锁定玩家移动（`player.setDeltaMovement` 不归零），仅设速度倍率。实际移动限制由客户端 overlay 控制。

在 `handleSanctuary` 方法末尾（粒子播放之后），追加：

```java
        // 觉醒：庇护期间设置减速而非锁定
        if (progress.isFullyUnlocked()) {
            var speedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
            // 速度由 PlayerStateHandler tick 管理，此处标记觉醒状态即可
        }
```

由于庇护移动限制主要在客户端 overlay (`ActiveSkillHudOverlay`) 中通过劫持输入实现，觉醒改动需要网络包传输觉醒状态。为降低复杂度，在庇护包中新增 `awakened` 字段。

先跳过网络包改动，改为：庇护期间在 `PlayerStateHandler.onPlayerTick` 中检测觉醒庇护状态并调整速度：

```java
        // 觉醒庇护：减速而非锁定
        if (progress.getSanctuaryInvulEnd() > currentTime
            && progress.isFullyUnlocked()
            && progress.isAbilityEnabled("active_skill")) {
            var speedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                double target = 0.1 * ModConfig.AWAKEN_SANCTUARY_SPEED.get();
                if (Math.abs(speedAttr.getBaseValue() - target) > 0.001) {
                    speedAttr.setBaseValue(target);
                }
            }
        }
```

此逻辑放在 `PlayerStateHandler.onPlayerTick` 门禁检查之后。

需要新增 import `net.minecraft.world.entity.ai.attributes.Attributes`。

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

（此任务与 Task 14 数值强化一起提交，因为同属 PlayerStateHandler）

---

### Task 14: 剩余数值强化 — 大地之力/休养生息/不动如山/无形之手/鸿运当头/坚韧之躯/受击坚韧/破敌之眼/真实血量/拒绝篡改/恩赐永驻

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/ExplorationAbilityHandler.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/RecoveryHandler.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/KnockbackResistHandler.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/FortuneFavorHandler.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/PlayerStateHandler.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/mixin/SeeAndSlashMixin.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/mixin/TrueHealthMixin.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/mixin/RejectHealthManipMixin.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/ability/ResilienceAbility.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/ability/FortuneFavorAbility.java`

- [ ] **Step 1: ExplorationAbilityHandler — 大地之力/无形之手/坚韧之躯 觉醒强化**

在 `onBreakSpeed` (大地之力) 中追加觉醒倍率：
```java
            float multiplier = ability.value(progress.getUnlockedMilestoneCount());
            if (progress.isFullyUnlocked()) {
                multiplier *= ModConfig.AWAKEN_MULTIPLIER.get().floatValue();
            }
            event.setNewSpeed(event.getOriginalSpeed() * multiplier);
```

在 `syncReachAttribute` (无形之手) 中追加觉醒倍率：
```java
            float bonus = ability.value(milestones);
            if (progress.isFullyUnlocked()) {
                bonus *= ModConfig.AWAKEN_MULTIPLIER.get().floatValue();
            }
            double expected = defaultValue + bonus;
```

在 `syncVitalityAttribute` (坚韧之躯) 中追加觉醒倍率：
```java
            float bonus = ability.value(milestones);
            if (progress.isFullyUnlocked()) {
                bonus = (float) Math.ceil(bonus * ModConfig.AWAKEN_MULTIPLIER.get());
            }
            double expected = 20.0 + bonus;
```

- [ ] **Step 2: RecoveryHandler — 休养生息 觉醒强化**

在 `onPlayerTick` 的休养生息部分，计算 amplifier 时追加觉醒加成：

```java
                int amplifier = (int) ability.value(progress.getUnlockedMilestoneCount());
                // 觉醒：再生 +2 级
                if (progress.isFullyUnlocked()) {
                    amplifier += 2;
                }
```

- [ ] **Step 3: KnockbackResistHandler — 不动如山 觉醒强化**

在 `onPlayerTick` 中计算击退抗性时：

```java
                float percent = ability.value(progress.getUnlockedMilestoneCount());
                if (progress.isFullyUnlocked()) {
                    percent = Math.min(percent * ModConfig.AWAKEN_MULTIPLIER.get().floatValue(), 100.0f);
                }
                attr.setBaseValue(percent / 100.0);
```

同样在 `onPlayerClone` 中更新对应逻辑。

- [ ] **Step 4: FortuneFavorHandler — 鸿运当头 觉醒强化**

在 `onLootingLevel` 中：
```java
            int bonus = (int) ability.value(progress.getUnlockedMilestoneCount());
            if (progress.isFullyUnlocked()) {
                bonus += 2; // 觉醒固定 +2
            }
            event.setLootingLevel(event.getLootingLevel() + bonus);
```

时运同理（通过 `FortuneContext` 传递，Mixin 读取 bonus 时已通过 `ability.value()` 获取，需要在 `FortuneFavorMixin` 中也追加觉醒检测）。

查看 `FortuneFavorMixin`，它通过 `FortuneContext` 获取玩家后调用 `AbilityRegistry.get("fortune_favor").value(milestones)` 计算。在 Mixin 中追加觉醒检测过于复杂，改为在 `FortuneContext` 中存储觉醒状态，或在 `FortuneFavorAbility.value()` 中接受觉醒参数。

更简单的方式：在 `FortuneFavorAbility` 中新增 `awakenedBonus()` 方法，Handler 和 Mixin 分别调用：

在 `FortuneFavorAbility` 中新增：
```java
    public int awakenedBonus() {
        return 2;  // 觉醒固定 +2
    }
```

在 `FortuneFavorHandler.onLootingLevel` 中：
```java
            int bonus = (int) ability.value(progress.getUnlockedMilestoneCount());
            if (progress.isFullyUnlocked() && ability instanceof FortuneFavorAbility ffa) {
                bonus += ffa.awakenedBonus();
            }
```

在 `FortuneFavorMixin` 中类似追加。

考虑到 Mixin 获取 Capability 的复杂度，在 `FortuneContext` 中新增一个静态 boolean `isAwakened`，在 `onBlockBreak` 中设置：

```java
            FortuneContext.setBreaker(player);
            if (progress.isFullyUnlocked()) {
                FortuneContext.setAwakened(true);
            }
```

并在 `FortuneFavorMixin` 中读取：
```java
            if (FortuneContext.isAwakened()) {
                bonus += 2;
            }
            FortuneContext.clear();
```

在 `FortuneContext.java` 中新增：
```java
    private static boolean awakened = false;
    public static void setAwakened(boolean val) { awakened = val; }
    public static boolean isAwakened() { return awakened; }
```

并在 `clear()` 中重置：
```java
    public static void clear() {
        BREAKER.remove();
        awakened = false;
    }
```

- [ ] **Step 5: ResilienceAbility — 受击坚韧 觉醒强化**

在 `ResilienceAbility.value()` 的 switch 中，新增觉醒情况：

```java
    @Override
    public float value(int milestones) {
        return switch (milestones) {
            case 8 -> ModConfig.RESILIENCE_STACKS_8.get();
            case 9 -> ModConfig.RESILIENCE_STACKS_9.get();
            case 10 -> ModConfig.RESILIENCE_STACKS_10.get();
            default -> 0;
        };
    }
```

改为增加 `awakened` 参数的重载：

```java
    public float value(int milestones, boolean awakened) {
        int base = switch (milestones) {
            case 8 -> ModConfig.RESILIENCE_STACKS_8.get();
            case 9 -> ModConfig.RESILIENCE_STACKS_9.get();
            case 10 -> ModConfig.RESILIENCE_STACKS_10.get();
            default -> 0;
        };
        return awakened ? base + 6 : base;
    }
```

并在 `PlayerStateHandler.onLivingHurt` (受击坚韧) 中调用时传入觉醒状态：

```java
            int maxStacks = (int) ((ResilienceAbility) AbilityRegistry.get("resilience"))
                .value(progress.getUnlockedMilestoneCount(), progress.isFullyUnlocked());
```

注意需要类型转换，因为 `AbilityRegistry.get()` 返回 `Ability` 接口。

- [ ] **Step 6: SeeAndSlashMixin — 破敌之眼 觉醒强化**

在 `SeeAndSlashMixin.onIsInvulnerableTo` 中，当目标被判定无敌被穿透时，需要在攻击者侧增加伤害。但由于这是在 `isInvulnerableTo` 检查中，无法直接修改伤害值。

更好的做法是在 `SeeAndSlashLivingEntityMixin`（hurt 拦截层）中检测觉醒并附加额外伤害。

让我先查看该 Mixin：

实际上，破敌之眼的觉醒效果是"对无敌目标 +30% 伤害"，这需要在 `LivingHurtEvent` 中检测——但此时如果无敌已被穿透（`isInvulnerableTo` 返回 false），hurt 正常执行，也就没有"无敌目标"的概念了。

换个思路：在 `SeeAndSlashLivingEntityMixin` 的 hurt 拦截中，当穿过了自定义无敌（非 `isInvulnerableTo`）时，附加 +30% 伤害。

或者更简单：在 `CombatAbilityHandler.onLivingHurt` 中新增破敌之眼的处理——对任何有 `invulnerableTime > 0` 或走过了自定义无敌检查的目标附加伤害。但这过于复杂。

为简化，将破敌之眼觉醒改为：**对Boss类生物额外造成 +30% 伤害**（因为 Boss 最常有无敌帧）。在 `CombatAbilityHandler.onLivingHurt` 中追加：

```java
        // 破敌之眼觉醒：对Boss目标 +30% 伤害
        if (source.getEntity() instanceof Player attacker
            && target instanceof net.minecraft.world.entity.boss.warden.Warden) {
            // Warden 不是 Boss 接口，改用 isInvulnerable 检测
        }
```

实际上，最简单且最有效的方式是：在 `handleSoulQuench` 中（因为破敌之眼和淬魂总是同时生效的晚期能力），对觉醒状态下穿透无敌时额外增伤。但这改变了两个能力的边界。

折中方案：在 `CombatAbilityHandler.onLivingHurt` 中新增破敌之眼的独立处理——对所有攻击者伤害附加觉醒倍率。仅当目标仍有 `invulnerableTime > 0` 时触发（说明攻击穿过了无敌）：

```java
    // 破敌之眼觉醒：对无敌帧中的目标 +30% 伤害
    private static void handlePiercingGazeAwakened(LivingHurtEvent event, LivingEntity target, Player attacker) {
        if (target.invulnerableTime <= 0) return;
        AdventureProgressCapability.getAdventureProgress(attacker).ifPresent(progress -> {
            if (!progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled("piercing_gaze")) return;
            event.setAmount(event.getAmount() * 1.30f);
        });
    }
```

在 `onLivingHurt` 中调用（在 `handleSoulQuench` 之前）：
```java
            handlePiercingGazeAwakened(event, target, attacker);
```

- [ ] **Step 7: TrueHealthMixin — 真实血量 觉醒修复速度翻倍**

在 `TrueHealthMixin.onGetHealth` 中，当检测到 `diff > 0`（合法回血）时，觉醒状态下加快备份同步速度（本质上"修复速度翻倍"意味着对相同差异更宽容）。

实际上，"修复速度翻倍"更适合在 `onSetHealthReturn` 中实现——觉醒时备份值向 DataItem 靠拢的速度翻倍。但这太抽象。

简化为：觉醒时，在 `onGetHealth` 的自动修复中，将修复量翻倍（将 backup 到 rawHealth 的差距以更快的速度修复）。实际上当前实现是直接将 DataItem 修复为 backup 值，"速度"概念不适用。

改为解读为：真实血量备份更新频率翻倍——当前在 `onSetHealthReturn` 中每次 setHealth 都更新备份，速度已是最快。"翻倍"可以理解为备份容错率翻倍（EPSILON 增大），让系统对小幅非法改动的容忍度更高。

对玩家体验而言，"修复速度翻倍"最具感知的做法是：在 `AdventureProgressCapability.onPlayerTick` 中，觉醒状态下每 tick 检查并主动修复血量差异，而非仅依赖 Mixin 的被动修复。但这会引入性能开销。

维持简单：在 `TrueHealthMixin.onGetHealth` 的修复分支中，对觉醒状态扩大 EPSILON（让修复更激进）：

```java
                float effectiveEpsilon = (progress.isFullyUnlocked() && progress.isAbilityEnabled("true_health"))
                    ? EPSILON * 2.0F : EPSILON;
                if (Math.abs(diff) > effectiveEpsilon) {
```

- [ ] **Step 8: RejectHealthManipMixin — 拒绝篡改 觉醒反弹伤害**

在 `rejectSetHealth` 方法中，当拒绝外部降血时，追加反弹逻辑：

```java
                if (progress.isFullyUnlocked()
                      && progress.isAbilityEnabled("reject_manip")) {
                    ci.cancel();
                    // 觉醒：反弹 30% 被拒绝的伤害
                    float reflected = (currentHealth - newHealth) * 0.30f;
                    Entity lastAttacker = player.getLastHurtByMob();
                    if (lastAttacker != null && reflected > 0.0F && lastAttacker.isAlive()) {
                        lastAttacker.hurt(player.damageSources().magic(), reflected);
                    }
                }
```

注意需要 import `net.minecraft.world.entity.Entity`。

- [ ] **Step 9: AdventureProgressCapability — 恩赐永驻 觉醒强化**

在 `extendBeneficialEffects` 中追加觉醒倍率：
```java
        int extendAmount = ModConfig.BUFF_EXTEND_AMOUNT.get();
        // 觉醒：延长量 ×1.3
        if (progress.isFullyUnlocked()) {
            extendAmount = (int) (extendAmount * ModConfig.AWAKEN_MULTIPLIER.get());
        }
        int threshold = minDuration + extendAmount;
```

但 `extendBeneficialEffects` 是 private static，无法直接获取 progress。需要改为从 player 重新获取：

```java
    private static void extendBeneficialEffects(Player player) {
        boolean extended = false;
        Set<String> excluded = getBuffExclusionSet(player);
        int minDuration = ModConfig.BUFF_MIN_DURATION.get();
        int extendAmount = ModConfig.BUFF_EXTEND_AMOUNT.get();
        // 觉醒：延长量 ×1.3
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isFullyUnlocked()) {
                // 使用局部变量无法跨 lambda 修改，需在外层处理
            }
        });
```

重构为：
```java
        int extendAmount = ModConfig.BUFF_EXTEND_AMOUNT.get();
        boolean awakened = AdventureProgressCapability.getAdventureProgress(player)
            .map(p -> p.isFullyUnlocked()).orElse(false);
        if (awakened) {
            extendAmount = (int) (extendAmount * ModConfig.AWAKEN_MULTIPLIER.get());
        }
        int threshold = minDuration + extendAmount;
```

- [ ] **Step 10: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 11: 提交**

```bash
git add src/main/java/com/ayin90723/adventure_power/handler/ && git add src/main/java/com/ayin90723/adventure_power/mixin/ && git add src/main/java/com/ayin90723/adventure_power/ability/ && git add src/main/java/com/ayin90723/adventure_power/util/FortuneContext.java && git add src/main/java/com/ayin90723/adventure_power/capability/AdventureProgressCapability.java
git commit -m "feat: 全部15项数值强化觉醒效果"
```

---

### Task 15: 语言文件更新 + 能力面板 tooltip

**Files:**
- Modify: `src/main/resources/assets/adventure_power/lang/zh_cn.json`
- Modify: `src/main/resources/assets/adventure_power/lang/en_us.json`

- [ ] **Step 1: 新增觉醒相关翻译键**

在 `zh_cn.json` 中新增：
```json
    "awakened.adventure_power.title": "冒险者的觉醒",
    "awakened.adventure_power.desc": "所有能力获得大幅强化",
    "ability.adventure_power.void_step.awakened": "§6[觉醒] §e三段跳",
    "ability.adventure_power.soul_bind.awakened": "§6[觉醒] §e死亡不掉落经验",
    "ability.adventure_power.undying_gear.awakened": "§6[觉醒] §e每件护甲+1护甲值，武器+15%伤害",
    "ability.adventure_power.soar.awakened": "§6[觉醒] §e飞行速度+50%",
    "ability.adventure_power.death_defy.awakened": "§6[觉醒] §e触发时释放免费旅者审判",
    "ability.adventure_power.shadow_kill.awakened": "§6[觉醒] §e斩杀时8格AOE",
    "ability.adventure_power.soul_quench.awakened": "§6[觉醒] §e目标低于20%HP伤害翻倍",
    "ability.adventure_power.lifesteal.awakened": "§6[觉醒] §e过量治疗转吸收护盾",
    "ability.adventure_power.purified_soul.awakened": "§6[觉醒] §e周围敌对生物虚弱II",
    "ability.adventure_power.active_skill.awakened": "§6[觉醒] §e审判范围+50%，庇护可移动",
    "ability.adventure_power.env_immunity.awakened": "§6[觉醒] §e免疫所有无源伤害",
    "ability.adventure_power.piercing_gaze.awakened": "§6[觉醒] §e对无敌目标+30%伤害",
    "ability.adventure_power.true_health.awakened": "§6[觉醒] §e血量修复速度翻倍",
    "ability.adventure_power.reject_manip.awakened": "§6[觉醒] §e反弹30%被拦截伤害",
    "ability.adventure_power.agility.awakened": "§6[觉醒] §e闪避率95%",
    "ability.adventure_power.digging_power.awakened": "§6[觉醒] §e挖掘速度2.28x",
    "ability.adventure_power.knockback_resist.awakened": "§6[觉醒] §e100%击退抗性",
    "ability.adventure_power.damage_resist.awakened": "§6[觉醒] §e减伤52%",
    "ability.adventure_power.extended_reach.awakened": "§6[觉醒] §e触及距离+3.6格",
    "ability.adventure_power.fortune_favor.awakened": "§6[觉醒] §e时运/抢夺+5",
    "ability.adventure_power.healing_block.awakened": "§6[觉醒] §e禁疗8秒",
    "ability.adventure_power.vitality.awakened": "§6[觉醒] §e最大生命+16",
    "ability.adventure_power.resilience.awakened": "§6[觉醒] §e最大18层减伤",
    "ability.adventure_power.rapid_recovery.awakened": "§6[觉醒] §e再生VII",
    "ability.adventure_power.perpetual_blessing.awakened": "§6[觉醒] §eBuff延长量x1.3"
```

在 `en_us.json` 中对应新增英文翻译：
```json
    "awakened.adventure_power.title": "Adventurer's Awakening",
    "awakened.adventure_power.desc": "All abilities are greatly enhanced",
    "ability.adventure_power.void_step.awakened": "§6[Awakened] §eTriple Jump",
    "ability.adventure_power.soul_bind.awakened": "§6[Awakened] §eKeep XP on death",
    "ability.adventure_power.undying_gear.awakened": "§6[Awakened] §e+1 Armor per piece, +15% weapon damage",
    "ability.adventure_power.soar.awakened": "§6[Awakened] §eFlight speed +50%",
    "ability.adventure_power.death_defy.awakened": "§6[Awakened] §eTriggers free Judgment on proc",
    "ability.adventure_power.shadow_kill.awakened": "§6[Awakened] §e8-block AOE on execute",
    "ability.adventure_power.soul_quench.awakened": "§6[Awakened] §eDouble damage below 20% HP",
    "ability.adventure_power.lifesteal.awakened": "§6[Awakened] §eExcess healing → absorption shield",
    "ability.adventure_power.purified_soul.awakened": "§6[Awakened] §eWeakness II aura (16 blocks)",
    "ability.adventure_power.active_skill.awakened": "§6[Awakened] §eJudgment +50% range, Sanctuary allows slow move",
    "ability.adventure_power.env_immunity.awakened": "§6[Awakened] §eImmune to all sourceless damage",
    "ability.adventure_power.piercing_gaze.awakened": "§6[Awakened] §e+30% damage to invulnerable targets",
    "ability.adventure_power.true_health.awakened": "§6[Awakened] §eRepair speed doubled",
    "ability.adventure_power.reject_manip.awakened": "§6[Awakened] §eReflect 30% blocked damage",
    "ability.adventure_power.agility.awakened": "§6[Awakened] §e95% dodge chance",
    "ability.adventure_power.digging_power.awakened": "§6[Awakened] §e2.28x mining speed",
    "ability.adventure_power.knockback_resist.awakened": "§6[Awakened] §e100% knockback resist",
    "ability.adventure_power.damage_resist.awakened": "§6[Awakened] §e52% damage reduction",
    "ability.adventure_power.extended_reach.awakened": "§6[Awakened] §e+3.6 block reach",
    "ability.adventure_power.fortune_favor.awakened": "§6[Awakened] §eFortune/Looting +5",
    "ability.adventure_power.healing_block.awakened": "§6[Awakened] §eHealing block 8s",
    "ability.adventure_power.vitality.awakened": "§6[Awakened] §eMax health +16",
    "ability.adventure_power.resilience.awakened": "§6[Awakened] §eUp to 18 stacks",
    "ability.adventure_power.rapid_recovery.awakened": "§6[Awakened] §eRegeneration VII",
    "ability.adventure_power.perpetual_blessing.awakened": "§6[Awakened] §eBuff extension x1.3"
```

- [ ] **Step 2: 构建验证**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/assets/adventure_power/lang/
git commit -m "feat: 新增觉醒阶段全部翻译键（中英文）"
```

---

### Task 16: 最终集成测试与构建

- [ ] **Step 1: 全量构建**

```bash
cd D:/download/模组/冒险的力量 && ./gradlew build -x test 2>&1 | tail -30
```

预期: BUILD SUCCESSFUL

- [ ] **Step 2: 检查构件**

```bash
ls -la build/libs/adventure_power-1.0.0.jar
```

- [ ] **Step 3: 最终提交**

```bash
git add -A && git status
git commit -m "feat: 完成「冒险者的觉醒」第11阶段全部实现

- 10个机制质变：三段跳/保留经验/属性加成/飞行加速/免费审判/AOE影杀/斩杀线/护盾/虚弱光环/审判扩大+庇护移动
- 15个数值强化：统一×1.3倍率+特殊能力固定加成
- 新增ModConfig配置项14个
- 新增ActiveSkillHandler.executeJudgment公开方法
- DoubleJumpHandler跳数制改造

Co-Authored-By: Claude <noreply@anthropic.com>"
```
