# 三个新能力实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增休养生息(rapid_recovery)、不动如山(knockback_resist)、嗜血(lifesteal)三个被动能力

**Architecture:** 3 个 Ability 元数据类（遵循现有 ~30 行模式）+ 2 个 Handler（RecoveryHandler 合并休养生息+嗜血，KnockbackResistHandler 管理属性）。不动如山直接操作原版 KNOCKBACK_RESISTANCE 属性，不自己拦截击退计算。

**Tech Stack:** Java 17, Forge 1.20.1 47.4.10, Minecraft 原版 Attributes/MobEffects

---

## 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `ability/RapidRecoveryAbility.java` | 休养生息元数据 + 成长曲线 |
| 新增 | `ability/KnockbackResistAbility.java` | 不动如山元数据 + 成长曲线 |
| 新增 | `ability/LifestealAbility.java` | 嗜血元数据 + 成长曲线 |
| 新增 | `handler/RecoveryHandler.java` | 休养生息脱战再生 + 嗜血攻击吸血 |
| 新增 | `handler/KnockbackResistHandler.java` | 不动如山属性值管理 |
| 修改 | `config/ModConfig.java` | 添加 3 组能力配置 |
| 修改 | `ability/AbilityRegistry.java` | 注册 3 个新能力 |
| 修改 | `capability/AdventureProgressCapability.java` | KNOWN_ABILITIES 添加 3 条目 |
| 修改 | `assets/.../lang/zh_cn.json` | 中文翻译 |
| 修改 | `assets/.../lang/en_us.json` | 英文翻译 |

---

### Task 1: ModConfig — 添加配置项

**Files:**
- Modify: `src/main/java/com/rosycentury/adventure_power/config/ModConfig.java`

在 `能力数值` push 块内，`影杀` pop 之后、`能力数值` pop 之前，插入三个新能力的配置声明和初始化。

- [ ] **Step 1: 添加配置字段声明**

在 `SHADOW_KILL_HP_RATIO` 声明之后添加：

```java
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
```

- [ ] **Step 2: 添加配置初始化**

在 `SHADOW_KILL_HP_RATIO` 赋值 block 之后（`BUILDER.pop(); // 影杀` 之后）添加：

```java
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
```

- [ ] **Step 3: 验证构建**

```bash
cd "D:\download\模组\冒险的力量" && ./gradlew build -x test
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/config/ModConfig.java
git commit -m "feat: 添加休养生息/不动如山/嗜血配置项"
```

---

### Task 2: RapidRecoveryAbility — 休养生息元数据

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/ability/RapidRecoveryAbility.java`

- [ ] **Step 1: 编写 Ability 类**

```java
package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 休养生息 — 脱战后自动获得生命恢复效果。
 * 解锁条件：2 里程碑
 * 成长公式：amplifier = base + step × ((milestones - required) / 2)
 * 默认：里程碑2=再生I(0), 4=再生II(1), 10=再生V(4)
 */
public class RapidRecoveryAbility implements Ability {

