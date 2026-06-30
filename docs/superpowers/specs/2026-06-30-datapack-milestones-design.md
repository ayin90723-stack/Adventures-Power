# 里程碑数据包化设计文档

日期: 2026-06-30 | 状态: 待审批

## 目标

将里程碑系统从 Java 枚举硬编码改为可通过数据包 JSON 覆盖的数据驱动模式，允许玩家/模组包作者自定义里程碑（增减里程碑、重新分配能力、引用任意模组成就）。

**设计级别**: B（可增减里程碑，饰品流程不变）

## 数据包 JSON 格式

文件路径: `data/<namespace>/adventure_power/milestones.json`

模组内置默认文件位置: `src/main/resources/data/adventure_power/adventure_power/milestones.json`

若多个数据包提供同名文件，后加载的覆盖先加载的（Forge 标准行为）。模组内置默认文件始终最先加载。若两个模组都提供同名文件，加载顺序取决于模组加载顺序（不推荐依赖此行为；如果需要扩展而非替换里程碑，应由本模组在未来版本中支持追加式 JSON）。

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

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 里程碑唯一标识，同时作为 advancement criterion key |
| `name` | string | 显示名称（中文，英文见翻译文件） |
| `abilities` | string[] | 该里程碑解锁后变为可用的能力 ID 列表 |
| `advancement` | ResourceLocation | 关联的成就。**支持任意模组的成就**。玩家获得该成就即视为里程碑达成 |
| `trigger` | object\|null | 仅当 advancement 使用 `impossible` 触发器时需要。`null` 表示该成就有原生触发器 |

### trigger.type 预置值

| type | 额外参数 | 含义 |
|------|---------|------|
| `survive_night` | 无 | 度过第一夜（dayTime > 24000 且白天） |
| `first_death` | 无 | 玩家首次死亡 |
| `first_trade` | 无 | 首次与村民交互 |
| `y_below` | `y`: int | Y 坐标低于指定值 |
| `first_kill` | `entity`: ResourceLocation | 首次击杀指定实体类型 |

### 触发流程

```
trigger != null
  → MilestoneTriggerManager 检测到条件
  → 自动授予 advancement 对应成就
  → AdvancementEventHandler 监听到成就
  → 解锁里程碑

trigger == null
  → 成就由原版 advancement 原生触发器自动获得
  → AdvancementEventHandler 监听到成就
  → 解锁里程碑
```

**核心原则**: 里程碑触发统一走成就系统。Java 只做「检测条件 → 授予成就」，不做「检测条件 → 直接解锁里程碑」。

**注意回调路径**: `grantMilestoneAdvancement()` → `award(adv, criterion)` → 触发 `AdvancementEvent.AdvancementEarnEvent` → `handleOurAdvancement()` → `grantMilestone()` → 音效/粒子/检查全解锁。`grantMilestone()` 的幂等性（`isMilestoneUnlocked` 检查）保证重复授予无副作用，但实现时需注意不要在 `AdvancementEvent` handler 内再次调用 `grantMilestoneAdvancement` 形成无限循环。

## 架构变更

### 1. Milestone 枚举 → 动态加载

```java
// 旧: 硬编码枚举
public enum Milestone { FIRST_NIGHT, FIRST_DEATH, ... }

// 新: record 类，由 MilestoneRegistry 加载 JSON 后创建
public record Milestone(
    String id,
    String name,
    List<String> abilities,
    ResourceLocation advancement,
    @Nullable TriggerDef trigger
) {}
```

### 2. MilestoneRegistry（新增）

```
ServerAboutToStartEvent / AddReloadListener
  → 扫描所有 datapack 的 data/*/adventure_power/milestones.json
  → 解析 JSON → 构建 Milestone 列表
  → 遍历 abilities 数组, 为每个 Ability 设置 countAtUnlock
  → 构建 advancement → milestone 反向索引
```

关键方法:
- `getAll()` — 返回有序里程碑列表
- `getByAdvancement(ResourceLocation)` — 根据成就 ID 查找里程碑
- `getMilestoneCount()` — 里程碑总数（替代 `Milestone.values().length`）
- `contains(String id)` — 检查 milestone ID 是否存在于当前注册表中（用于 NBT 反序列化过滤）
- `getAbilitiesForMilestone(String milestoneId)` — 里程碑包含的能力
- `getCountAtUnlock(String abilityId)` — 查询某能力解锁所需的里程碑数（替代 `Ability.requiredMilestones()`）
- `isAbilityAvailable(String abilityId, int unlockedCount)` — 等价于 `unlockedCount >= getCountAtUnlock(abilityId)`

