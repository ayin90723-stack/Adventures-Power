# 里程碑数据包化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将里程碑系统从 Java 硬编码枚举改为数据包 JSON 驱动，支持玩家自定义里程碑

**Architecture:** 引入 `MilestoneRegistry` 在 ServerAboutToStartEvent 时从数据包 JSON 加载里程碑定义，替代 `Milestone` 枚举。`Ability` 接口的 `requiredMilestones()` 移除，改为 `MilestoneRegistry` 在加载后通过 `setCountAtUnlock()` 动态设置。`IAdventureProgress` 的里程碑存储从 `EnumSet<Milestone>` 改为 `Set<String>`。客户端通过 `AdventureSyncPacket` 附带里程碑元数据副本来渲染 tooltip。

**Tech Stack:** Java 17, Forge 1.20.1 47.4.10, Gson, Minecraft Advancement 系统

---

### Task 0: 新数据模型（Milestone record + TriggerDef）

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/milestone/Milestone.java`
- Create: `src/main/java/com/ayin90723/adventure_power/util/TriggerDef.java`

- [ ] **Step 1: 将 Milestone 枚举改为 record**

删除枚举，替换为 record 类。同时保留 `fromId()` 静态方法作为便利查询（委托给 `MilestoneRegistry`）。

```java
package com.ayin90723.adventure_power.milestone;

import com.ayin90723.adventure_power.util.TriggerDef;
import net.minecraft.resources.ResourceLocation;
import javax.annotation.Nullable;
import java.util.List;

/**
 * 冒险里程碑 — 由 MilestoneRegistry 从数据包 JSON 加载。
 * 不再使用枚举，改为 record 以支持数据驱动。
 */
public record Milestone(
    String id,
    String name,
    List<String> abilities,
    ResourceLocation advancement,
    @Nullable TriggerDef trigger
) {
    /** 根据 id 查找里程碑，委托给 MilestoneRegistry */
    public static Milestone fromId(String id) {
        return com.ayin90723.adventure_power.util.MilestoneRegistry.getById(id);
    }
}
```

- [ ] **Step 2: 创建 TriggerDef record**

```java
package com.ayin90723.adventure_power.util;

import net.minecraft.resources.ResourceLocation;
import javax.annotation.Nullable;

/**
 * 里程碑触发器定义 — 对应 milestones.json 中 trigger 字段。
 * type 为预置值之一：survive_night / first_death / first_trade / y_below / first_kill
 */
public record TriggerDef(
    String type,
    @Nullable Integer y,
    @Nullable ResourceLocation entity
) {}
```

- [ ] **Step 3: 编译验证**

```bash
cd D:\download\模组\冒险的力量
./gradlew compileJava -x test
```

预期: 大量编译错误（其他文件还在引用旧的 `Milestone` 枚举）— 这是正常的，后续任务逐步修复。

---

### Task 1: Ability 接口变更

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/ability/Ability.java`

- [ ] **Step 1: 移除 requiredMilestones，新增 setCountAtUnlock**

```java
package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 冒险能力接口。
 * 所有能力实现此接口。countAtUnlock 由 MilestoneRegistry 在加载 JSON 后设置。
 */
public interface Ability {

    /** 唯一标识，如 "agility" */
    String id();

    /** 显示名称 */
    Component name();

    /** 描述文本 */
    Component description();

    /**
     * 当前数值。count = 玩家已解锁的里程碑总数。
     * 无成长的能力可返回 -1 表示"已解锁即完整"。
     */
    float value(int count);

    /**
     * MilestoneRegistry 加载 JSON 后调用，设置该能力解锁所需的里程碑数。
     * 默认实现为空（向后兼容不需要此信息的旧能力）。
     */
    default void setCountAtUnlock(int n) {}

    /** 启用时回调（服务端） */
    default void onEnable(Player player) {}

    /** 禁用时回调（服务端） */
    default void onDisable(Player player) {}

    /** 每 tick 回调（服务端），默认空 */
    default void onTick(Player player) {}
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileJava -x test
```

预期: 编译错误减少（`Ability` 接口不再报错），但 25 个 Ability 实现类仍报错（未实现 `requiredMilestones()` → 现在它们需要实现新方法签名）。

---

### Task 2: 标准公式 Ability 实现（23 个）

**Files:** 修改 23 个文件。每个文件改动相同模式：加 `countAtUnlock` 字段 + `setCountAtUnlock()` + 改 `value()`。

**模式**（以 AgilityAbility 为例）:

修改前:
```java
@Override
public int requiredMilestones() {
    return 1;
}

@Override
public float value(int milestones) {
    return ModConfig.AGILITY_BASE.get() + ModConfig.AGILITY_PER_MILESTONE.get() * (milestones - requiredMilestones());
}
```

修改后:
```java
private int countAtUnlock = 1;

@Override
public void setCountAtUnlock(int n) {
    this.countAtUnlock = n;
}

@Override
public float value(int count) {
    return ModConfig.AGILITY_BASE.get() + ModConfig.AGILITY_PER_MILESTONE.get() * (count - countAtUnlock);
}
```

- [ ] **Step 1: 修改 AgilityAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/AgilityAbility.java`

删除 `requiredMilestones()` 方法。添加 `private int countAtUnlock = 1;` 和 `setCountAtUnlock()`。将 `value(int milestones)` 参数改为 `value(int count)`，内部 `requiredMilestones()` 引用改为 `countAtUnlock`。

- [ ] **Step 2: 修改 DiggingPowerAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/DiggingPowerAbility.java`

同上模式，`countAtUnlock = 1`。

- [ ] **Step 3: 修改 PerpetualBlessingAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/PerpetualBlessingAbility.java`

同上模式，`countAtUnlock = 1`。注意 `value()` 内部公式不同（`value(int milestones)` → `value(int count)`，使用 `count` 替代 `milestones`）。

- [ ] **Step 4: 修改 VoidStepAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/VoidStepAbility.java`

`countAtUnlock = 2`。

- [ ] **Step 5: 修改 RapidRecoveryAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/RapidRecoveryAbility.java`

`countAtUnlock = 2`。

- [ ] **Step 6: 修改 SoulBindAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/SoulBindAbility.java`

`countAtUnlock = 3`。此能力 `value()` 可能返回 -1（无成长），仅需加字段和 setter。

- [ ] **Step 7: 修改 KnockbackResistAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/KnockbackResistAbility.java`

`countAtUnlock = 3`。

- [ ] **Step 8: 修改 DamageResistAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/DamageResistAbility.java`

`countAtUnlock = 4`。

- [ ] **Step 9: 修改 ExtendedReachAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/ExtendedReachAbility.java`

`countAtUnlock = 4`。

- [ ] **Step 10: 修改 UndyingGearAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/UndyingGearAbility.java`

`countAtUnlock = 5`。此能力 `value()` 返回 -1。

- [ ] **Step 11: 修改 FortuneFavorAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/FortuneFavorAbility.java`

`countAtUnlock = 5`。

- [ ] **Step 12: 修改 EnvImmunityAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/EnvImmunityAbility.java`

`countAtUnlock = 6`。`value()` 返回 -1。

- [ ] **Step 13: 修改 LifestealAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/LifestealAbility.java`

`countAtUnlock = 6`。

- [ ] **Step 14: 修改 HealingBlockAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/HealingBlockAbility.java`

