# 冒险的力量 — 里程碑数据包编写指南

## 概述

从 v1.1.5 起，"冒险的力量"的里程碑系统支持通过 **Minecraft 数据包** 完全自定义。

v1.1.5 起**彻底移除了 Minecraft 成就系统**，里程碑进度通过饰品 tooltip 和 P 键统一面板（冒险进度标签）查看。

你可以：
- 增减里程碑数量（不限于 10 个）
- 重新分配能力到不同里程碑（把飞行放到第一个！）
- 用原版成就或 8 种自定义触发器解锁里程碑
- 能力在对应里程碑解锁后**立即可用**，不用按顺序
- 多个数据包各自定义里程碑并**合并**（`merge` 模式）
- **禁用整个里程碑系统**（`enabled: false`）或**禁用个别能力**（`disabled_abilities` 黑名单）
- 只需一个 JSON 文件，无需任何 Java 代码

---

## 快速开始

### 1. 创建数据包目录

在你的世界文件夹中创建：

```
<minecraft实例>/saves/<世界名>/datapacks/<你的包名>/
├── pack.mcmeta
└── data/
    └── adventure_power/
        └── adventure_power/
            └── milestones.json
```

> **命名空间必须是 `adventure_power`**，文件位于 `adventure_power/milestones.json`。模组通过 `getResource()` 读取此固定路径，数据包中的同名文件优先级更高，**覆盖**内置默认。

### 2. pack.mcmeta

```json
{
  "pack": {
    "pack_format": 15,
    "description": "我的自定义里程碑"
  }
}
```

> `pack_format`: 1.20.1 用 15。

### 3. milestones.json

```json
{
  "milestones": [
    { ... },
    { ... }
  ]
}
```

**只需这一个文件。** 不需要任何 advancement JSON。

---

## JSON 格式参考

### 完整字段示例