**countAtUnlock 计算规则**:
```
countAtUnlock = milestone 在 JSON 数组中的 index + 1
```
即数组中第 1 个里程碑的 index=0 → countAtUnlock=1，第 2 个 index=1 → countAtUnlock=2，依此类推。加载 JSON 后遍历 `milestones` 数组，对每个 milestone 的 `abilities` 列表中的每个能力调用 `AbilityRegistry.setCountAtUnlock(abilityId, milestoneIndex + 1)`。

**AbilityRegistry 方法迁移**:
- `getByMilestone(int)` → 移除（由 `MilestoneRegistry.getAbilitiesForMilestone()` 替代）
- `getAvailable(int unlockedCount)` → 移除（由 `MilestoneRegistry.isAbilityAvailable()` 替代）
- 新增 `setCountAtUnlock(String id, int n)` 和 `getCountAtUnlock(String id)`

### 3. Ability 接口变更

```java
// 旧
int requiredMilestones();          // 硬编码的里程碑序号
float value(int milestones);       // milestones = 已解锁总数

// 新
// requiredMilestones() 移除
float value(int count);            // count = 已解锁里程碑总数，参数名从 milestones 改为 count
// 新增: MilestoneRegistry 加载后设置
default void setCountAtUnlock(int n) {}
```

每个 Ability 实现类内部存储 `countAtUnlock` 字段（默认值 = 当前 requiredMilestones 返回值），`value(count)` 内部用 `count - countAtUnlock` 计算成长偏移。

**注意**: `float value(int)` 的 Java 方法签名完全不变（仅参数名从 `milestones` 改为 `count`），不影响二进制/源码兼容性。

**特殊公式能力**: 以下能力的 `value()` 不使用标准 `base + step × (count - countAtUnlock)` 公式，而是根据绝对值做 switch 判断。它们的迁移需单独处理：
- `ResilienceAbility`: `switch(count) { case 8 → ..., case 9 → ..., case 10 → ... }`，countAtUnlock 设为 8，公式用绝对值 switch
- `SoulQuenchAbility`: `value()` 返回 -1（无成长），实际数值由 `flatDamage(count)` 和 `hpRatio(count)` 提供
- `ShadowKillAbility`: `value()` 无成长，数值由 `flatDamage()` / `hpRatio()` 提供
- 返回 -1 的能力（Soar/DeathDefy/TrueHealth/RejectManip/ActiveSkill 等）：value() 始终返回 -1，无需改动

**25 个 Ability 的 countAtUnlock 默认值保持不变:**
- 1: agility, digging_power, perpetual_blessing
- 2: void_step, rapid_recovery
- 3: soul_bind, knockback_resist
- 4: damage_resist, extended_reach
- 5: undying_gear, fortune_favor
- 6: env_immunity, lifesteal
- 7: healing_block, vitality
- 8: resilience, purified_soul
- 9: soar, soul_quench, piercing_gaze, death_defy
- 10: shadow_kill, true_health, reject_manip, active_skill

### 4. IAdventureProgress 接口变更

```java
// 旧: 依赖 Milestone 枚举类型
boolean isMilestoneUnlocked(Milestone m);
boolean unlockMilestone(Milestone m);
int getUnlockedMilestoneCount();           // 不变
boolean areAllMilestonesUnlocked();        // 实现改为查询 Registry

// 新: 使用字符串 ID
boolean isMilestoneUnlocked(String id);
boolean unlockMilestone(String id);
int getUnlockedMilestoneCount();
boolean areAllMilestonesUnlocked();        // fullyUnlocked || milestones.size() >= MilestoneRegistry.getMilestoneCount()
```

AdventureProgress 实现:
- `Set<Milestone>` → `Set<String>`（存储 milestone ID）
- NBT 序列化: `m.name().toLowerCase()` → `milestoneId` 直接作为 key
- **NBT 反序列化时必须过滤**: 只加载 `MilestoneRegistry.contains(key)` 为 true 的条目，未知 ID 跳过（避免计数虚高，见边界情况表）
- `isAbilityEnabled()`: `ability.requiredMilestones()` → `MilestoneRegistry.getCountAtUnlock(id) <= getUnlockedMilestoneCount()`
- `areAllMilestonesUnlocked()`: **保留** `fullyUnlocked` 短路 → `fullyUnlocked || getUnlockedMilestoneCount() >= MilestoneRegistry.getMilestoneCount()`
- `getUnlockedMilestoneCount()`: 同样保留 `fullyUnlocked` 短路 → 若 `fullyUnlocked` 直接返回 `MilestoneRegistry.getMilestoneCount()`