`countAtUnlock = 7`。

- [ ] **Step 15: 修改 VitalityAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/VitalityAbility.java`

`countAtUnlock = 7`。

- [ ] **Step 16: 修改 ResilienceAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/ResilienceAbility.java`

`countAtUnlock = 8`。此能力 `value()` 使用绝对值 switch，参数名改为 `count` 但公式逻辑保持一致：`switch(count) { case 8 -> ...; case 9 -> ...; case 10 -> ...; }`。

- [ ] **Step 17: 修改 PurifiedSoulAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/PurifiedSoulAbility.java`

`countAtUnlock = 8`。`value()` 返回 -1。

- [ ] **Step 18: 修改 SoarAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/SoarAbility.java`

`countAtUnlock = 9`。`value()` 返回 -1。

- [ ] **Step 19: 修改 SoulQuenchAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/SoulQuenchAbility.java`

`countAtUnlock = 9`。保留 `flatDamage(int count)` 和 `hpRatio(int count)` 的自定义方法。

```java
private int countAtUnlock = 9;

@Override
public void setCountAtUnlock(int n) {
    this.countAtUnlock = n;
}

@Override
public float value(int count) {
    return -1; // 无标准成长公式
}

public int flatDamage(int count) {
    return count >= 10 ? ModConfig.SOUL_QUENCH_FLAT_DAMAGE_10.get() : ModConfig.SOUL_QUENCH_FLAT_DAMAGE_9.get();
}

public float hpRatio(int count) {
    return (float)(double)(count >= 10 ? ModConfig.SOUL_QUENCH_HP_RATIO_10.get() : ModConfig.SOUL_QUENCH_HP_RATIO_9.get());
}
```

- [ ] **Step 20: 修改 PiercingGazeAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/PiercingGazeAbility.java`

`countAtUnlock = 9`。`value()` 返回 -1。

- [ ] **Step 21: 修改 DeathDefyAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/DeathDefyAbility.java`

`countAtUnlock = 9`。`value()` 返回 -1。

- [ ] **Step 22: 修改 ShadowKillAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/ShadowKillAbility.java`

`countAtUnlock = 10`。

```java
private int countAtUnlock = 10;

@Override
public void setCountAtUnlock(int n) {
    this.countAtUnlock = n;
}

@Override
public float value(int count) {
    return flatDamage(); // 无成长
}

public int flatDamage() {
    return ModConfig.SHADOW_KILL_FLAT_DAMAGE.get();
}

public float hpRatio() {
    return (float)(double)ModConfig.SHADOW_KILL_HP_RATIO.get();
}
```

- [ ] **Step 23: 修改 TrueHealthAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/TrueHealthAbility.java`

`countAtUnlock = 10`。`value()` 返回 -1。

- [ ] **Step 24: 修改 RejectManipAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/RejectManipAbility.java`

`countAtUnlock = 10`。`value()` 返回 -1。

- [ ] **Step 25: 修改 ActiveSkillAbility**

File: `src/main/java/com/ayin90723/adventure_power/ability/ActiveSkillAbility.java`

`countAtUnlock = 10`。`value()` 返回 -1。

---

### Task 3: AbilityRegistry 变更

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/ability/AbilityRegistry.java`

- [ ] **Step 1: 移除旧方法，新增 countAtUnlock 管理**

```java
package com.ayin90723.adventure_power.ability;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 能力注册表 — 25 种冒险能力。
 * countAtUnlock 映射由 MilestoneRegistry 在加载 JSON 后填充。
 */
public class AbilityRegistry {

    public static final Map<String, Ability> ALL = new LinkedHashMap<>();

    /** 能力 ID → 动态 countAtUnlock 覆盖值（由 MilestoneRegistry 加载后设置） */
    private static final Map<String, Integer> COUNT_AT_UNLOCK_OVERRIDES = new HashMap<>();

    static {
        register(new AgilityAbility());
        register(new DiggingPowerAbility());
        register(new PerpetualBlessingAbility());
        register(new VoidStepAbility());
        register(new RapidRecoveryAbility());
        register(new SoulBindAbility());
        register(new KnockbackResistAbility());
        register(new DamageResistAbility());
        register(new ExtendedReachAbility());
        register(new UndyingGearAbility());
        register(new FortuneFavorAbility());
        register(new EnvImmunityAbility());
        register(new LifestealAbility());
        register(new HealingBlockAbility());
        register(new VitalityAbility());
        register(new ResilienceAbility());
        register(new PurifiedSoulAbility());
        register(new SoarAbility());
        register(new SoulQuenchAbility());
        register(new PiercingGazeAbility());
        register(new DeathDefyAbility());
        register(new ShadowKillAbility());
        register(new TrueHealthAbility());
        register(new RejectManipAbility());
        register(new ActiveSkillAbility());
    }

    private static void register(Ability ability) {
        ALL.put(ability.id(), ability);
    }

    public static Ability get(String id) {
        return ALL.get(id);
    }

    /** MilestoneRegistry 加载 JSON 后调用，为指定能力设置动态 countAtUnlock */
    public static void setCountAtUnlock(String id, int count) {
        COUNT_AT_UNLOCK_OVERRIDES.put(id, count);
        Ability ability = ALL.get(id);
        if (ability != null) {
            ability.setCountAtUnlock(count);
        }
    }

    /** 查询某能力的 countAtUnlock（优先用覆盖值，否则用 ability 内部默认值） */
    public static int getCountAtUnlock(String id) {
        Integer override = COUNT_AT_UNLOCK_OVERRIDES.get(id);
        if (override != null) return override;
        // 回退：ability 内部的 countAtUnlock 默认值通过 reflection 不可靠，
        // 直接返回一个合理的默认值。正常情况下加载 JSON 后一定被设置。
        return 0;
    }

