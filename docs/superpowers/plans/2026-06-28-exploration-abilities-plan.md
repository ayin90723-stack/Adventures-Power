# 探索/采集能力实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增大地之力(digging_power)、无形之手(extended_reach)、鸿运当头(fortune_favor)、坚韧之躯(vitality)四个探索/采集能力

**Architecture:** 4 Ability 元数据类 + 2 Handler（ExplorationAbilityHandler 处理属性和 BreakSpeed，FortuneFavorHandler 处理 LootingLevelEvent）+ 1 Mixin（FortuneFavorMixin 拦截 EnchantmentHelper 加时运等级）+ 1 工具类（FortuneContext 线程局部上下文传送方块破坏者引用）。同时修复死亡抗拒硬编码 20.0F 的问题。

**Tech Stack:** Java 17, Forge 1.20.1 47.4.10, Mixin 0.8, Forge LootingLevelEvent + PlayerEvent.BreakSpeed, Attributes系统

---

## 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `ability/DiggingPowerAbility.java` | 大地之力元数据 |
| 新增 | `ability/ExtendedReachAbility.java` | 无形之手元数据 |
| 新增 | `ability/FortuneFavorAbility.java` | 鸿运当头元数据 |
| 新增 | `ability/VitalityAbility.java` | 坚韧之躯元数据 |
| 新增 | `handler/ExplorationAbilityHandler.java` | 大地之力 + 无形之手 + 坚韧之躯 |
| 新增 | `handler/FortuneFavorHandler.java` | 鸿运当头：LootingLevelEvent 抢夺 |
| 新增 | `mixin/FortuneFavorMixin.java` | 鸿运当头：Mixin 时运 |
| 新增 | `util/FortuneContext.java` | 时运 Mixin 上下文传递 |
| 修改 | `config/ModConfig.java` | 4 组配置 |
| 修改 | `ability/AbilityRegistry.java` | 注册 4 个能力 |
| 修改 | `capability/AdventureProgressCapability.java` | KNOWN_ABILITIES + DeathDefy 修复 |
| 修改 | `resources/mixins.adventure_power.json` | 注册 Mixin |
| 修改 | `lang/zh_cn.json` + `en_us.json` | 翻译 |

---

### Task 1: ModConfig — 添加四组配置

**Files:**
- Modify: `src/main/java/com/rosycentury/adventure_power/config/ModConfig.java`

- [ ] **Step 1: 添加字段声明**

在 `VITALITY_*` 相关位置（坚韧之躯之前的配置之后）添加。实际插入位置在嗜血配置字段之后：

```java
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
```

- [ ] **Step 2: 添加初始化代码**

在 static 块中，嗜血 pop 之后、能力数值 pop 之前添加：

```java
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
```

- [ ] **Step 3: 验证编译**

```bash
cd "D:\download\模组\冒险的力量" && ./gradlew build -x test
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/config/ModConfig.java
git commit -m "feat: 添加大地之力/无形之手/鸿运当头/坚韧之躯配置项"
```

---

### Task 2: DiggingPowerAbility — 大地之力

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/ability/DiggingPowerAbility.java`

- [ ] **Step 1: 编写类**

```java
package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 大地之力 — 提升挖掘速度。
 * 解锁条件：1 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：1.3x → 1.75x
 */
public class DiggingPowerAbility implements Ability {

