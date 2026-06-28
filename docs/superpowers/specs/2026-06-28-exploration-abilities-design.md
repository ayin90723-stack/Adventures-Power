# 探索/采集能力设计文档（第二批）

**日期**: 2026-06-28  
**状态**: 已批准  
**目标**: 新增挖掘速度、触及距离、时运+抢夺、最大血量四个能力，弥补探索和采集领域的缺口

---

## 背景

第一批能力全部是战斗和保命类型。经讨论新增 4 个探索/采集能力，加上"坚韧之躯"凑整 25 个。

---

## 新增能力

### 1. 大地之力 (digging_power)

| 属性 | 值 |
|------|-----|
| 里程碑 | 1 初次夜冕 |
| 类型 | 被动 |
| 描述 | 提升挖掘速度 |
| 成长 | 1.3x→1.75x |
| 配置键 | `digging_power_base`, `digging_power_per_milestone` |

**机制**：
- 使用 `PlayerEvent.BreakSpeed` 事件
- `event.getOriginalSpeed()` 乘以能力倍数
- 公式：`speed * (base + per_milestone × (milestones - required))`
- 默认：base=1.3, per_milestone=0.05

**参考**：七咒之戒 EnigmaticEventHandler.miningStuff（BreakSpeed 事件）

### 2. 无形之手 (extended_reach)

| 属性 | 值 |
|------|-----|
| 里程碑 | 4 初探地底 |
| 类型 | 被动 |
| 描述 | 增加方块交互距离 |
| 成长 | +1→+2.8 格 |
| 配置键 | `extended_reach_base`, `extended_reach_per_milestone` |

**机制**：
- 使用 Forge 内置属性 `ForgeMod.BLOCK_REACH`（`net.minecraftforge.common.ForgeMod`）
- 能力启用时 `setBaseValue` 为 `defaultValue + base + per_milestone × (milestones - 4)`
- 同不動如山模式：PlayerTick 检查 + PlayerEvent.Clone 恢复 + Logout 重置
- 默认：base=1.0, per_milestone=0.2

**说明**：
- 不动如山已经建立了"直接操作属性"的模式，无形之手完全复制
- milestone 4 "初探地底"——深入地下需要更远触及，自然匹配

### 3. 鸿运当头 (fortune_favor)

| 属性 | 值 |
|------|-----|
| 里程碑 | 5 初次附魔 |
| 类型 | 被动 |
| 描述 | 提升时运和抢夺等级 |
| 成长 | +1→+3 级 |
| 配置键 | `fortune_favor_bonus_base`, `fortune_favor_bonus_step` |

**机制**：

**抢夺部分**（无 Mixin）：
- Forge 内置 `LootingLevelEvent`，在事件中检测攻击者是否有能力
- `event.setLootingLevel(event.getLootingLevel() + bonus)` 在原工具等级上叠加

**时运部分**（需 Mixin）：
- Mixin 拦截 `EnchantmentHelper.getItemEnchantmentLevel(Enchantment, ItemStack)`
- 当查询 `Enchantments.BLOCK_FORTUNE` 时：
  - 获取当前挖掘的玩家（通过 `BlockEvent.BreakEvent` 提前传入上下文，或反射获取破坏者）
  - 如果玩家启用了鸿运当头，返回 `originalLevel + bonus`

**Mixin 详细**：
- 目标类：`net.minecraft.world.item.enchantment.EnchantmentHelper`
- 目标方法：`getItemEnchantmentLevel`（SRG: `m_44843_`）
- 注入点：`@Inject` at `RETURN`，`@Local` 捕获返回值
- 在 Enigmatic Legacy 的 Curios `getFortuneLevel` 不可用的情况下（我们是能力不是物品），Mixin 是最干净的方案

**说明**：
- milestone 5 "初次附魔"——附魔台后解锁掉落增强，和附魔系统自然衔接

### 4. 坚韧之躯 (vitality)

| 属性 | 值 |
|------|-----|
| 里程碑 | 7 凋零 |
| 类型 | 被动 |
| 描述 | 提高最大生命值 |
| 成长 | +4→+12 血（+2→+6 心） |
| 配置键 | `vitality_base`, `vitality_per_milestone` |