    /** 清除所有动态设置（用于数据包重载前重置） */
    public static void clearCountAtUnlockOverrides() {
        COUNT_AT_UNLOCK_OVERRIDES.clear();
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileJava -x test
```

预期: Ability 实现类编译错误消失。剩余错误集中在 Capability 和 Handler 层（仍在引用旧 `Milestone` 枚举和 `requiredMilestones()`）。

---

### Task 4: 默认 milestones.json

**Files:**
- Create: `src/main/resources/data/adventure_power/adventure_power/milestones.json`

- [ ] **Step 1: 创建默认里程碑 JSON**

```json
{
  "milestones": [
    {
      "id": "first_night",
      "name": "初次夜冕",
      "abilities": ["agility", "digging_power", "perpetual_blessing"],
      "advancement": "adventure_power:first_night",
      "trigger": { "type": "survive_night" }
    },
    {
      "id": "first_death",
      "name": "初尝败绩",
      "abilities": ["void_step", "rapid_recovery"],
      "advancement": "adventure_power:first_death",
      "trigger": { "type": "first_death" }
    },
    {
      "id": "first_trade",
      "name": "初次交易",
      "abilities": ["soul_bind", "knockback_resist"],
      "advancement": "adventure_power:first_trade",
      "trigger": { "type": "first_trade" }
    },
    {
      "id": "first_deep",
      "name": "初探地底",
      "abilities": ["damage_resist", "extended_reach"],
      "advancement": "adventure_power:first_deep",
      "trigger": { "type": "y_below", "y": 0 }
    },
    {
      "id": "first_enchant",
      "name": "初次附魔",
      "abilities": ["undying_gear", "fortune_favor"],
      "advancement": "minecraft:story/enchant_item",
      "trigger": null
    },
    {
      "id": "nether",
      "name": "炽热之门",
      "abilities": ["env_immunity", "lifesteal"],
      "advancement": "minecraft:story/enter_the_nether",
      "trigger": null
    },
    {
      "id": "wither",
      "name": "凋零之陨",
      "abilities": ["healing_block", "vitality"],
      "advancement": "adventure_power:wither",
      "trigger": null
    },
    {
      "id": "warden",
      "name": "幽匿之惧",
      "abilities": ["resilience", "purified_soul"],
      "advancement": "adventure_power:warden",
      "trigger": null
    },
    {
      "id": "dragon",
      "name": "终末之翼",
      "abilities": ["soar", "soul_quench", "piercing_gaze", "death_defy"],
      "advancement": "minecraft:end/kill_dragon",
      "trigger": null
    },
    {
      "id": "elytra",
      "name": "苍穹之证",
      "abilities": ["shadow_kill", "true_health", "reject_manip", "active_skill"],
      "advancement": "minecraft:end/elytra",
      "trigger": null
    }
  ]
}
```

---

### Task 5: MilestoneRegistry（核心新增）

**Files:**
- Create: `src/main/java/com/ayin90723/adventure_power/util/MilestoneRegistry.java`

- [ ] **Step 1: 实现 MilestoneRegistry**

```java
package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 里程碑动态注册表 — 从数据包 JSON 加载里程碑定义。
 * 在 ServerAboutToStartEvent 或 AddReloadListenerEvent 时初始化。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MilestoneRegistry {

    private static final Gson GSON = new Gson();
    private static List<Milestone> milestones = List.of();
    private static Map<ResourceLocation, Milestone> byAdvancement = Map.of();
    private static Map<String, Milestone> byId = Map.of();
    private static boolean initialized = false;

    // ===== 查询方法 =====

    public static List<Milestone> getAll() { return milestones; }
    public static int getMilestoneCount() { return milestones.size(); }
    public static boolean isInitialized() { return initialized; }

    @Nullable
    public static Milestone getByAdvancement(ResourceLocation advId) {
        return byAdvancement.get(advId);
    }

    @Nullable
    public static Milestone getById(String id) {
        return byId.get(id);
    }

    public static boolean contains(String id) {
        return byId.containsKey(id);
    }

    public static int getCountAtUnlock(String abilityId) {
        return AbilityRegistry.getCountAtUnlock(abilityId);
    }

    public static boolean isAbilityAvailable(String abilityId, int unlockedCount) {
        return unlockedCount >= getCountAtUnlock(abilityId);
    }

    /** 获取某里程碑包含的能力 ID 列表 */
    public static List<String> getAbilitiesForMilestone(String milestoneId) {
        Milestone m = byId.get(milestoneId);
        return m != null ? m.abilities() : List.of();
    }

    // ===== 加载逻辑 =====

    /**
     * 从数据包 JSON 加载里程碑数据。
     * 在 ServerAboutToStartEvent 和 AddReloadListenerEvent 时调用。
     * 也用于客户端接收同步数据后的初始化。
     */
    public static void loadFromJson(String namespace, JsonObject root) {
        List<Milestone> loaded = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("milestones");
        if (arr == null || arr.size() == 0) {
            AdventurePower.LOGGER.warn("[MilestoneRegistry] milestones 数组为空，无里程碑可用");
        }

        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            String id = obj.get("id").getAsString();

            // 重复 ID 警告
            if (seenIds.contains(id)) {
                AdventurePower.LOGGER.warn("[MilestoneRegistry] 重复的 milestone ID: {}，使用最后一个", id);
            }
            seenIds.add(id);

            String name = obj.get("name").getAsString();

            // abilities 数组
            List<String> abilities = new ArrayList<>();
            JsonArray abilityArr = obj.getAsJsonArray("abilities");
            if (abilityArr != null) {
                for (JsonElement e : abilityArr) {
                    String abilityId = e.getAsString();
                    if (AbilityRegistry.get(abilityId) == null) {
                        AdventurePower.LOGGER.warn("[MilestoneRegistry] 未知的 ability ID: {}，跳过", abilityId);
                    } else {
                        abilities.add(abilityId);
                    }
                }
            }

            // advancement 字段
            String advStr = obj.has("advancement") && !obj.get("advancement").isJsonNull()
                ? obj.get("advancement").getAsString() : null;
            ResourceLocation advancement = advStr != null ? new ResourceLocation(advStr) : null;

            // trigger 字段
            TriggerDef trigger = null;
            if (obj.has("trigger") && !obj.get("trigger").isJsonNull()) {
                JsonObject trigObj = obj.getAsJsonObject("trigger");
                String type = trigObj.get("type").getAsString();
                Integer y = trigObj.has("y") ? trigObj.get("y").getAsInt() : null;
                ResourceLocation entity = trigObj.has("entity")
                    ? new ResourceLocation(trigObj.get("entity").getAsString()) : null;
                trigger = new TriggerDef(type, y, entity);
            }

            // 验证: 如果 advancement 为空且 trigger 也为空，警告
            if (advancement == null && trigger == null) {
                AdventurePower.LOGGER.warn("[MilestoneRegistry] milestone {} 无 advancement 且无 trigger，永远无法达成", id);
            }

            Milestone m = new Milestone(id, name, List.copyOf(abilities), advancement, trigger);
            loaded.add(m);

            // 为此里程碑的能力设置 countAtUnlock = index + 1
            for (String abilityId : abilities) {
                AbilityRegistry.setCountAtUnlock(abilityId, i + 1);
            }
        }

        // 构建查询索引
        milestones = List.copyOf(loaded);
        byId = new HashMap<>();
        byAdvancement = new HashMap<>();
        for (Milestone m : loaded) {
            byId.put(m.id(), m);
            if (m.advancement() != null) {
                byAdvancement.put(m.advancement(), m);
            }
        }
        initialized = true;

        AdventurePower.LOGGER.info("[MilestoneRegistry] 加载完成: {} 个里程碑", milestones.size());
    }

    /** 数据包重载前清除现有数据 */
    public static void clear() {
        milestones = List.of();
        byAdvancement = Map.of();
        byId = Map.of();
        initialized = false;
        AbilityRegistry.clearCountAtUnlockOverrides();
    }

    // ===== 事件监听 =====

    /**
     * 注册数据包重载监听器。
     * Forge 的 AddReloadListenerEvent 允许在服务端启动和数据包重载时重新加载。
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(GSON, "adventure_power/milestones") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> map,
                                ResourceManager resourceManager,
                                ProfilerFiller profiler) {
                MilestoneRegistry.clear();
                // 查找 adventure_power namespace 下的 milestones.json
                ResourceLocation key = new ResourceLocation(AdventurePower.MODID, "adventure_power/milestones");
                JsonElement element = map.get(key);
                if (element != null && element.isJsonObject()) {
                    MilestoneRegistry.loadFromJson(AdventurePower.MODID, element.getAsJsonObject());
                } else {
                    // 尝试 mod 内置默认 JSON（通过模组资源自动落入 map 中）
                    // 如果 map 中没有，说明数据包未提供，fallback 到内置
                    AdventurePower.LOGGER.warn("[MilestoneRegistry] 未找到 milestones.json，使用内置默认");
                    loadBuiltinDefaults();
                }
            }
        });
    }

    /** 当数据包中无 milestones.json 时使用模组内置默认 */
    private static void loadBuiltinDefaults() {
        String json = "{ \"milestones\": ["
            + "{\"id\":\"first_night\",\"name\":\"初次夜冕\",\"abilities\":[\"agility\",\"digging_power\",\"perpetual_blessing\"],\"advancement\":\"adventure_power:first_night\",\"trigger\":{\"type\":\"survive_night\"}},"
            + "{\"id\":\"first_death\",\"name\":\"初尝败绩\",\"abilities\":[\"void_step\",\"rapid_recovery\"],\"advancement\":\"adventure_power:first_death\",\"trigger\":{\"type\":\"first_death\"}},"
            + "{\"id\":\"first_trade\",\"name\":\"初次交易\",\"abilities\":[\"soul_bind\",\"knockback_resist\"],\"advancement\":\"adventure_power:first_trade\",\"trigger\":{\"type\":\"first_trade\"}},"
            + "{\"id\":\"first_deep\",\"name\":\"初探地底\",\"abilities\":[\"damage_resist\",\"extended_reach\"],\"advancement\":\"adventure_power:first_deep\",\"trigger\":{\"type\":\"y_below\",\"y\":0}},"
            + "{\"id\":\"first_enchant\",\"name\":\"初次附魔\",\"abilities\":[\"undying_gear\",\"fortune_favor\"],\"advancement\":\"minecraft:story/enchant_item\"},"
            + "{\"id\":\"nether\",\"name\":\"炽热之门\",\"abilities\":[\"env_immunity\",\"lifesteal\"],\"advancement\":\"minecraft:story/enter_the_nether\"},"
            + "{\"id\":\"wither\",\"name\":\"凋零之陨\",\"abilities\":[\"healing_block\",\"vitality\"],\"advancement\":\"adventure_power:wither\"},"
            + "{\"id\":\"warden\",\"name\":\"幽匿之惧\",\"abilities\":[\"resilience\",\"purified_soul\"],\"advancement\":\"adventure_power:warden\"},"
            + "{\"id\":\"dragon\",\"name\":\"终末之翼\",\"abilities\":[\"soar\",\"soul_quench\",\"piercing_gaze\",\"death_defy\"],\"advancement\":\"minecraft:end/kill_dragon\"},"
            + "{\"id\":\"elytra\",\"name\":\"苍穹之证\",\"abilities\":[\"shadow_kill\",\"true_health\",\"reject_manip\",\"active_skill\"],\"advancement\":\"minecraft:end/elytra\"}"
            + "]}";
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        loadFromJson(AdventurePower.MODID, root);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileJava -x test
```

预期: `MilestoneRegistry` 编译通过。其余错误在 Capability/Handler 层。

---

### Task 6: IAdventureProgress 接口 + AdventureProgress 实现

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/capability/IAdventureProgress.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/capability/AdventureProgress.java`

- [ ] **Step 1: 修改 IAdventureProgress 接口**

将 `isMilestoneUnlocked(Milestone m)` → `isMilestoneUnlocked(String id)`，`unlockMilestone(Milestone m)` → `unlockMilestone(String id)`。移除 `import com.ayin90723.adventure_power.milestone.Milestone;`。

```java
// 里程碑（动态加载）
boolean isMilestoneUnlocked(String id);
boolean unlockMilestone(String id);
int getUnlockedMilestoneCount();
boolean areAllMilestonesUnlocked();
```

- [ ] **Step 2: 修改 AdventureProgress 实现**

核心改动:
- `Set<Milestone>` → `Set<String>`
- NBT 序列化: `m.name().toLowerCase()` → 直接用 milestone ID
- NBT 反序列化: **加过滤** `MilestoneRegistry.contains(key)`
- `isAbilityEnabled()`: 调用 `MilestoneRegistry.getCountAtUnlock(id)`
- `areAllMilestonesUnlocked()`: `fullyUnlocked || milestones.size() >= MilestoneRegistry.getMilestoneCount()`
- `getUnlockedMilestoneCount()`: `if (fullyUnlocked) return MilestoneRegistry.getMilestoneCount();`

```java
package com.ayin90723.adventure_power.capability;

import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import net.minecraft.nbt.CompoundTag;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
    private static final String TAG_DISABLED_ABILITIES = "disabledAbilities";

    private boolean adventurer;
    private boolean fullyUnlocked;
    private final Set<String> unlockedMilestones = new HashSet<>();
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

    @Override public boolean isAdventurer() { return adventurer; }
    @Override public void activateAdventurer() { this.adventurer = true; }
    @Override public boolean isFullyUnlocked() { return fullyUnlocked; }
    @Override public void activateFullyUnlocked() { this.fullyUnlocked = true; }

    // ===== 里程碑 =====

    @Override
    public boolean isMilestoneUnlocked(String id) {
        if (fullyUnlocked) return true;
        return unlockedMilestones.contains(id);
    }

    @Override
    public boolean unlockMilestone(String id) {
        return unlockedMilestones.add(id);
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

    @Override
    public boolean isAbilityEnabled(String id) {
        if (disabledAbilities.contains(id)) return false;
        if (AbilityRegistry.get(id) == null) return false;
        return MilestoneRegistry.isAbilityAvailable(id, getUnlockedMilestoneCount());
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
    @Override public long getDeathDefyInvulEnd() { return deathDefyInvulEnd; }
    @Override public void setDeathDefyInvulEnd(long time) { this.deathDefyInvulEnd = time; }
    @Override public long getDeathDefyCooldownEnd() { return deathDefyCooldownEnd; }
    @Override public void setDeathDefyCooldownEnd(long time) { this.deathDefyCooldownEnd = time; }

    // ===== 受击坚韧 =====
    @Override public int getResilienceStacks() { return resilienceStacks; }
    @Override public void setResilienceStacks(int stacks) { this.resilienceStacks = Math.max(0, stacks); }
    @Override public long getLastHurtTime() { return lastHurtTime; }
    @Override public void setLastHurtTime(long time) { this.lastHurtTime = time; }

    // ===== 真实血量备份 =====
    @Override public float getBackupHealth() { return backupHealth; }
    @Override public void setBackupHealth(float health) { this.backupHealth = health; }

    // ===== 主动技能 =====
    @Override public int getActiveSkillIndex() { return activeSkillIndex; }
    @Override public void setActiveSkillIndex(int index) { this.activeSkillIndex = index; }
    @Override public long getJudgmentCooldownEnd() { return judgmentCooldownEnd; }
    @Override public void setJudgmentCooldownEnd(long time) { this.judgmentCooldownEnd = time; }
    @Override public long getSanctuaryCooldownEnd() { return sanctuaryCooldownEnd; }
    @Override public void setSanctuaryCooldownEnd(long time) { this.sanctuaryCooldownEnd = time; }
    @Override public long getSanctuaryInvulEnd() { return sanctuaryInvulEnd; }
    @Override public void setSanctuaryInvulEnd(long time) { this.sanctuaryInvulEnd = time; }
    @Override public long getActiveSkillGcdEnd() { return activeSkillGcdEnd; }
    @Override public void setActiveSkillGcdEnd(long time) { this.activeSkillGcdEnd = time; }

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
        this.backupHealth = nbt.getFloat(TAG_BACKUP_HEALTH);
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileJava -x test
```

预期: `IAdventureProgress` 和 `AdventureProgress` 编译通过。`AdventureProgressCapability` 仍有编译错误。

---

### Task 7: AdventureProgressCapability 适配

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/capability/AdventureProgressCapability.java`

- [ ] **Step 1: 替换所有 Milestone 枚举引用**

需要改动的点:
1. `grantMilestone(ServerPlayer, Milestone)` → `grantMilestone(ServerPlayer, String milestoneId)`
2. `writeMilestonesToStack()`: `for (Milestone m : Milestone.values())` → `for (Milestone m : MilestoneRegistry.getAll())`
3. `scanItemForMilestones()`: 同上
4. `recoverProgressFromItems()`: `boolean[] found = new boolean[Milestone.values().length]` → `new boolean[MilestoneRegistry.getMilestoneCount()]`
5. `milestoneNbtKey(Milestone)` → `milestoneNbtKey(String id)`
6. `migrateOldStage()`: `Milestone.NETHER` 等枚举引用 → 字符串 `"nether"`/`"wither"` 等
7. `onPlayerTick` 中的 `for (Milestone m : Milestone.values())` → `for (Milestone m : MilestoneRegistry.getAll())`
8. `isAbilityAvailable()`: `ability.requiredMilestones()` → `MilestoneRegistry.getCountAtUnlock(id)`

关键方法改动示例:

```java
// grantMilestone 签名变更
public static void grantMilestone(ServerPlayer player, String milestoneId) {
    getAdventureProgress(player).ifPresent(progress -> {
        if (!progress.isAdventurer()) return;
        if (progress.isMilestoneUnlocked(milestoneId)) return;

        progress.unlockMilestone(milestoneId);
        // 翱翔飞行立即同步...
        syncCapabilityToPersistent(player, progress);
        syncAllAdventureItemNbt(player, progress);
        syncToClient(player);

        ServerLevel level = player.serverLevel();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.END_ROD,
            player.getX(), player.getY() + 1.5, player.getZ(), 30, 0.5, 0.5, 0.5, 0.1);
        player.displayClientMessage(
            Component.translatable("milestone.adventure_power." + milestoneId).withStyle(ChatFormatting.GREEN), true);

        if (progress.areAllMilestonesUnlocked() && !progress.isFullyUnlocked()) {
            replaceBeginWithEnd(player);
            AdvancementEventHandler.grantEndAdvancement(player);
        }
    });
}

// milestoneNbtKey 改为 String 参数
private static String milestoneNbtKey(String id) {
    return "MME_Milestone_" + Character.toUpperCase(id.charAt(0)) + id.substring(1);
}

// writeMilestonesToStack
public static void writeMilestonesToStack(ItemStack stack, IAdventureProgress progress) {
    CompoundTag tag = stack.getOrCreateTag();
    for (Milestone m : MilestoneRegistry.getAll()) {
        tag.putBoolean(milestoneNbtKey(m.id()), progress.isMilestoneUnlocked(m.id()));
    }
    stack.setTag(tag);
}

// migrateOldStage — 枚举引用改为字符串
public static void migrateOldStage(ItemStack stack) {
    CompoundTag tag = stack.getTag();
    if (tag != null && tag.contains(OLD_STAGE_KEY)) {
        int oldStage = tag.getInt(OLD_STAGE_KEY);
        if (oldStage >= 1) tag.putBoolean(milestoneNbtKey("nether"), true);
        if (oldStage >= 2) tag.putBoolean(milestoneNbtKey("wither"), true);
        if (oldStage >= 3) tag.putBoolean(milestoneNbtKey("warden"), true);
        if (oldStage >= 4) tag.putBoolean(milestoneNbtKey("dragon"), true);
        if (oldStage >= 5) tag.putBoolean(milestoneNbtKey("elytra"), true);
        tag.remove(OLD_STAGE_KEY);
        stack.setTag(tag);
    }
}

// scanItemForMilestones
private static void scanItemForMilestones(ItemStack stack, boolean[] found) {
    if (!stack.is(ModItems.ADVENTURE_BEGIN.get()) && !stack.is(ModItems.ADVENTURE_END.get())) return;
    migrateOldStage(stack);
    CompoundTag tag = stack.getOrCreateTag();
    List<Milestone> all = MilestoneRegistry.getAll();
    for (int i = 0; i < all.size(); i++) {
        if (tag.getBoolean(milestoneNbtKey(all.get(i).id()))) {
            found[i] = true;
        }
    }
}

// scanAllItemsForMilestones
private static void scanAllItemsForMilestones(Player player, boolean[] found) {
    List<Milestone> all = MilestoneRegistry.getAll();
    for (ItemStack stack : player.getInventory().items) {
        scanItemForMilestones(stack, found);
    }
    // ... armor, offhand, Curios 同理
}

// recoverProgressFromItems
private static boolean recoverProgressFromItems(Player player, IAdventureProgress progress) {
    List<Milestone> all = MilestoneRegistry.getAll();
    boolean[] found = new boolean[all.size()];
    scanAllItemsForMilestones(player, found);
    boolean anyMilestone = false;
    for (int i = 0; i < all.size(); i++) {
        if (found[i]) {
            progress.unlockMilestone(all.get(i).id());
            anyMilestone = true;
        }
    }
    if (anyMilestone) {
        progress.activateAdventurer();
        syncAllAdventureItemNbt(player, progress);
    }
    return anyMilestone;
}

// onPlayerTick 中的 fullyUnlocked 快速通道
for (Milestone m : MilestoneRegistry.getAll()) {
    progress.unlockMilestone(m.id());
}
// ...
for (Milestone m : MilestoneRegistry.getAll()) {
    AdvancementEventHandler.grantMilestoneAdvancement(sp, m.id());
}

// isAbilityAvailable
public static boolean isAbilityAvailable(Player player, String id) {
    if (!KNOWN_ABILITIES.containsKey(id)) return false;
    if (AbilityRegistry.get(id) == null) return false;
    return getAdventureProgress(player)
        .map(p -> MilestoneRegistry.isAbilityAvailable(id, p.getUnlockedMilestoneCount()))
        .orElse(false);
}
```

- [ ] **Step 2: 在 PlayerLoggedInEvent 处理 Registry 未初始化的情况**

```java
@SubscribeEvent
public static void onPlayerLogin(PlayerLoggedInEvent event) {
    Player player = event.getEntity();
    if (player.level().isClientSide()) return;

    // 若 Registry 未加载（ServerAboutToStartEvent 比登录事件晚），延迟处理
    if (!MilestoneRegistry.isInitialized()) {
        AdventurePower.LOGGER.warn("[AdventureProgress] MilestoneRegistry 未初始化，延迟玩家 {} 的进度加载", player.getName().getString());
        return; // onPlayerTick 中的 verifiedBeginItem 安全网会兜底处理
    }

    // ... 现有登录逻辑
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileJava -x test
```

预期: `AdventureProgressCapability` 编译通过。剩余错误集中在 Handler 层。

---

### Task 8: MilestoneTriggerManager（替代 MilestoneTriggerHandler）

**Files:**
- Create: `src/main/java/com/ayin90723/adventure_power/util/MilestoneTriggerManager.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/MilestoneTriggerHandler.java` → 标记为废弃/删除

- [ ] **Step 1: 实现 MilestoneTriggerManager**

```java
package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.handler.AdvancementEventHandler;
import com.ayin90723.adventure_power.milestone.Milestone;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 里程碑触发器管理器 — 根据 MilestoneRegistry 中的 trigger 定义注册事件监听。
 * 替代旧的硬编码 MilestoneTriggerHandler。
 *
 * 支持 5 种 trigger type:
 * - survive_night: 度过第一夜
 * - first_death: 玩家首次死亡
 * - first_trade: 首次与村民交互
 * - y_below: Y 坐标低于指定值
 * - first_kill: 首次击杀指定实体
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MilestoneTriggerManager {

    private static final long DAY_CYCLE_TICKS = 24000;

    /** 已触发的 milestone ID 集合（防止重复触发） */
    private static final Set<UUID> SURVIVE_NIGHT_TRIGGERED = new HashSet<>();
    private static final Set<UUID> FIRST_DEATH_TRIGGERED = new HashSet<>();
    private static final Set<UUID> FIRST_TRADE_TRIGGERED = new HashSet<>();
    private static final Map<UUID, Set<String>> Y_BELOW_TRIGGERED = new HashMap<>();
    private static final Map<UUID, Set<String>> FIRST_KILL_TRIGGERED = new HashMap<>();

    // ===== survive_night =====

    @SubscribeEvent
    public static void onPlayerTickSurviveNight(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer sp)) return;
        UUID uuid = player.getUUID();

        if (SURVIVE_NIGHT_TRIGGERED.contains(uuid)) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        // 遍历 registry 找所有 survive_night trigger
        for (Milestone m : MilestoneRegistry.getAll()) {
            if (m.trigger() != null && "survive_night".equals(m.trigger().type())) {
                if (AdventureProgressCapability.getAdventureProgress(player)
                    .map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
                long dayTime = player.level().getDayTime();
                if (dayTime > DAY_CYCLE_TICKS && player.level().isDay()) {
                    AdvancementEventHandler.grantMilestoneAdvancement(sp, m.id());
                    SURVIVE_NIGHT_TRIGGERED.add(uuid);
                }
            }
        }
    }

    // ===== first_death =====

    @SubscribeEvent
    public static void onPlayerFirstDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.isCanceled()) return;

        UUID uuid = player.getUUID();
        if (FIRST_DEATH_TRIGGERED.contains(uuid)) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        for (Milestone m : MilestoneRegistry.getAll()) {
            if (m.trigger() != null && "first_death".equals(m.trigger().type())) {
                if (AdventureProgressCapability.getAdventureProgress(player)
                    .map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
                AdvancementEventHandler.grantMilestoneAdvancement(player, m.id());
                FIRST_DEATH_TRIGGERED.add(uuid);
            }
        }
    }

    // ===== first_trade =====

    @SubscribeEvent
    public static void onPlayerFirstTrade(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!(event.getTarget() instanceof Villager)) return;

        UUID uuid = player.getUUID();
        if (FIRST_TRADE_TRIGGERED.contains(uuid)) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        for (Milestone m : MilestoneRegistry.getAll()) {
            if (m.trigger() != null && "first_trade".equals(m.trigger().type())) {
                if (AdventureProgressCapability.getAdventureProgress(player)
                    .map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
                AdvancementEventHandler.grantMilestoneAdvancement(player, m.id());
                FIRST_TRADE_TRIGGERED.add(uuid);
            }
        }
    }

    // ===== y_below =====

    @SubscribeEvent
    public static void onPlayerTickYBelow(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer sp)) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        UUID uuid = player.getUUID();
        Set<String> triggered = Y_BELOW_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());

        for (Milestone m : MilestoneRegistry.getAll()) {
            if (m.trigger() != null && "y_below".equals(m.trigger().type())) {
                if (triggered.contains(m.id())) continue;
                if (AdventureProgressCapability.getAdventureProgress(player)
                    .map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
                int threshold = m.trigger().y() != null ? m.trigger().y() : 0;
                if (player.getY() < threshold) {
                    AdvancementEventHandler.grantMilestoneAdvancement(sp, m.id());
                    triggered.add(m.id());
                }
            }
        }
    }

    // ===== first_kill =====

    @SubscribeEvent
    public static void onPlayerFirstKill(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.isCanceled()) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        UUID uuid = player.getUUID();
        Set<String> triggered = FIRST_KILL_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());

        for (Milestone m : MilestoneRegistry.getAll()) {
            if (m.trigger() != null && "first_kill".equals(m.trigger().type())) {
                if (triggered.contains(m.id())) continue;
                if (AdventureProgressCapability.getAdventureProgress(player)
                    .map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
                if (m.trigger().entity() == null) continue;

                EntityType<?> requiredType = ForgeRegistries.ENTITY_TYPES.getValue(m.trigger().entity());
                if (requiredType != null && event.getEntity().getType() == requiredType) {
                    AdvancementEventHandler.grantMilestoneAdvancement(player, m.id());
                    triggered.add(m.id());
                }
            }
        }
    }
}
```

- [ ] **Step 2: 删除旧的 MilestoneTriggerHandler**

旧文件 `handler/MilestoneTriggerHandler.java` 整体删除。

```bash
rm src/main/java/com/ayin90723/adventure_power/handler/MilestoneTriggerHandler.java
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileJava -x test
```

预期: Handler 层还有剩余错误（AdvancementEventHandler 等）。

---

### Task 9: AdvancementEventHandler 适配

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/handler/AdvancementEventHandler.java`