```json
{
  "milestones": [
    {
      "id": "my_first_milestone",
      "name": "第一步",
      "abilities": ["agility", "digging_power"],
      "advancement": "minecraft:story/mine_stone",
      "trigger": null
    },
    {
      "id": "my_second_milestone",
      "name": "夜幕降临",
      "abilities": ["void_step"],
      "advancement": null,
      "trigger": { "type": "survive_night" }
    }
  ]
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | ✅ | 里程碑唯一 ID。只能用小写字母、数字、下划线 |
| `name` | string | ✅ | 显示在饰品 tooltip 和 P 键进度界面的名称 |
| `abilities` | string[] | ✅ | 该里程碑解锁后变为可用的能力 ID 列表 |
| `advancement` | ResourceLocation | ❌ | 关联的原版成就。玩家获得该成就即触发里程碑。填写原版成就 ID（如 `minecraft:story/mine_stone`） |
| `trigger` | object\|null | ❌ | 自定义触发器。`null` 表示不使用自定义触发 |

> **至少填一个**。两个都填则两条路径都可触发，先到先得。

### 顶层字段（文件级配置）

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `merge` | boolean | `false` | 为 `true` 时本文件的里程碑与**下方内容**（内置默认或其他数据包）按 id 合并：同 id 后者覆盖，新 id 追加。为 `false`（或省略）时本文件**整体替换**其下方的全部内容 |
| `enabled` | boolean | `true` | 为 `false` 时本文件不贡献任何内容；若为**最高优先级文件**（即数据包中的最后一个定义），整个里程碑系统被禁用，所有里程碑消失且不会回退内置默认 |
| `disabled_abilities` | string[] | `[]` | 禁用能力黑名单：列出的能力 ID 从**所有**里程碑中移除，玩家永远无法解锁（获得冒险的终点也不会重新获得）。多个文件的黑名单自动合并 |

示例——只想在默认 10 个里程碑基础上追加 1 个：

```json
{
  "merge": true,
  "milestones": [
    {
      "id": "my_bonus",
      "name": "额外挑战",
      "abilities": ["lifesteal"],
      "advancement": null,
      "trigger": { "type": "first_kill", "entity": "minecraft:ender_dragon" }
    }
  ]
}
```

示例——完全禁用里程碑系统（让模组不起作用）：

```json
{
  "enabled": false
}
```

示例——禁用太强的能力（翱翔/影杀等），其余保持默认：

```json
{
  "merge": true,
  "disabled_abilities": ["soar", "shadow_kill"]
}
```

> 容错：即使**忘写** `"merge": true`，只要文件提供了 `disabled_abilities` 而未提供 `milestones`，模组会自动按 merge 语义保留下方内容（否则空数组会回退内置默认，黑名单被清空）。但建议始终显式写 `merge` 表明意图。

---

## 可用能力 ID

30 种能力及其 ID：

| 能力名 | ID | 说明 |
|--------|-----|------|
| 灵巧 | `agility` | 概率闪避伤害 |
| 大地之力 | `digging_power` | 提升挖掘速度 |
| 恩赐永驻 | `perpetual_blessing` | 正面效果自动续期 |
| 虚空踏步 | `void_step` | 二段跳（觉醒·御风：疾跑时朝视角冲刺） |
| 休养生息 | `rapid_recovery` | 脱战直写回血 + 恢复饱食度 |
| 灵魂绑定 | `soul_bind` | 死亡保留 Buff（觉醒保留经验） |
| 不动如山 | `knockback_resist` | 减少击退 |
| 加速 | `swift` | 疾跑加速 + 降低疾跑饱食消耗（觉醒推开敌对生物） |
| 全视之眼 | `all_seeing` | 夜视 + 去雾（觉醒威胁雷达） |
| 无形之手 | `extended_reach` | 增加触及距离 |
| 磁吸 | `magnet` | 自动吸取附近掉落物（觉醒范围扩大+经验球） |
| 不朽装备 | `undying_gear` | 装备免耐久 |
| 鸿运当头 | `fortune_favor` | 时运/抢夺加成 |
| 经验加成 | `xp_boost` | 拾取经验球经验倍率（觉醒倍率提升） |
| 环境免疫 | `env_immunity` | 免疫环境伤害 |
| 嗜血 | `lifesteal` | 攻击吸血（觉醒过量转护盾） |
| 伤害抗性 | `damage_resist` | 百分比减伤 |
| 禁疗之触 | `healing_block` | 禁疗目标 |
| 坚韧之躯 | `vitality` | 提升最大生命值 |
| 死亡抗拒 | `death_defy` | 免死一次（觉醒触发免费审判） |
| 受击坚韧 | `resilience` | 受击叠层减伤 |
| 净魂 | `purified_soul` | 免疫负面效果 |
| 满载而归 | `loot_all` | 击杀生物额外掉落每样一份 |
| 翱翔 | `soar` | 创造飞行 |
| 淬魂之力 | `soul_quench` | 真实百分比伤害 |
| 破敌之眼 | `piercing_gaze` | 穿透无敌 |
| 恩赐永驻 | `perpetual_blessing` | 正面效果自动续期（觉醒不可驱散） |
| 影杀 | `shadow_kill` | 影子血量斩杀（觉醒 AOE 链） |
| 真实血量 | `true_health` | 保护血量 |
| 拒绝篡改 | `reject_manip` | 拦截非法血量修改 |
| 旅者之力 | `active_skill` | 主动技能（审判+庇护） |

---

## Trigger 预置类型

以下 8 种自定义触发器可直接选用，无需任何 advancement JSON：

### survive_night — 度过第一夜

```json
"trigger": { "type": "survive_night" }
```

**触发条件**：游戏时间达到 23000 tick（第一个黎明）且当前为白天。不需要额外参数。

---

### first_death — 首次死亡

```json
"trigger": { "type": "first_death" }
```

**触发条件**：玩家首次死亡事件（注意：如果开启了死亡抗拒能力且冷却就绪，死亡被取消则不会触发）。

---

### first_trade — 首次交易

```json
"trigger": { "type": "first_trade" }
```

**触发条件**：玩家首次与村民交互（右键点击）。

---

### y_below — 深入地下

```json
"trigger": { "type": "y_below", "y": 0 }
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `y` | int | 玩家 Y 坐标低于此值时触发 |