**机制**：
- 使用原版 `Attributes.MAX_HEALTH` 属性
- 能力启用时 `setBaseValue(20.0 + bonus)`（20 是玩家默认最大血量）
- PlayerTick 检查 + PlayerEvent.Clone 恢复 + Logout 重置
- 公式：`bonus = base + per_milestone × (milestones - 7)`，默认 base=4.0, per_milestone=2.0
- 里程碑7=+4(22血), 8=+6(24血), 9=+8(28血), 10=+12(32血)

**说明**：
- milestone 7 "凋零之陨"——击败凋零后获得更强的体魄
- 配合不动如山、受击坚韧、伤害抗性，构成完整的生存防御体系

---

## 里程碑分布（更新后）

| # | 里程碑 | 能力 |
|---|--------|------|
| 1 | 初次夜冕 | 灵巧、恩赐永驻、**大地之力** |
| 2 | 初尝败绩 | 虚空踏步、休养生息 |
| 3 | 初次交易 | 灵魂绑定、不动如山 |
| 4 | 初探地底 | 伤害抗性、**无形之手** |
| 5 | 初次附魔 | 不朽装备、**鸿运当头** |
| 6 | 下界 | 环境免疫、嗜血 |
| 7 | 凋零 | 禁疗之触、**坚韧之躯** |
| 8 | 监守者 | 受击坚韧、净魂 |
| 9 | 末影龙 | 翱翔、淬魂、破敌、死亡抗拒 |
| 10 | 鞘翅 | 影杀、真实血量、拒绝篡改、旅者之力 |

共 **25 种能力**（第一批 21 + 第二批 4），里程碑 1~7 每层 2 个，分布均匀。

---

## 需要修改的文件

### 新增文件
- `ability/DiggingPowerAbility.java`
- `ability/ExtendedReachAbility.java`
- `ability/FortuneFavorAbility.java`
- `ability/VitalityAbility.java`
- `handler/ExplorationAbilityHandler.java` — 大地之力 + 无形之手 + 坚韧之躯（属性类）
- `handler/FortuneFavorHandler.java` — 鸿运当头（抢夺+LootingLevelEvent）
- `mixin/FortuneFavorMixin.java` — 鸿运当头（时运+Mixin）
- `util/FortuneContext.java` — 时运 Mixin 的线程局部上下文传递

### 修改文件
- `AbilityRegistry.java` — 注册 4 个新能力
- `AdventureProgressCapability.java` — `KNOWN_ABILITIES` 添加 4 条目
- `ModConfig.java` — 添加 4 组配置
- `lang/zh_cn.json` + `lang/en_us.json` — 翻译
- `resources/mixins.adventure_power.json` — 注册 FortuneFavorMixin

---

## 与现有系统的交互

| 交互 | 影响 |
|------|------|
| 大地之力 + 急迫效果 | 乘法叠加（事件在原速基础上乘） |
| 无形之手 + 不动如山 | 同模式，各自操作不同属性，无冲突 |
| 鸿运当头 + 工具自带时运/抢夺 | 加法叠加（Mixin 返回 original+bonus） |
| 鸿运当头 + 其他 mod 的时运修改 | 最后注册的 Mixin 胜出，需注意优先级 |
| 坚韧之躯 + 死亡抗拒 | 死亡抗拒回血用固定值 20 而非 getMaxHealth()，需改为用 getMaxHealth() |
| 坚韧之躯 + 真实血量 | 备份值基于新 maxHealth，无需特殊处理 |
| 坚韧之躯 + Player.Clone | MAX_HEALTH 属性在死亡/维度切换后重置为 20，需恢复 |
```

---

## 配置默认值

```toml
[冒险能力配置.能力数值]

[冒险能力配置.能力数值.大地之力]
digging_power_base = 1.3               # 里程碑1时的挖掘速度倍数
digging_power_per_milestone = 0.05     # 每额外里程碑增加的倍数

[冒险能力配置.能力数值.无形之手]
extended_reach_base = 1.0              # 里程碑4时增加的格数
extended_reach_per_milestone = 0.2     # 每额外里程碑增加的格数

[冒险能力配置.能力数值.鸿运当头]
fortune_favor_bonus_base = 1           # 里程碑5时的时运/抢夺加成等级
fortune_favor_bonus_step = 1           # 每2个里程碑增加的等级

[冒险能力配置.能力数值.坚韧之躯]
vitality_base = 4.0                    # 里程碑7时增加的血量（半格为单位）
vitality_per_milestone = 2.0           # 每额外里程碑增加的血量
```