- [ ] **Step 1: 移除 VANILLA_TO_MILESTONE，改用 MilestoneRegistry**

```java
package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class AdvancementEventHandler {

    @SubscribeEvent
    public static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceLocation earnedId = event.getAdvancement().getId();

        // ===== 方向一：已获得的成就 → 检查是否匹配某个里程碑 =====
        Milestone linked = MilestoneRegistry.getByAdvancement(earnedId);
        if (linked != null) {
            if (AdventureProgressCapability.isAdventurer(player)) {
                grantMilestoneAdvancement(player, linked.id());
            }
            return;
        }

        // ===== 方向二：我们的成就 → 更新 Capability =====
        if (AdventurePower.MODID.equals(earnedId.getNamespace())) {
            handleOurAdvancement(player, earnedId.getPath());
        }
    }

    private static void handleOurAdvancement(ServerPlayer player, String path) {
        switch (path) {
            case "root" -> {
                AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                    if (!progress.isAdventurer()) {
                        progress.activateAdventurer();
                        AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
                        AdventureProgressCapability.syncAllAdventureItemNbt(player, progress);
                        AdventureProgressCapability.syncToClient(player);
                    }
                });
                catchUpMissedMilestones(player);
            }
            case "adventure_end" -> {
                AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                    if (!progress.isFullyUnlocked()) {
                        progress.activateFullyUnlocked();
                        AdventureProgressCapability.updateScoreboard(player, true);
                        AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
                        AdventureProgressCapability.syncToClient(player);
                    }
                });
            }
            default -> {
                // 常规里程碑成就 → 同步 Capability
                if (MilestoneRegistry.contains(path)) {
                    AdventureProgressCapability.grantMilestone(player, path);
                }
            }
        }
    }

    public static void catchUpMissedMilestones(ServerPlayer player) {
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        boolean anyChanged = false;

        // 扫描所有注册的里程碑对应的成就
        for (Milestone m : MilestoneRegistry.getAll()) {
            if (progress.isMilestoneUnlocked(m.id())) continue;
            ResourceLocation advId = m.advancement();
            if (advId == null) continue;
            Advancement adv = player.server.getAdvancements().getAdvancement(advId);
            if (adv != null && isAdvancementDone(player, adv)) {
                progress.unlockMilestone(m.id());
                anyChanged = true;
            }
        }

        if (anyChanged) {
            AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
            AdventureProgressCapability.syncAllAdventureItemNbt(player, progress);
            AdventureProgressCapability.syncToClient(player);
        }
    }

    private static boolean isAdvancementDone(ServerPlayer player, Advancement adv) {
        AdvancementProgress advProgress = player.getAdvancements().getOrStartProgress(adv);
        return advProgress.isDone();
    }

    /** 授予我们模组的里程碑成就 */
    public static void grantMilestoneAdvancement(ServerPlayer player, String milestoneId) {
        ResourceLocation advId = new ResourceLocation(AdventurePower.MODID, milestoneId);
        Advancement advancement = player.server.getAdvancements().getAdvancement(advId);
        if (advancement == null) return;
        if (player.getAdvancements().getOrStartProgress(advancement).isDone()) return;
        player.getAdvancements().award(advancement, milestoneId);
    }

    public static void grantRootAdvancement(ServerPlayer player) {
        ResourceLocation advId = new ResourceLocation(AdventurePower.MODID, "root");
        Advancement advancement = player.server.getAdvancements().getAdvancement(advId);
        if (advancement == null) return;
        if (player.getAdvancements().getOrStartProgress(advancement).isDone()) return;
        player.getAdvancements().award(advancement, "adventurer");
    }

    public static void grantEndAdvancement(ServerPlayer player) {
        ResourceLocation advId = new ResourceLocation(AdventurePower.MODID, "adventure_end");
        Advancement advancement = player.server.getAdvancements().getAdvancement(advId);
        if (advancement == null) return;
        if (player.getAdvancements().getOrStartProgress(advancement).isDone()) return;
        player.getAdvancements().award(advancement, "end");
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileJava -x test
```