**触发条件**：玩家 Y 坐标低于指定值。

示例：
- `"y": 0` — Y<0（深层世界）
- `"y": -60` — Y<-60（接近基岩）
- `"y": 64` — Y<64（进入地下）

---

### first_kill — 首次击杀

```json
"trigger": { "type": "first_kill", "entity": "minecraft:wither" }
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `entity` | ResourceLocation | 目标实体 ID |

**触发条件**：玩家首次击杀指定实体类型。

示例：
- `"minecraft:wither"` — 首次击杀凋零
- `"minecraft:ender_dragon"` — 首次击杀末影龙
- `"minecraft:warden"` — 首次击杀监守者
- `"iceandfire:fire_dragon"` — 首次击杀冰火传说的火龙

---

### enter_dimension — 进入维度

```json
"trigger": { "type": "enter_dimension", "dimension": "minecraft:the_nether" }
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `dimension` | ResourceLocation | 目标维度 ID |

**触发条件**：玩家通过传送门/指令进入指定维度时触发（切换维度事件）。

示例：
- `"minecraft:the_nether"` — 进入下界
- `"minecraft:the_end"` — 进入末地
- `"twilightforest:twilight_forest"` — 进入暮色森林

> 注意：玩家**已经在**该维度时不会触发（只有切换维度才会）。玩家在维度内时 /reload 新增该里程碑，需离开再进入一次。

---

### reach_y — 到达高度

```json
"trigger": { "type": "reach_y", "y": 320 }
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `y` | int | 玩家 Y 坐标**达到或超过**此值时触发 |

**触发条件**：玩家 Y 坐标 ≥ 指定值（与 `y_below` 相反，适合"登顶高山/建造天空基地"类目标）。

---

### obtain_item — 获得物品

```json
"trigger": { "type": "obtain_item", "item": "minecraft:netherite_ingot" }
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `item` | ResourceLocation | 目标物品 ID |

**触发条件**：玩家**拾取**指定物品时触发。

示例：
- `"minecraft:diamond"` — 拾取钻石
- `"minecraft:elytra"` — 拾取鞘翅
- `"botania:manasteel_ingot"` — 拾取植物魔法的魔力钢锭

> 注意：只在拾取（捡起掉落物/物品实体）时触发，合成/箱子开出/指令给予不会触发。

---

## 使用其他模组的成就

这是数据包化最大的优势——**任何模组的成就都可以作为里程碑**。

### 示例 1：用暮色森林的成就

```json
{
  "id": "twilight_entry",
  "name": "暮色之门",
  "abilities": ["env_immunity"],
  "advancement": "twilightforest:root",
  "trigger": null
}
```

当玩家完成暮色森林的根成就（进入暮色森林），该里程碑自动解锁。

### 示例 2：用 Botania 的成就

```json
{
  "id": "botania_flower",
  "name": "植物魔法入门",
  "abilities": ["perpetual_blessing"],
  "advancement": "botania:main/flower_pickup",
  "trigger": null
}
```

### 如何找到模组的成就 ID？

1. 在游戏中按 `L` 打开成就界面
2. 进入对应模组的成就标签页
3. 按 `F3+H` 开启高级提示
4. 将鼠标悬停在成就上，查看成就 ID

或者用命令：
```
/advancement grant @s everything
```
然后查看聊天栏输出。

---

## 完整示例

### 示例 A：简化版 — 只保留 3 个里程碑

适合希望快速体验全部能力的轻量模组包：

```json
{
  "milestones": [
    {
      "id": "survive_first_night",
      "name": "活过第一夜",
      "abilities": ["agility", "perpetual_blessing", "digging_power", "void_step",
                    "rapid_recovery", "soul_bind", "knockback_resist", "undying_gear"],
      "advancement": null,
      "trigger": { "type": "survive_night" }
    },
    {
      "id": "enter_nether",
      "name": "踏入地狱",
      "abilities": ["damage_resist", "extended_reach", "env_immunity", "lifesteal",
                    "fortune_favor", "healing_block", "vitality", "resilience", "purified_soul", "loot_all"],
      "advancement": "minecraft:story/enter_the_nether",
      "trigger": null
    },
    {
      "id": "enter_end",
      "name": "终末之地",
      "abilities": ["soar", "soul_quench", "piercing_gaze", "death_defy",
                    "shadow_kill", "true_health", "reject_manip", "active_skill"],
      "advancement": "minecraft:story/enter_the_end",
      "trigger": null
    }
  ]
}
```