### 5. AdvancementEventHandler 变更

移除 `VANILLA_TO_MILESTONE` 硬编码映射，改为:
- `onAdvancementEarned`: 调用 `MilestoneRegistry.getByAdvancement(earnedId)` 查找匹配的里程碑
- `catchUpMissedMilestones`: 遍历 `MilestoneRegistry.getAll()` 而非 `Milestone.values()`
- `grantMilestoneAdvancement`: 参数从 `Milestone` 改为 `String id`

### 6. MilestoneTriggerHandler → MilestoneTriggerManager

原 `MilestoneTriggerHandler` 的 4 个硬编码检测逻辑替换为 `MilestoneTriggerManager`:
- 遍历 `MilestoneRegistry.getAll()`
- 对 trigger != null 的里程碑，根据 type 注册对应的事件监听
- 检测到条件时调用 `AdvancementEventHandler.grantMilestoneAdvancement(player, milestoneId)`

### 7. AdventureProgressCapability 兼容性

**NBT 兼容**: 当前 NBT 键为 `"first_night"`（对应旧 `m.name().toLowerCase()` 即 `FIRST_NIGHT`→`first_night`）。新系统直接用 milestone ID 作为 key。只要默认 JSON 的 ID 与旧枚举名小写一致，存档完全兼容。

**NBT 反序列化过滤（关键）**: 反序列化时必须调用 `MilestoneRegistry.contains(key)` 过滤未知 ID。若不加过滤，数据包删除某个里程碑后，其旧 NBT key 仍会被加载到 `unlockedMilestones` 集合中，导致 `getUnlockedMilestoneCount()` 虚高，所有能力的 `value(count)` 计算偏大，`areAllMilestonesUnlocked()` 误触发。

**旧 Stage 迁移**: `migrateOldStage()` 仍然保留，处理旧版 `AdventureStage` key。其中的 `Milestone.NETHER` 等枚举引用改为字符串 ID 常量。

**数据包重载**: 玩家已解锁的里程碑如果在新的 JSON 中不存在，NBT 中的旧 key 被反序列化过滤跳过，不计入总数，不显示在 UI 中。若后续数据包又加回该里程碑，NBT 中保留的 key 会被重新识别。

**数据包重载时 JSON 顺序变化**: 如果包作者重排 JSON 中 milestone 的顺序，ability 的 `countAtUnlock` 会随之改变。例如把某个能力从 index=0 移到 index=3，其 `countAtUnlock` 从 1 变为 4。此时只有少量里程碑的玩家，`count < countAtUnlock` → 能力直接被禁用（门禁拦截）。已解锁足够多里程碑的玩家，`value(count) = base + step × (count - countAtUnlock)` 会因为 `countAtUnlock` 变大而降低，但不会低于 base（保底值）。这是数据驱动的正常行为——包作者应理解重排序的后果。实现上不做额外保护。

### 8. AdventureCurioItem tooltip（需客户端同步）

当前硬编码 `MILESTONE_COUNT = 10` 和 `lore.milestone_0` ~ `lore.milestone_9` 翻译键。改为:
- 动态读取客户端本地的 `MilestoneRegistry` 副本
- 翻译键改为 `lore.milestone.<id>` 格式

**客户端 MilestoneRegistry 同步方案**: `AdventureCurioItem.appendHoverText()` 是纯客户端方法，无法直接访问服务端的 MilestoneRegistry。方案如下：

1. 在 `AdventureSyncPacket` 的 CompoundTag 中新增一个 `"registry_milestones"` 键，存储里程碑元数据的序列化列表（id、name、abilities、index）
2. 客户端收到后，调用 `MilestoneRegistry.clientInit(CompoundTag)` 构建客户端只读副本
3. `AdventureSyncPacket` 在玩家登录、数据包重载、维度切换时发送（现有发送时机已覆盖）
4. 若客户端尚未收到同步包，tooltip 回退显示纯文本占位符