预期: Handler 层编译错误大幅减少。剩余错误在 Item 层。

---

### Task 10: 物品层适配

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/item/AdventureCurioItem.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/item/AdventureEndRecipe.java`

- [ ] **Step 1: 修改 AdventureCurioItem**

将硬编码 `MILESTONE_COUNT = 10` 替换为动态查询。tooltip 翻译键改为基于 milestone ID。

```java
// 删除: private static final int MILESTONE_COUNT = 10;

private void addMilestoneLines(List<Component> tooltip) {
    List<Milestone> all = MilestoneRegistry.getAll();
    for (Milestone m : all) {
        tooltip.add(Component.translatable("item.adventure_power.lore.milestone." + m.id())
                .withStyle(ChatFormatting.GRAY));
    }
}
```

需添加 import: `import com.ayin90723.adventure_power.util.MilestoneRegistry;` 和 `import com.ayin90723.adventure_power.milestone.Milestone;`

**注意**: `MilestoneRegistry` 在客户端可能未同步（tooltip 是纯客户端方法）。需加 null 安全处理：

```java
private void addMilestoneLines(List<Component> tooltip) {
    if (!MilestoneRegistry.isInitialized()) {
        tooltip.add(Component.translatable("item.adventure_power.lore.loading")
                .withStyle(ChatFormatting.GRAY));
        return;
    }
    for (Milestone m : MilestoneRegistry.getAll()) {
        tooltip.add(Component.translatable("item.adventure_power.lore.milestone." + m.id())
                .withStyle(ChatFormatting.GRAY));
    }
}
```

- [ ] **Step 2: 修改 AdventureEndRecipe**

`hasAllFiveMilestones()` → 通用检查。由于配方是在服务端匹配的，可以访问完整的 MilestoneRegistry。

```java
private static boolean hasAllMilestones(ItemStack stack) {
    CompoundTag tag = stack.getTag();
    if (tag == null) return false;
    List<Milestone> all = MilestoneRegistry.getAll();
    for (Milestone m : all) {
        if (!tag.getBoolean(milestoneNbtKey(m.id()))) return false;
    }
    return !all.isEmpty();
}