里程碑从 10 个浓缩为 3 个，每个里程碑解锁多个能力。玩家进度更快。

---

### 示例 B：整合包联动 — 用 RPG 模组 Boss

```json
{
  "milestones": [
    {
      "id": "welcome",
      "name": "冒险开始",
      "abilities": ["agility", "perpetual_blessing"],
      "advancement": null,
      "trigger": { "type": "survive_night" }
    },
    {
      "id": "slay_wither",
      "name": "凋零杀手",
      "abilities": ["healing_block", "vitality", "resilience", "soul_quench"],
      "advancement": null,
      "trigger": { "type": "first_kill", "entity": "minecraft:wither" }
    },
    {
      "id": "slay_dragon",
      "name": "屠龙者",
      "abilities": ["soar", "piercing_gaze", "death_defy"],
      "advancement": "minecraft:end/kill_dragon",
      "trigger": null
    },
    {
      "id": "slay_warden",
      "name": "深渊征服者",
      "abilities": ["shadow_kill", "true_health", "reject_manip", "active_skill"],
      "advancement": null,
      "trigger": { "type": "first_kill", "entity": "minecraft:warden" }
    }
  ]
}
```

---

### 示例 C：渐进式 — 15 个里程碑细粒度控制

```json
{
  "milestones": [
    { "id": "m1", "name": "初夜", "abilities": ["agility"],
      "advancement": null, "trigger": {"type":"survive_night"} },
    { "id": "m2", "name": "初死", "abilities": ["perpetual_blessing"],
      "advancement": null, "trigger": {"type":"first_death"} },
    { "id": "m3", "name": "石镐", "abilities": ["digging_power"],
      "advancement": "minecraft:story/upgrade_tools", "trigger": null },
    { "id": "m4", "name": "熔炉", "abilities": ["soul_bind"],
      "advancement": "minecraft:story/smelt_iron", "trigger": null },
    { "id": "m5", "name": "铁装", "abilities": ["knockback_resist"],
      "advancement": "minecraft:story/obtain_armor", "trigger": null },
    { "id": "m6", "name": "钻石", "abilities": ["damage_resist"],
      "advancement": "minecraft:story/mine_diamond", "trigger": null },
    { "id": "m7", "name": "交易", "abilities": ["extended_reach"],
      "advancement": null, "trigger": {"type":"first_trade"} },
    { "id": "m8", "name": "地下", "abilities": ["undying_gear"],
      "advancement": null, "trigger": {"type":"y_below","y":0} },
    { "id": "m9", "name": "附魔", "abilities": ["fortune_favor"],
      "advancement": "minecraft:story/enchant_item", "trigger": null },
    { "id": "m10", "name": "地狱", "abilities": ["env_immunity"],
      "advancement": "minecraft:story/enter_the_nether", "trigger": null },
    { "id": "m11", "name": "凋零", "abilities": ["lifesteal","healing_block"],
      "advancement": null, "trigger": {"type":"first_kill","entity":"minecraft:wither"} },
    { "id": "m12", "name": "监守", "abilities": ["vitality","resilience","loot_all"],
      "advancement": null, "trigger": {"type":"first_kill","entity":"minecraft:warden"} },
    { "id": "m13", "name": "末地", "abilities": ["purified_soul","soar"],
      "advancement": "minecraft:story/enter_the_end", "trigger": null },
    { "id": "m14", "name": "屠龙", "abilities": ["soul_quench","piercing_gaze","death_defy"],
      "advancement": "minecraft:end/kill_dragon", "trigger": null },
    { "id": "m15", "name": "鞘翅", "abilities": ["shadow_kill","true_health","reject_manip","active_skill"],
      "advancement": "minecraft:end/elytra", "trigger": null }
  ]
}
```