    @Override
    public String id() {
        return "digging_power";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.digging_power");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.digging_power.desc");
    }

    @Override
    public int requiredMilestones() {
        return 1;
    }

    @Override
    public float value(int milestones) {
        return (float) (ModConfig.DIGGING_POWER_BASE.get()
            + ModConfig.DIGGING_POWER_PER_MILESTONE.get() * (milestones - requiredMilestones()));
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/ability/DiggingPowerAbility.java
git commit -m "feat: 添加大地之力能力元数据"
```

---

### Task 3: ExtendedReachAbility — 无形之手

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/ability/ExtendedReachAbility.java`

- [ ] **Step 1: 编写类**

```java
package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 无形之手 — 增加方块交互距离。
 * 解锁条件：4 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：+1.0 → +2.8 格
 */
public class ExtendedReachAbility implements Ability {

    @Override
    public String id() {
        return "extended_reach";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.extended_reach");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.extended_reach.desc");
    }

    @Override
    public int requiredMilestones() {
        return 4;
    }

    @Override
    public float value(int milestones) {
        return (float) (ModConfig.EXTENDED_REACH_BASE.get()
            + ModConfig.EXTENDED_REACH_PER_MILESTONE.get() * (milestones - requiredMilestones()));
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/ability/ExtendedReachAbility.java
git commit -m "feat: 添加无形之手能力元数据"
```

---

### Task 4: FortuneFavorAbility — 鸿运当头

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/ability/FortuneFavorAbility.java`

- [ ] **Step 1: 编写类**

```java
package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 鸿运当头 — 提升时运和抢夺等级。
 * 解锁条件：5 里程碑
 * 成长公式：bonus = base + step × ((milestones - required) / 2)
 * 默认：里程碑5=+1, 7=+2, 9=+3
 */
public class FortuneFavorAbility implements Ability {

    @Override
    public String id() {
        return "fortune_favor";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.fortune_favor");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.fortune_favor.desc");
    }

    @Override
    public int requiredMilestones() {
        return 5;
    }

    @Override
    public float value(int milestones) {
        int steps = (milestones - requiredMilestones()) / 2;
        return ModConfig.FORTUNE_FAVOR_BONUS_BASE.get()
            + ModConfig.FORTUNE_FAVOR_BONUS_STEP.get() * steps;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/ability/FortuneFavorAbility.java
git commit -m "feat: 添加鸿运当头能力元数据"
```

---

### Task 5: VitalityAbility — 坚韧之躯

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/ability/VitalityAbility.java`

- [ ] **Step 1: 编写类**

```java
package com.rosycentury.adventure_power.ability;

import com.rosycentury.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 坚韧之躯 — 提高最大生命值。
 * 解锁条件：7 里程碑
 * 成长公式：base + per_milestone × (milestones - required)
 * 默认范围：+4 → +12（+2心 → +6心）
 */
public class VitalityAbility implements Ability {

    @Override
    public String id() {
        return "vitality";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.vitality");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.vitality.desc");
    }

    @Override
    public int requiredMilestones() {
        return 7;
    }

    @Override
    public float value(int milestones) {
        return (float) (ModConfig.VITALITY_BASE.get()
            + ModConfig.VITALITY_PER_MILESTONE.get() * (milestones - requiredMilestones()));
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/ability/VitalityAbility.java
git commit -m "feat: 添加坚韧之躯能力元数据"
```

---

### Task 6: AbilityRegistry — 注册新能力

**Files:**
- Modify: `src/main/java/com/rosycentury/adventure_power/ability/AbilityRegistry.java`

- [ ] **Step 1: 按里程碑顺序插入**

在 `register(new AgilityAbility());` 之后添加 `register(new DiggingPowerAbility());`（里程碑1）

在 `register(new DamageResistAbility());` 之后添加 `register(new ExtendedReachAbility());`（里程碑4）

在 `register(new UndyingGearAbility());` 之后添加 `register(new FortuneFavorAbility());`（里程碑5）

在 `register(new HealingBlockAbility());` 之后添加 `register(new VitalityAbility());`（里程碑7）

- [ ] **Step 2: 验证编译并提交**

```bash
cd "D:\download\模组\冒险的力量" && ./gradlew build -x test
git add src/main/java/com/rosycentury/adventure_power/ability/AbilityRegistry.java
git commit -m "feat: 注册大地之力/无形之手/鸿运当头/坚韧之躯"
```

---

### Task 7: KNOWN_ABILITIES + DeathDefy 修复

**Files:**
- Modify: `src/main/java/com/rosycentury/adventure_power/capability/AdventureProgressCapability.java`

- [ ] **Step 1: 添加 KNOWN_ABILITIES 条目**

按里程碑顺序插入。在 `agility` 之后添加 `digging_power`，在 `damage_resist` 之后添加 `extended_reach`，在 `undying_gear` 之后添加 `fortune_favor`，在 `healing_block` 之后添加 `vitality`：

```java
        KNOWN_ABILITIES.put("agility", Component.translatable("ability.adventure_power.agility"));
        KNOWN_ABILITIES.put("digging_power", Component.translatable("ability.adventure_power.digging_power"));
        KNOWN_ABILITIES.put("perpetual_blessing", Component.translatable("ability.adventure_power.perpetual_blessing"));
        // ...
        KNOWN_ABILITIES.put("damage_resist", Component.translatable("ability.adventure_power.damage_resist"));
        KNOWN_ABILITIES.put("extended_reach", Component.translatable("ability.adventure_power.extended_reach"));
        KNOWN_ABILITIES.put("undying_gear", Component.translatable("ability.adventure_power.undying_gear"));
        KNOWN_ABILITIES.put("fortune_favor", Component.translatable("ability.adventure_power.fortune_favor"));
        // ...
        KNOWN_ABILITIES.put("healing_block", Component.translatable("ability.adventure_power.healing_block"));
        KNOWN_ABILITIES.put("vitality", Component.translatable("ability.adventure_power.vitality"));
```

- [ ] **Step 2: 修复死亡抗拒硬编码回血值**

在 `onPlayerDeath` 方法中（处理死亡抗拒的地方），将：
```java
float restoreHealth = 20.0F;
```
改为：
```java
float restoreHealth = player.getMaxHealth();
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/capability/AdventureProgressCapability.java
git commit -m "feat: 注册新能力条目并修复死亡抗拒使用getMaxHealth"
```

---

### Task 8: ExplorationAbilityHandler — 大地之力 + 无形之手 + 坚韧之躯

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/handler/ExplorationAbilityHandler.java`

- [ ] **Step 1: 编写 Handler**

```java
package com.rosycentury.adventure_power.handler;

import com.rosycentury.adventure_power.AdventurePower;
import com.rosycentury.adventure_power.ability.Ability;
import com.rosycentury.adventure_power.ability.AbilityRegistry;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 探索/采集类能力效果处理器。
 * <p>
 * 处理 3 种非战斗能力的实际效果：
 * <ul>
 *   <li>大地之力 (digging_power) — BreakSpeed 事件提升挖掘速度</li>
 *   <li>无形之手 (extended_reach) — 设置 BLOCK_REACH 属性</li>
 *   <li>坚韧之躯 (vitality) — 设置 MAX_HEALTH 属性</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ExplorationAbilityHandler {

    // ==================== 大地之力 — 挖掘速度 ====================

    /**
     * 大地之力：提升玩家挖掘速度。公式：originalSpeed × multiplier
     */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("digging_power")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            Ability ability = AbilityRegistry.get("digging_power");
            if (ability == null) return;

            float multiplier = ability.value(progress.getUnlockedMilestoneCount());
            event.setNewSpeed(event.getOriginalSpeed() * multiplier);
        });
    }

    // ==================== 无形之手 + 坚韧之躯 — 属性管理 ====================

    /**
     * 每 tick 同步无形之手（BLOCK_REACH）和坚韧之躯（MAX_HEALTH）属性值。
     * 仅在值变化时写入。
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

        // ---- 无形之手 ----
        syncReachAttribute(player, progress.isAbilityEnabled("extended_reach"),
            progress.getUnlockedMilestoneCount());

        // ---- 坚韧之躯 ----
        syncVitalityAttribute(player, progress.isAbilityEnabled("vitality"),
            progress.getUnlockedMilestoneCount());
    }

    private static void syncReachAttribute(Player player, boolean enabled, int milestones) {
        var attr = player.getAttribute(ForgeMod.BLOCK_REACH.get());
        if (attr == null) return;

        double defaultValue = ForgeMod.BLOCK_REACH.get().getDefaultValue();
        double currentVal = attr.getBaseValue();

        if (enabled) {
            Ability ability = AbilityRegistry.get("extended_reach");
            if (ability == null) return;
            float bonus = ability.value(milestones);
            double expected = defaultValue + bonus;
            if (Math.abs(currentVal - expected) > 0.001) {
                attr.setBaseValue(expected);
            }
        } else {
            if (Math.abs(currentVal - defaultValue) > 0.001) {
                attr.setBaseValue(defaultValue);
            }
        }
    }

    private static void syncVitalityAttribute(Player player, boolean enabled, int milestones) {
        var attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;

        double currentVal = attr.getBaseValue();

        if (enabled) {
            Ability ability = AbilityRegistry.get("vitality");
            if (ability == null) return;
            float bonus = ability.value(milestones);
            double expected = 20.0 + bonus;
            if (Math.abs(currentVal - expected) > 0.001) {
                attr.setBaseValue(expected);
                // 如果当前血量超过新上限，裁剪到新上限
                if (player.getHealth() > expected) {
                    player.setHealth((float) expected);
                }
            }
        } else {
            if (Math.abs(currentVal - 20.0) > 0.001) {
                // 裁剪血量到 20 以下再调低上限，防止血量卡在异常值
                if (player.getHealth() > 20.0F) {
                    player.setHealth(20.0F);
                }
                attr.setBaseValue(20.0);
            }
        }
    }

    // ==================== 维度切换/重生恢复 ====================

    /**
     * 维度切换或重生后恢复属性值。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
            int milestones = progress.getUnlockedMilestoneCount();

            if (progress.isAbilityEnabled("extended_reach")) {
                syncReachAttribute(player, true, milestones);
            }
            if (progress.isAbilityEnabled("vitality")) {
                syncVitalityAttribute(player, true, milestones);
            }
        });
    }

    // ==================== 登出清理 ====================

    /**
     * 登出时重置属性为默认值，防止残留影响。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        var reachAttr = player.getAttribute(ForgeMod.BLOCK_REACH.get());
        if (reachAttr != null && Math.abs(reachAttr.getBaseValue() - ForgeMod.BLOCK_REACH.get().getDefaultValue()) > 0.001) {
            reachAttr.setBaseValue(ForgeMod.BLOCK_REACH.get().getDefaultValue());
        }

        var healthAttr = player.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null && Math.abs(healthAttr.getBaseValue() - 20.0) > 0.001) {
            healthAttr.setBaseValue(20.0);
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/handler/ExplorationAbilityHandler.java
git commit -m "feat: 添加大地之力/无形之手/坚韧之躯效果处理器"
```

---

### Task 9: FortuneContext — Mixin 上下文工具类

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/util/FortuneContext.java`

- [ ] **Step 1: 编写类**

```java
package com.rosycentury.adventure_power.util;

import net.minecraft.world.entity.player.Player;

/**
 * 线程局部存储，用于在方块破坏→附魔查询之间传递玩家引用。
 * <p>
 * Mixin 无法直接在 EnchantmentHelper 中获取破坏者（ItemStack 不持有所有者的引用），
 * 通过此上下文在 BreakEvent 处理前设置玩家，供 Mixin 读取。
 */