private static String milestoneNbtKey(String id) {
    return "MME_Milestone_" + Character.toUpperCase(id.charAt(0)) + id.substring(1);
}
```

更新 `matches()` 方法中的调用:
```java
// 路径 A: 检查物品 NBT
AdventureProgressCapability.migrateOldStage(beginStack);
if (hasAllMilestones(beginStack)) return true;
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileJava -x test
```

预期: 所有编译错误解决。

---

### Task 11: 客户端 MilestoneRegistry 同步

**Files:**
- Modify: `src/main/java/com/ayin90723/adventure_power/util/MilestoneRegistry.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/network/NetworkHandler.java`
- Modify: `src/main/java/com/ayin90723/adventure_power/capability/AdventureProgressCapability.java`

- [ ] **Step 1: 在 MilestoneRegistry 添加客户端初始化方法**

```java
/** 客户端从网络包接收里程碑数据 */
public static void clientInit(List<String> serializedMilestones) {
    // 构建一个临时的 JSON object 来复用 loadFromJson
    StringBuilder json = new StringBuilder("{\"milestones\":[");
    for (int i = 0; i < serializedMilestones.size(); i++) {
        if (i > 0) json.append(",");
        json.append(serializedMilestones.get(i));
    }
    json.append("]}");
    JsonObject root = GSON.fromJson(json.toString(), JsonObject.class);
    loadFromJson(AdventurePower.MODID, root);
}
```

- [ ] **Step 2: 在 AdventureSyncPacket 中附加上里程碑元数据**

在 `AdventureProgressCapability.syncToClient()` 方法中，构建 AdventureSyncPacket 时额外附加上 registry milestones 数据:

```java
// 在 AdventureProgressCapability 中新增方法
public static CompoundTag buildSyncTag(Player player) {
    CompoundTag tag = getAdventureProgress(player)
        .map(IAdventureProgress::serializeNBT).orElse(new CompoundTag());
    
    // 附加里程碑注册表数据
    CompoundTag registryTag = new CompoundTag();
    List<Milestone> all = MilestoneRegistry.getAll();
    registryTag.putInt("count", all.size());
    for (int i = 0; i < all.size(); i++) {
        Milestone m = all.get(i);
        CompoundTag mTag = new CompoundTag();
        mTag.putString("id", m.id());
        mTag.putString("name", m.name());
        mTag.putInt("index", i);
        tag.put("registry_milestone_" + i, mTag);
    }
    tag.put("registry_milestones", registryTag);
    
    return tag;
}