每个里程碑只解锁 1-2 个能力，玩家每完成一步都有新能力获取的反馈。

---

## 能力成长公式

能力数值随已解锁里程碑总数增长。以下是各能力的计算逻辑：

| 能力 | 公式 | 说明 |
|------|------|------|
| 灵巧 | `10% + 7% × (总数 - countAtUnlock)` | 闪避率 |
| 伤害抗性 | `10% + 5% × (总数 - countAtUnlock)` | 减伤率 |
| 不动如山 | `30% + 7% × (总数 - countAtUnlock)` | 击退抗性 |
| 虚空踏步 | 固定 1.2（无成长） | 跳跃倍率；觉醒·御风改为疾跑时朝视角冲刺 |
| 休养生息 | `amp_base + amp_step × floor((总数 - countAtUnlock) / 2)` | 再生等级 |
| 嗜血 | `5% + 2% × (总数 - countAtUnlock)` | 吸血率 |
| 大地之力 | `1.3 + 0.05 × (总数 - countAtUnlock)` | 挖掘倍率 |
| 无形之手 | `1.0 + 0.2 × (总数 - countAtUnlock)` | 触及距离（格） |
| 鸿运当头 | `1 + 1 × floor((总数 - countAtUnlock) / 2)` | 时运/抢夺加成 |
| 坚韧之躯 | `4 + 2 × (总数 - countAtUnlock)` | 额外半格生命 |
| 受击坚韧 | 配置三档（解锁时/解锁+1/解锁+2） | 最大减伤层数 |
| 淬魂之力 | 配置二档（解锁时/解锁+1） | 固定伤害+百分比 |
| 影杀 | 固定值 | 无成长 |

> `countAtUnlock` = 该能力所属里程碑在 JSON 数组中的位置 + 1（第 1 个里程碑为 1，第 2 个为 2……）

### 成长公式直觉

- 越早解锁的能力，成长空间越大（因为 `总数 - countAtUnlock` 更大）
- 把强力能力放在后面的里程碑，它们在解锁时数值就较高（基础值发挥作用）
- 数据包作者可以调整能力在数组中的位置来平衡强度

---

## 常见问题

### Q: 能力数值没有随里程碑增长？

检查：
1. JSON 中 `abilities` 数组的 ID 拼写是否正确
2. 里程碑顺序是否正确——能力的 `countAtUnlock` = 数组位置 + 1
3. 重启游戏或 `/reload` 使变更生效

### Q: 里程碑无法触发？

检查：
1. `advancement` 指向的成就 ID 是否正确（注意命名空间）
2. 如果使用 `trigger`，触发条件是否满足（按 P 键打开冒险进度标签查看解锁条件）
3. `advancement` 和 `trigger` 至少填一个
4. 是否已佩戴冒险的开始饰品（未佩戴时不会触发）

### Q: 使用其他模组的成就没反应？

- 确认该模组已安装且成就确实存在
- 首次获得成就时，里程碑才会解锁。如果之前已完成该成就，需要佩戴冒险的开始后，模组会自动补回
- 确认 `advancement` 指向的是成就 ID（不是成就标签 `#tag`，标签不支持）
- **不再需要**创建对应的 advancement JSON 文件——模组直接监听原版成就事件

### Q: 修改后在游戏中没看到变化？

在游戏内执行：
```
/reload
```
这会重新加载所有数据包并**自动向所有在线玩家广播更新**（tooltip 和 P 键面板立即刷新）。如果你在改 `milestones.json`，这是最快的方法，不需要重启游戏。注意：已触发过的触发器不会重复触发，新增的里程碑只对之后达成条件的玩家生效。

### Q: 多个数据包提供了 milestones.json？

**默认后加载的覆盖先加载的**。Forge 的加载顺序是：模组内置 → vanilla → 服务器数据包 → 世界数据包。如果你的两个数据包都提供同名文件，后者的文件会覆盖前者。

如果需要**追加**而非覆盖（例如你有两个数据包各定义一部分里程碑），在被追加的数据包文件中加 `"merge": true` 即可合并：同 id 的里程碑后者覆盖，新 id 追加。多个文件按优先级从低到高逐级合并，`disabled_abilities` 黑名单自动合并。