public class FortuneContext {
    private static final ThreadLocal<Player> CURRENT_BREAKER = new ThreadLocal<>();

    public static void setBreaker(Player player) {
        CURRENT_BREAKER.set(player);
    }

    public static Player getBreaker() {
        return CURRENT_BREAKER.get();
    }

    public static void clear() {
        CURRENT_BREAKER.remove();
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/util/FortuneContext.java
git commit -m "feat: 添加时运 Mixin 上下文工具类"
```

---

### Task 10: FortuneFavorHandler — 鸿运当头处理（抢夺+LootingLevelEvent）

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/handler/FortuneFavorHandler.java`

- [ ] **Step 1: 编写 Handler**

```java
package com.rosycentury.adventure_power.handler;

import com.rosycentury.adventure_power.AdventurePower;
import com.rosycentury.adventure_power.ability.Ability;
import com.rosycentury.adventure_power.ability.AbilityRegistry;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.util.FortuneContext;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LootingLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 鸿运当头能力处理器。
 * <p>
 * 处理 2 种掉落增强：
 * <ul>
 *   <li>抢夺 — LootingLevelEvent（Forge 内置事件，直接修改等级）</li>
 *   <li>时运 — BlockEvent.BreakEvent（设置 FortuneContext 供 Mixin 读取）</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FortuneFavorHandler {

    /**
     * 抢夺：攻击者击杀生物时增加抢夺等级。在工具已有等级上叠加。
     */
    @SubscribeEvent
    public static void onLootingLevel(LootingLevelEvent event) {
        if (event.getDamageSource() == null) return;
        if (!(event.getDamageSource().getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("fortune_favor")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            Ability ability = AbilityRegistry.get("fortune_favor");
            if (ability == null) return;

            int bonus = (int) ability.value(progress.getUnlockedMilestoneCount());
            event.setLootingLevel(event.getLootingLevel() + bonus);
        });
    }

    /**
     * 时运：方块破坏前将玩家引用写入 FortuneContext，供 Mixin 读取。
     * Mixin（FortuneFavorMixin）在 EnchantmentHelper 中检测上下文并叠加时运等级。
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("fortune_favor")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            FortuneContext.setBreaker(player);
        });
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/handler/FortuneFavorHandler.java
git commit -m "feat: 添加鸿运当头效果处理器"
```

---

### Task 11: FortuneFavorMixin — 时运 Mixin

**Files:**
- Create: `src/main/java/com/rosycentury/adventure_power/mixin/FortuneFavorMixin.java`

- [ ] **Step 1: 编写 Mixin**

```java
package com.rosycentury.adventure_power.mixin;

import com.rosycentury.adventure_power.ability.Ability;
import com.rosycentury.adventure_power.ability.AbilityRegistry;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.util.FortuneContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 鸿运当头 — 时运加成 Mixin。
 * <p>
 * 拦截 {@link EnchantmentHelper#getItemEnchantmentLevel(Enchantment, ItemStack)}
 * 当查询时运等级（BLOCK_FORTUNE）时，检测 {@link FortuneContext} 中是否有破坏者，
 * 若有且破坏者启用了鸿运当头，则在原始等级上叠加能力加成。
 * <p>
 * 目标方法 SRG：m_44843_ (Lnet/minecraft/world/item/enchantment/Enchantment;Lnet/minecraft/world/item/ItemStack;)I
 */
@Mixin(EnchantmentHelper.class)
public class FortuneFavorMixin {

    @Inject(method = "m_44843_", at = @At("RETURN"), cancellable = true)
    private static void onGetItemEnchantmentLevel(Enchantment enchantment, ItemStack stack,
                                                   CallbackInfoReturnable<Integer> cir) {
        // 只处理时运查询
        if (enchantment != Enchantments.BLOCK_FORTUNE) return;

        Player player = FortuneContext.getBreaker();
        if (player == null) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("fortune_favor")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            Ability ability = AbilityRegistry.get("fortune_favor");
            if (ability == null) return;

            int bonus = (int) ability.value(progress.getUnlockedMilestoneCount());
            cir.setReturnValue(cir.getReturnValue() + bonus);
        });
    }
}
```

- [ ] **Step 2: 验证 Mixin 编译**

```bash
cd "D:\download\模组\冒险的力量" && ./gradlew build -x test
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/rosycentury/adventure_power/mixin/FortuneFavorMixin.java
git commit -m "feat: 添加鸿运当头时运Mixin"
```

---

### Task 12: 注册 Mixin + 语言文件

**Files:**
- Modify: `src/main/resources/mixins.adventure_power.json`
- Modify: `src/main/resources/assets/adventure_power/lang/zh_cn.json`
- Modify: `src/main/resources/assets/adventure_power/lang/en_us.json`

- [ ] **Step 1: 在 mixins JSON 中注册新 Mixin**

在 `mixins` 数组中添加 `"FortuneFavorMixin"`：

```json
    "mixins": [
        "SeeAndSlashMixin",
        "SeeAndSlashLivingEntityAccessor",
        "SeeAndSlashLivingEntityMixin",
        "SeeAndSlashPlayerAttackMixin",
        "UndyingSlashMixin",
        "TrueHealthMixin",
        "RejectHealthManipMixin",
        "RejectHealthManipAttributeMixin",
        "DeathDefyMixin",
        "LegacyHurtAndBreakMixin",
        "PurifiedSoulMixin",
        "FortuneFavorMixin"
    ],
```

- [ ] **Step 2: 中文翻译**

在 `zh_cn.json` 的能力条目中按里程碑顺序添加：

```json
  "ability.adventure_power.digging_power": "大地之力",
  "ability.adventure_power.digging_power.desc": "提升挖掘速度",
  "ability.adventure_power.extended_reach": "无形之手",
  "ability.adventure_power.extended_reach.desc": "增加方块交互距离",
  "ability.adventure_power.fortune_favor": "鸿运当头",
  "ability.adventure_power.fortune_favor.desc": "提升时运和抢夺等级",
  "ability.adventure_power.vitality": "坚韧之躯",
  "ability.adventure_power.vitality.desc": "提高最大生命值",
```

- [ ] **Step 3: 英文翻译**

```json
  "ability.adventure_power.digging_power": "Earthly Power",
  "ability.adventure_power.digging_power.desc": "Increase mining speed",
  "ability.adventure_power.extended_reach": "Unseen Hand",
  "ability.adventure_power.extended_reach.desc": "Increase block interaction range",
  "ability.adventure_power.fortune_favor": "Fortune's Favor",
  "ability.adventure_power.fortune_favor.desc": "Increase Fortune and Looting levels",
  "ability.adventure_power.vitality": "Iron Constitution",
  "ability.adventure_power.vitality.desc": "Increase maximum health",
```

- [ ] **Step 4: 验证编译并提交**

```bash
cd "D:\download\模组\冒险的力量" && ./gradlew build -x test
git add src/main/resources/mixins.adventure_power.json src/main/resources/assets/adventure_power/lang/zh_cn.json src/main/resources/assets/adventure_power/lang/en_us.json
git commit -m "feat: 注册FortuneFavorMixin并添加四能力翻译"
```

---

### Task 13: 构建验证

- [ ] **Step 1: 完整构建**

```bash
cd "D:\download\模组\冒险的力量" && ./gradlew build -x test
```

预期输出: `BUILD SUCCESSFUL`

- [ ] **Step 2: 确认构件**

```bash
ls -la build/libs/adventure_power-1.0.0.jar
```

---

## 交互验证清单

| 场景 | 预期行为 |
|------|---------|
| 大地之力 + 急迫效果 | 乘法叠加 |
| 无形之手 + 跨维度 | 属性通过 Clone 事件恢复 |
| 鸿运当头 + 工具自带时运 III | 实际时运 IV（3+1） |
| 鸿运当头 + 武器自带抢夺 II | 实际抢夺 III（2+1） |
| 鸿运当头 + 白板工具 | 实际时运 I（0+1） |
| 坚韧之躯 + 跨维度 | 属性通过 Clone 恢复，血量裁剪到上限 |
| 坚韧之躯 + 死亡抗拒 | 免死回血使用 getMaxHealth() 而非硬编码 20 |
| 坚韧之躯 + 能力关闭 | MAX_HEALTH 回到 20，当前血量裁剪至 20 |