// 修改 syncToClient:
public static void syncToClient(Player player) {
    if (!(player instanceof ServerPlayer sp)) return;
    CompoundTag syncData = buildSyncTag(player);
    NetworkHandler.INSTANCE.send(
        PacketDistributor.PLAYER.with(() -> sp),
        new NetworkHandler.AdventureSyncPacket(syncData)
    );
}
```

- [ ] **Step 3: 在客户端接收端解析里程碑数据**

修改 `AdventureSyncPacket.handle()`:

```java
public static void handle(AdventureSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
    ctx.get().enqueueWork(() -> {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // 先同步里程碑注册表（如果附带的话）
            if (msg.data.contains("registry_milestones")) {
                CompoundTag registryTag = msg.data.getCompound("registry_milestones");
                int count = registryTag.getInt("count");
                List<String> milestones = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    if (msg.data.contains("registry_milestone_" + i)) {
                        CompoundTag mTag = msg.data.getCompound("registry_milestone_" + i);
                        String id = mTag.getString("id");
                        String name = mTag.getString("name");
                        JsonObject mJson = new JsonObject();
                        mJson.addProperty("id", id);
                        mJson.addProperty("name", name);
                        mJson.add("abilities", new JsonArray());
                        mJson.add("advancement", null);
                        mJson.add("trigger", null);
                        milestones.add(GSON.toJson(mJson));
                    }
                }
                if (!milestones.isEmpty()) {
                    MilestoneRegistry.clientInit(milestones);
                }
            }
            
            // 然后同步 Capability 数据
            mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(
                progress -> progress.deserializeNBT(msg.data));
            AdventureProgressCapability.tryOpenPendingScreen();
        }
    });
    ctx.get().setPacketHandled(true);
}
```

需在 NetworkHandler 中添加 Gson import。

- [ ] **Step 4: 编译验证 + 完整构建**

```bash
./gradlew build -x test
```

预期: 构建成功。

---

### Task 12: lang 翻译文件更新

**Files:**
- Modify: `src/main/resources/assets/adventure_power/lang/zh_cn.json`
- Modify: `src/main/resources/assets/adventure_power/lang/en_us.json`

- [ ] **Step 1: 添加新的 tooltip 翻译键**

旧的 `item.adventure_power.lore.milestone_0` ~ `item.adventure_power.lore.milestone_9` 替换为基于 ID 的键，同时保留旧键用于兼容（玩家可能安装了旧版资源包）。

zh_cn.json 新增:
```json
{
  "item.adventure_power.lore.loading": "§7加载中...",
  "item.adventure_power.lore.milestone.first_night": "§7初次夜冕 §8—— §f灵巧 §7| 恩赐永驻 §7| 大地之力",
  "item.adventure_power.lore.milestone.first_death": "§7初尝败绩 §8—— §f虚空踏步 §7| 休养生息",
  "item.adventure_power.lore.milestone.first_trade": "§7初次交易 §8—— §f灵魂绑定 §7| 不动如山",
  "item.adventure_power.lore.milestone.first_deep": "§7初探地底 §8—— §f伤害抗性 §7| 无形之手",
  "item.adventure_power.lore.milestone.first_enchant": "§7初次附魔 §8—— §f不朽装备 §7| 鸿运当头",
  "item.adventure_power.lore.milestone.nether": "§7炽热之门 §8—— §f环境免疫 §7| 嗜血",
  "item.adventure_power.lore.milestone.wither": "§7凋零之陨 §8—— §f禁疗之触 §7| 坚韧之躯",
  "item.adventure_power.lore.milestone.warden": "§7幽匿之惧 §8—— §f受击坚韧 §7| 净魂",
  "item.adventure_power.lore.milestone.dragon": "§7终末之翼 §8—— §f淬魂 §7| 破敌 §7| 翱翔 §7| 死亡抗拒",
  "item.adventure_power.lore.milestone.elytra": "§7苍穹之证 §8—— §f影杀 §7| 真实血量 §7| 拒绝篡改 §7| 旅者之力"
}
```

en_us.json 新增:
```json
{
  "item.adventure_power.lore.loading": "§7Loading...",
  "item.adventure_power.lore.milestone.first_night": "§7First Night §8—— §fAgility §7| Perpetual Blessing §7| Digging Power",
  "item.adventure_power.lore.milestone.first_death": "§7First Death §8—— §fVoid Step §7| Rapid Recovery",
  "item.adventure_power.lore.milestone.first_trade": "§7First Trade §8—— §fSoul Bind §7| Knockback Resist",
  "item.adventure_power.lore.milestone.first_deep": "§7First Deep §8—— §fDamage Resist §7| Extended Reach",
  "item.adventure_power.lore.milestone.first_enchant": "§7First Enchant §8—— §fUndying Gear §7| Fortune Favor",
  "item.adventure_power.lore.milestone.nether": "§7Nether §8—— §fEnv Immunity §7| Lifesteal",
  "item.adventure_power.lore.milestone.wither": "§7Wither §8—— §fHealing Block §7| Vitality",
  "item.adventure_power.lore.milestone.warden": "§7Warden §8—— §fResilience §7| Purified Soul",
  "item.adventure_power.lore.milestone.dragon": "§7Dragon §8—— §fSoul Quench §7| Piercing Gaze §7| Soar §7| Death Defy",
  "item.adventure_power.lore.milestone.elytra": "§7Elytra §8—— §fShadow Kill §7| True Health §7| Reject Manip §7| Active Skill"
}
```

---

### Task 13: 集成测试 — 构建并验证

**Files:** 无新文件

- [ ] **Step 1: 完整构建**

```bash
cd D:\download\模组\冒险的力量
./gradlew clean build -x test
```

预期: BUILD SUCCESSFUL，生成 `build/libs/adventure_power-1.0.0.jar`。

- [ ] **Step 2: 检查生成的 JAR 包含 milestones.json**

```bash
jar tf build/libs/adventure_power-1.0.0.jar | grep milestones
```

预期: 输出 `data/adventure_power/adventure_power/milestones.json`。

- [ ] **Step 3: 启动 Minecraft 验证**

将 JAR 放入测试实例的 mods 文件夹，启动游戏。验证：
1. 玩家获得"冒险的开始" → 佩戴 → 成就界面出现 `adventure_power:root`
2. 度过第一夜 → 成就 `adventure_power:first_night` 自动完成 → 灵巧/大地之力/恩赐永驻 可用
3. 原版成就联动：进入下界 → `adventure_power:nether` 完成
4. 全里程碑 → 冒险的开始替换为冒险的终点
5. `/reload` → 日志无报错，系统正常

- [ ] **Step 4: 测试数据包覆盖**

创建一个测试数据包，覆盖 `milestones.json`（把某个里程碑移动到不同位置），`/reload` 后验证能力是否正确重映射。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: 里程碑系统数据包化

- Milestone 枚举 → record，由 MilestoneRegistry 动态加载
- Ability 接口移除 requiredMilestones()，新增 setCountAtUnlock()
- IAdventureProgress 里程碑存储改用 Set<String>
- MilestoneTriggerManager 替代硬编码 MilestoneTriggerHandler
- AdvancementEventHandler 改用 Registry 动态匹配
- AdventureSyncPacket 附带里程碑元数据给客户端
- 默认内置 milestones.json 保持现有 10 个里程碑不变"
```