### Q: 删掉的里程碑，已解锁的玩家会怎样？

玩家已解锁但不在新 JSON 中的里程碑，会在 NBT 加载时被过滤掉（不计入总数、不显示在 tooltip 上）。如果后续又在 JSON 中加回来，之前保留的 NBT 数据会使其自动恢复。

### Q: milestones 数组可以为空吗？

**不要这样做。** 空数组会被模组检测到并自动回退到内置默认的 10 个里程碑。如果你确实想完全禁用里程碑系统，把数据包文件写成 `{ "enabled": false }` 即可——整个系统关闭，不会回退内置默认。

### Q: 怎么禁用个别能力（比如某个能力太强/有 bug）？

在数据包文件中加顶层字段：

```json
{
  "merge": true,
  "disabled_abilities": ["soar", "shadow_kill"]
}
```

列出的能力会从所有里程碑中移除：玩家无法通过里程碑解锁，面板/tooltip 不显示，**获得冒险的终点后也不会重新获得**。加 `"merge": true` 表示只改禁用列表、里程碑保持默认。

被禁用的能力可以用指令后门给指定玩家单独解锁（见下文「指令后门」），解锁后按解锁时刻的进度定位成长起点，随里程碑正常成长。

---

## 指令后门（/ap）

需要 **OP（2 级权限）**。提供两个状态变更功能 + 一个查询，**无法绕过里程碑解锁未被禁用的能力**：

| 指令 | 功能 |
|------|------|
| `/ap unlock milestone <里程碑ID> [玩家]` | 解锁里程碑（自动处理觉醒级联） |
| `/ap unlock ability <能力ID> [玩家]` | 解锁一个**被数据包禁用**的能力 |
| `/ap disabled` | 列出当前被禁用的能力 |

- `[玩家]` 省略时默认自己；也可指定目标（如 `@s`、玩家名）
- `/ap` 与 `/adventure_power` 为同一指令的别名
- 解锁里程碑：玩家需已佩戴冒险的开始（未佩戴会提示）；已解锁的里程碑会提示且不会重复
- 解锁被禁用能力：只允许 `disabled_abilities` 黑名单中的能力；未被禁用的能力会**拒绝**并提示走里程碑。解锁是 **per-player** 的（记录在该玩家的进度 NBT 中），不影响其他玩家；解锁后能力立即生效，可在 P 面板开关。**数值成长按解锁时刻的进度定位**：解锁时数值 = 基础值，之后每解锁一个里程碑正常吃成长加成（不受里程碑总数/原归属位置限制，阶梯、档位类能力同样适用）

示例：

```
/ap unlock milestone first_night
/ap unlock ability soar @s
/ap disabled
```

---

## 调试建议

1. **先小改**：从默认 JSON 出发，每次只改一个里程碑，用 `/reload` 验证
2. **检查日志**：模组在加载里程碑时会输出日志，`[MilestoneRegistry]` 前缀的信息包含加载了多少里程碑、是否有错误
3. **按 P 键**：打开冒险统一面板（冒险进度标签），查看里程碑列表和解锁条件是否正确
4. **用命令触发**：`/advancement grant @s only minecraft:story/mine_stone` 可手动触发原版成就来测试
5. **备份存档**：改动数据包前备份世界，以防意外损坏进度

---

## 版本兼容性

| 模组版本 | 数据包支持 |
|----------|-----------|
| v1.1.1 及之前 | ❌ 不支持，里程碑硬编码 |
| v1.1.5+ | ✅ 完全支持（5 种触发器） |
| v1.3.2+ | ✅ 合并模式（merge）、整体禁用（enabled:false）、能力黑名单（disabled_abilities）、8 种触发器、/reload 在线广播 |

---

## 参考链接

- [Minecraft Wiki — 数据包](https://minecraft.wiki/w/Data_pack)
- [Minecraft Wiki — 成就格式](https://minecraft.wiki/w/Advancement_definition)
- [模组 GitHub](https://github.com/ayin90723-stack/Adventures-Power)