    @Override
    public String id() {
        return "rapid_recovery";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.rapid_recovery");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.rapid_recovery.desc");
    }

    @Override
    public int requiredMilestones() {
        return 2;
    }

    /**
     * 返回再生效果 amplifier（0=再生I, 4=再生V）。
     * 每 2 个里程碑升一级。
     */
    @Override
    public float value(int milestones) {
        int steps = (milestones - requiredMilestones()) / 2;
        return ModConfig.RAPID_RECOVERY_AMPLIFIER_BASE.get()
            + ModConfig.RAPID_RECOVERY_AMPLIFIER_STEP.get() * steps;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/ability/RapidRecoveryAbility.java
git commit -m "feat: 添加休养生息能力元数据"
```

---

### Task 3: KnockbackResistAbility — 不动如山元数据

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/ability/KnockbackResistAbility.java`

- [ ] **Step 1: 编写 Ability 类**

```java
package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 不动如山 — 百分比减少受到的击退距离。
 * 解锁条件：3 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：30% → 79%
 */
public class KnockbackResistAbility implements Ability {

    @Override
    public String id() {
        return "knockback_resist";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.knockback_resist");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.knockback_resist.desc");
    }

    @Override
    public int requiredMilestones() {
        return 3;
    }

    /**
     * 返回击退抗性百分比。从配置读取 base + per_milestone × (milestones - required)。
     */
    @Override
    public float value(int milestones) {
        return ModConfig.KNOCKBACK_RESIST_BASE.get()
            + ModConfig.KNOCKBACK_RESIST_PER_MILESTONE.get() * (milestones - requiredMilestones());
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/ability/KnockbackResistAbility.java
git commit -m "feat: 添加不动如山能力元数据"
```

---

### Task 4: LifestealAbility — 嗜血元数据

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/ability/LifestealAbility.java`

- [ ] **Step 1: 编写 Ability 类**

```java
package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 嗜血 — 攻击造成伤害时回复自身生命值。
 * 解锁条件：6 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：5% → 13%
 */
public class LifestealAbility implements Ability {

    @Override
    public String id() {
        return "lifesteal";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.lifesteal");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.lifesteal.desc");
    }

    @Override
    public int requiredMilestones() {
        return 6;
    }

    /**
     * 返回吸血百分比。从配置读取 base + per_milestone × (milestones - required)。
     */
    @Override
    public float value(int milestones) {
        return ModConfig.LIFESTEAL_BASE.get()
            + ModConfig.LIFESTEAL_PER_MILESTONE.get() * (milestones - requiredMilestones());
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/ability/LifestealAbility.java
git commit -m "feat: 添加嗜血能力元数据"
```

---

### Task 5: AbilityRegistry — 注册新能力

**Files:**
- Modify: `src/main/java/com/rosycentury/adventure_power/ability/AbilityRegistry.java`

- [ ] **Step 1: 添加注册调用**

在 `register(new ActiveSkillAbility());` 之前插入三个新能力注册：

```java
        register(new RapidRecoveryAbility());
        register(new KnockbackResistAbility());
        register(new LifestealAbility());
```

插入位置在 `RejectManipAbility` 之后、`ActiveSkillAbility` 之前，保持里程碑顺序。完整注册顺序应为：

```java
        register(new AgilityAbility());           // 1
        register(new PerpetualBlessingAbility()); // 1
        register(new VoidStepAbility());          // 2
        register(new RapidRecoveryAbility());     // 2 ← 新增
        register(new SoulBindAbility());          // 3
        register(new KnockbackResistAbility());   // 3 ← 新增
        register(new DamageResistAbility());      // 4
        register(new UndyingGearAbility());       // 5
        register(new EnvImmunityAbility());       // 6
        register(new LifestealAbility());         // 6 ← 新增
        register(new HealingBlockAbility());      // 7
        register(new ResilienceAbility());        // 8
        register(new PurifiedSoulAbility());      // 8
        register(new SoarAbility());              // 9
        register(new SoulQuenchAbility());        // 9
        register(new PiercingGazeAbility());      // 9
        register(new DeathDefyAbility());         // 9
        register(new ShadowKillAbility());        // 10
        register(new TrueHealthAbility());        // 10
        register(new RejectManipAbility());       // 10
        register(new ActiveSkillAbility());       // 10
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/ability/AbilityRegistry.java
git commit -m "feat: 注册休养生息/不动如山/嗜血三个新能力"
```

---

### Task 6: KNOWN_ABILITIES — 添加面板条目

**Files:**
- Modify: `src/main/java/com/rosycentury/adventure_power/capability/AdventureProgressCapability.java`

- [ ] **Step 1: 在 KNOWN_ABILITIES 中添加条目**

在 static 块中，按里程碑顺序插入三个条目。`purified_soul` 之后插入 `rapid_recovery` 和 `knockback_resist`，`env_immunity` 之后插入 `lifesteal`：

```java
        KNOWN_ABILITIES.put("purified_soul", Component.translatable("ability.adventure_power.purified_soul"));
        KNOWN_ABILITIES.put("rapid_recovery", Component.translatable("ability.adventure_power.rapid_recovery"));
        KNOWN_ABILITIES.put("knockback_resist", Component.translatable("ability.adventure_power.knockback_resist"));
        KNOWN_ABILITIES.put("soar", Component.translatable("ability.adventure_power.soar"));
```

以及 `env_immunity` 之后：

```java
        KNOWN_ABILITIES.put("env_immunity", Component.translatable("ability.adventure_power.env_immunity"));
        KNOWN_ABILITIES.put("lifesteal", Component.translatable("ability.adventure_power.lifesteal"));
        KNOWN_ABILITIES.put("healing_block", Component.translatable("ability.adventure_power.healing_block"));
```

完整顺序为：agility, perpetual_blessing, void_step, rapid_recovery, soul_bind, knockback_resist, damage_resist, undying_gear, env_immunity, lifesteal, healing_block, resilience, purified_soul, soar, soul_quench, piercing_gaze, death_defy, shadow_kill, true_health, reject_manip, active_skill

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/capability/AdventureProgressCapability.java
git commit -m "feat: KNOWN_ABILITIES 添加三个新能力条目"
```

---

### Task 7: RecoveryHandler — 休养生息脱战再生 + 嗜血攻击吸血

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/handler/RecoveryHandler.java`

- [ ] **Step 1: 编写 RecoveryHandler**

```java
package com.rosycentury.adventure_power.handler;

import com.rosycentury.adventure_power.AdventurePower;
import com.rosycentury.adventure_power.ability.Ability;
import com.rosycentury.adventure_power.ability.AbilityRegistry;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.config.ModConfig;
import com.rosycentury.adventure_power.util.FriendlyFireProtection;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 恢复类能力效果处理器。
 * <p>
 * 处理 2 种恢复能力的实际效果：
 * <ul>
 *   <li>休养生息 (rapid_recovery) — 脱战 N 秒后自动获得生命恢复</li>
 *   <li>嗜血 (lifesteal) — 攻击造成伤害时回复自身生命值</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RecoveryHandler {

    /** 脱战再生检查间隔（tick），每 3 秒检查一次 */
    private static final int RECOVERY_CHECK_INTERVAL = 60;

    /** 上次再生检查时间缓存 */
    private static final Map<UUID, Long> lastRecoveryCheck = new ConcurrentHashMap<>();

    /** 玩家最后受伤时间 */
    private static final Map<UUID, Long> lastHurtTimestamps = new ConcurrentHashMap<>();

    // ==================== 休养生息 — 脱战再生 ====================

    /**
     * 记录所有冒险者玩家的受伤时间，供休养生息判断脱战间隔。
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("rapid_recovery")) return;
            lastHurtTimestamps.put(player.getUUID(), player.level().getGameTime());
        });
    }

    /**
     * 每 tick 检查：
     * 休养生息 — 脱战超过延迟阈值后，周期性施压生命恢复效果
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

        // ---- 休养生息 ----
        if (progress.isAbilityEnabled("rapid_recovery")) {
            long currentTime = player.level().getGameTime();
            long lastCheck = lastRecoveryCheck.getOrDefault(player.getUUID(), -1L);
            if (lastCheck == -1L) {
                lastRecoveryCheck.put(player.getUUID(), currentTime);
            } else if (currentTime - lastCheck >= RECOVERY_CHECK_INTERVAL) {
                lastRecoveryCheck.put(player.getUUID(), currentTime);

                int delayTicks = ModConfig.RAPID_RECOVERY_DELAY_TICKS.get();
                long lastHurt = lastHurtTimestamps.getOrDefault(player.getUUID(), 0L);
                long timeSinceHurt = currentTime - lastHurt;

                // 脱战超过延迟阈值 + 血量未满 → 施压再生
                if (timeSinceHurt >= delayTicks && player.getHealth() < player.getMaxHealth()) {
                    Ability ability = AbilityRegistry.get("rapid_recovery");
                    if (ability != null) {
                        int amplifier = (int) ability.value(progress.getUnlockedMilestoneCount());
                        // 检查玩家是否已有更高等级的外部再生效果
                        MobEffectInstance existing = player.getEffect(MobEffects.REGENERATION);
                        if (existing == null || existing.getAmplifier() < amplifier) {
                            player.addEffect(new MobEffectInstance(
                                MobEffects.REGENERATION,
                                delayTicks + 20,  // 稍长于检查间隔，确保覆盖
                                amplifier,
                                false, false, true));
                        }
                    }
                }
            }
        } else {
            lastRecoveryCheck.remove(player.getUUID());
            lastHurtTimestamps.remove(player.getUUID());
        }
    }

    /**
     * 玩家登出时清理缓存，防止内存泄漏。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        lastRecoveryCheck.remove(id);
        lastHurtTimestamps.remove(id);
    }

    // ==================== 嗜血 — 攻击吸血 ====================

    /**
     * 嗜血：攻击造成伤害时按比例回复自身生命值。
     * <p>
     * 跳过内部穿透伤害（soul_strike / judgment），防止递归吸血。
     * 吸血量上限为最大生命值的 {@link ModConfig#LIFESTEAL_CAP_RATIO} 倍。
     */
    @SubscribeEvent
    public static void onLivingHurtLifesteal(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;
        if (!(event.getSource().getEntity() instanceof Player attacker)) return;
        if (target instanceof Player) return; // PVP 无效

        // 跳过内部穿透伤害，防递归
        String msgId = event.getSource().getMsgId();
        if ("soul_strike".equals(msgId) || "judgment".equals(msgId)) return;

        if (FriendlyFireProtection.isOwnerTarget(attacker, target)) return;

        AdventureProgressCapability.getAdventureProgress(attacker).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("lifesteal")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            Ability ability = AbilityRegistry.get("lifesteal");
            if (ability == null) return;

            float percentage = ability.value(progress.getUnlockedMilestoneCount()) / 100.0f;
            float healAmount = event.getAmount() * percentage;
            float cap = attacker.getMaxHealth() * (float) ModConfig.LIFESTEAL_CAP_RATIO.get();
            healAmount = Math.min(healAmount, cap);

            if (healAmount > 0.0F) {
                attacker.heal(healAmount);
            }
        });
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/handler/RecoveryHandler.java
git commit -m "feat: 添加休养生息和嗜血能力效果处理器"
```

---

### Task 8: KnockbackResistHandler — 不动如山属性管理

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/handler/KnockbackResistHandler.java`

- [ ] **Step 1: 编写 KnockbackResistHandler**

```java
package com.rosycentury.adventure_power.handler;

import com.rosycentury.adventure_power.AdventurePower;
import com.rosycentury.adventure_power.ability.Ability;
import com.rosycentury.adventure_power.ability.AbilityRegistry;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 不动如山能力处理器 — 管理原版击退抗性属性值。
 * <p>
 * 不动如山直接操作 {@link Attributes#KNOCKBACK_RESISTANCE} 属性，
 * 不自行拦截击退计算，与其他模组的击退修改保持兼容。
 * <p>
 * 每 tick 检查能力开关和里程碑变化，自动同步属性值。
 * 登出时重置属性为 0（防止残留影响其他世界/角色）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class KnockbackResistHandler {

    /**
     * 每 tick 检查：不动如山能力启用 → 设置击退抗性属性；
     * 能力禁用 → 重置为 0。仅在值发生变化时写入，避免无效操作。
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

        boolean shouldHave = progress.isAbilityEnabled("knockback_resist");
        var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr == null) return;

        double currentVal = attr.getBaseValue();
        boolean hasKBResist = currentVal > 0.001;

        if (shouldHave && !hasKBResist) {
            Ability ability = AbilityRegistry.get("knockback_resist");
            if (ability != null) {
                float percent = ability.value(progress.getUnlockedMilestoneCount());
                attr.setBaseValue(percent / 100.0);
            }
        } else if (!shouldHave && hasKBResist) {
            attr.setBaseValue(0.0);
        } else if (shouldHave && hasKBResist) {
            // 能力启用中，检查里程碑是否变化 → 更新值
            Ability ability = AbilityRegistry.get("knockback_resist");
            if (ability != null) {
                float expected = ability.value(progress.getUnlockedMilestoneCount()) / 100.0f;
                if (Math.abs(currentVal - expected) > 0.001) {
                    attr.setBaseValue(expected);
                }
            }
        }
    }

    /**
     * 维度切换后恢复击退抗性属性（Player.Clone 会重置属性为默认值 0）。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        // 战斗后执行：给其他恢复逻辑（翱翔等）先跑完的机会
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled("knockback_resist")) return;

            Ability ability = AbilityRegistry.get("knockback_resist");
            if (ability == null) return;

            var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (attr == null) return;

            float percent = ability.value(progress.getUnlockedMilestoneCount());
            attr.setBaseValue(percent / 100.0);
        });
    }

    /**
     * 登出时重置属性为 0，防止残留值影响。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr != null && attr.getBaseValue() > 0.001) {
            attr.setBaseValue(0.0);
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/handler/KnockbackResistHandler.java
git commit -m "feat: 添加不动如山能力效果处理器"
```

---

### Task 9: 语言文件 — 中英文翻译

**Files:**
- Modify: `src/main/resources/assets/adventure_power/lang/zh_cn.json`
- Modify: `src/main/resources/assets/adventure_power/lang/en_us.json`

- [ ] **Step 1: 中文翻译**

在 `zh_cn.json` 的 `"ability.adventure_power.void_step.desc"` 之后添加：

```json
  "ability.adventure_power.rapid_recovery": "休养生息",
  "ability.adventure_power.rapid_recovery.desc": "脱战后自动获得生命恢复效果",
  "ability.adventure_power.knockback_resist": "不动如山",
  "ability.adventure_power.knockback_resist.desc": "减少受到的击退距离",
  "ability.adventure_power.lifesteal": "嗜血",
  "ability.adventure_power.lifesteal.desc": "攻击造成伤害时回复自身生命值",
```

中文文件中保持与其他条目一致的缩进风格（使用空格但实际为 tab），条目按字母序或功能组排列均可——此处按新能力顺序插入 void_step 之后。

- [ ] **Step 2: 英文翻译**

在 `en_us.json` 的 `"ability.adventure_power.void_step.desc"` 之后添加：

```json
  "ability.adventure_power.rapid_recovery": "Rapid Recovery",
  "ability.adventure_power.rapid_recovery.desc": "Automatically regenerate health after leaving combat",
  "ability.adventure_power.knockback_resist": "Unshakable",
  "ability.adventure_power.knockback_resist.desc": "Reduce knockback distance taken",
  "ability.adventure_power.lifesteal": "Lifesteal",
  "ability.adventure_power.lifesteal.desc": "Heal yourself when dealing damage to enemies",
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/assets/adventure_power/lang/zh_cn.json src/main/resources/assets/adventure_power/lang/en_us.json
git commit -m "feat: 添加三个新能力的中英文翻译"
```

---

### Task 10: 构建验证

- [ ] **Step 1: 完整构建**

```bash
cd "D:\download\模组\冒险的力量" && ./gradlew build -x test
```

预期输出: `BUILD SUCCESSFUL`

- [ ] **Step 2: 确认构件生成**

```bash
ls -la build/libs/adventure_power-1.0.0.jar
```

---

## 交互验证清单

| 场景 | 预期行为 |
|------|---------|
| 休养生息 + 受击 | 受击后再生效果中断，延迟 5 秒后重新开始 |
| 休养生息 + 恩赐永驻 | 再生 Buff 被恩赐永驻延长保底时间 |
| 休养生息 + 满血 | 满血时不消耗资源，跳过再生施压 |
| 不动如山 + 切换开关 | 关闭后属性立即重置为 0 |
| 不动如山 + 跨维度 | 属性通过 PlayerEvent.Clone 自动恢复 |
| 嗜血 + 淬魂 | 淬魂内部伤害不触发嗜血（防递归） |
| 嗜血 + 高血量 Boss | 单次吸血上限为最大生命值的 20% |
| 嗜血 + 训服生物 | 友好火力保护跳过，不对训服生物吸血 |
| 三个新能力 + 面板 | 在能力管理界面正确显示和切换 |