数据量极小（10 个里程碑约 1KB），对网络影响可忽略。

### 9. AdventureEndRecipe

`hasAllFiveMilestones()` 检查 5 个特定里程碑 → 改为检查 `areAllMilestonesUnlocked()`（通过物品 NBT 中的里程碑标记数量）。

## 不改动的部分

- **NetworkHandler** — `AdventureSyncPacket` 使用 CompoundTag，内部字段变化对协议透明
- **ModConfig** — 配置项名不变（如 `RESILIENCE_STACKS_8`），能力实现适配时只改内部公式
- **所有 Mixin** — `isAbilityAvailable(player, "xxx")` 调用完全不变
- **饰品自动发放/替换流程** — 保持现状
- **主动技能系统** — 只引用能力 ID 字符串，不受影响
- **lang 文件** — 能力翻译键不变，新增里程碑翻译键按需

## 边界情况处理

| 情况 | 处理 |
|------|------|
| 无数据包提供 milestones.json | 使用模组内置默认 JSON（等于当前行为） |
| JSON 中 `milestones` 为空数组 | 里程碑数为 0，所有能力不可用（`countAtUnlock` 未设置 → 默认值生效）。日志 WARNING |
| 同一 milestone ID 重复出现 | 日志 WARNING，使用最后一个定义 |
| JSON 中引用不存在的 ability ID | 日志 WARNING，跳过该 ability |
| JSON 中 `advancement` 字段缺失且 `trigger` 也为 null | 日志 WARNING，该里程碑永远无法达成 |
| JSON 中 `advancement` 指向不存在的成就 | 日志 WARNING，里程碑无法通过成就达成（若 trigger 不为 null 仍可触发） |
| `advancement` 使用 tag 格式（`#namespace:tag`） | 不支持，仅接受具体成就 ID。遇到 tag 格式日志 WARNING |
| 数据包重载后里程碑减少 | 已解锁的旧里程碑在 NBT 反序列化时过滤跳过，不计入总数，不显示 |
| 数据包重载后里程碑增加 | 新里程碑正常可用 |
| 数据包重载后 JSON 顺序变化 | ability 的 `countAtUnlock` 随之更新。能力强度可能变化（设计如此） |
| 玩家登录时 Registry 未加载 | `MilestoneRegistry` 在 `ServerAboutToStartEvent` 完成加载，晚于玩家登录事件。需在 `PlayerLoggedInEvent` 时检查 Registry 是否已初始化，未初始化则延迟到下一 tick |
| 客户端未收到同步包时打开 tooltip | `MilestoneRegistry` 客户端副本为空 → tooltip 显示占位符文本 "加载中..." |
| `/reload` 线程安全 | Forge 的 `/reload` 在主线程执行，`MilestoneRegistry` 重建和 `setCountAtUnlock()` 均在同一线程，无并发问题 |

## 改动量估算

| 类别 | 文件数 | 改动量 |
|------|--------|--------|
| 新增 | 3 | `MilestoneRegistry.java` (~200行) + `MilestoneTriggerManager.java` (~150行) + 默认 JSON (~80行) = ~430 行新代码 |
| Milestone 枚举 → record | 1 | 删除枚举，新增 record 类 ~30 行 |
| Ability 接口 | 1 | 移除 `requiredMilestones()`，新增 `setCountAtUnlock()` 默认方法 ~5 行 |
| 25 个 Ability 实现 | 25 | 每文件 3-5 行（加 `countAtUnlock` 字段 + setter，修改 `value()` 参数名） |
| AbilityRegistry | 1 | 移除 `getByMilestone()`/`getAvailable()`，新增 `setCountAtUnlock()`/`getCountAtUnlock()` ~25 行 |
| Capability (接口+实现+注册) | 3 | ~120 行（接口改类型 + 实现用 Set\<String\> + NBT 过滤 + Capability 所有 Milestone 引用替换） |
| MilestoneTriggerHandler → Manager | 1 | 原文件删除（~95行），新 Manager 替代 |
| Handler (AdvancementEventHandler + 5 个其他) | 6 | ~60 行 |
| 物品 (AdventureCurioItem + AdventureEndRecipe) | 2 | ~30 行 |
| UI (AbilityManagementScreen) | 1 | ~10 行 |
| 资源文件 (JSON + 翻译) | 3+ | 默认 milestones.json + 新增翻译键 |
| **总计** | **~47** | **约 800 行变更** |
