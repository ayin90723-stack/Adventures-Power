# 三个新能力设计文档

**日期**: 2026-06-28  
**状态**: 已批准  
**目标**: 新增抗击退和回血能力，弥补现有能力系统的防御缺口

---

## 背景

当前 18 种能力缺少：
- **抗击退**：玩家被攻击后击退距离过大，无法有效站场
- **快速回血**：战斗中吸血 + 脱战快速自愈均缺失

经讨论决定新增 3 个独立能力。

---

## 新增能力

### 1. 休养生息 (rapid_recovery)

| 属性 | 值 |
|------|-----|
| 里程碑 | 2 初尝败绩 |
| 类型 | 被动/周期性 |
| 描述 | 脱战后自动获得生命恢复效果 |
| 成长 | 里程碑2=再生I, 每+2里程碑+1级, 里程碑10=再生V |
| 配置键 | `rapid_recovery_amplifier_base`, `rapid_recovery_amplifier_step` |

**机制**：
- PlayerTick 中检测 `currentTime - lastHurtTime` 超出阈值后，周期性施加原版生命恢复药水效果
- 受击后立即中断，lastHurtTime 更新，玩家需重新等待脱战间隔
- 脱战间隔默认 5 秒（100 tick），可通过配置调整
- 不与外部药水冲突——如果玩家已有更高的再生等级则保留更高的

**说明**：
- 里程碑 2「初尝败绩」是玩家第一次死亡后获得，自然地"死过了，知道休息"
- 给早期玩家一个生存 QoL 能力

### 2. 不动如山 (knockback_resist)

| 属性 | 值 |
|------|-----|
| 里程碑 | 3 初次交易 |
| 类型 | 被动 |
| 描述 | 百分比减少受到的击退距离 |
| 成长 | 里程碑3=30%, 每+1里程碑+7%, 里程碑10=79% |
| 配置键 | `knockback_resist_base`, `knockback_resist_per_milestone` |

**机制**：
- 使用原版 `Attributes.KNOCKBACK_RESISTANCE` 属性
- 通过 `EntityAttributeModificationEvent` 给玩家注册该属性
- 能力启用时 `setBaseValue` 为当前里程碑对应的百分比
- 能力关闭或里程碑变化时同步更新属性值

**说明**：
- 里程碑 3「初次交易」——有了交易就有了资源，"站稳脚跟"的时机
- 直接复用原版击退抗性属性，不自己拦截击退，与其他模组的击退修改兼容

### 3. 嗜血 (lifesteal)

| 属性 | 值 |
|------|-----|
| 里程碑 | 6 下界 |
| 类型 | 被动/攻击触发 |
| 描述 | 攻击造成伤害时回复自身生命值 |
| 成长 | 里程碑6=5%, 每+1里程碑+2%, 里程碑10=13% |
| 配置键 | `lifesteal_base`, `lifesteal_per_milestone` |

**机制**：
- LivingHurtEvent 中攻击方逻辑，按实际造成伤害 × 百分比回复攻击者生命
- 跳过内部穿透伤害（`soul_strike` / `judgment`）防递归
- 回复量上限为攻击者最大生命值的 20%，防止对高血量 Boss 过量吸血
- 与净魂、影杀等能力在同一个 LivingHurtEvent 中，利用现有的防重入机制

**说明**：
- 里程碑 6「下界」——进入更危险的维度，生存压力变大，需要吸血维持血量
- 与里程碑 7 的禁疗之触形成"治疗主题"对称：6 强化自己的血，7 禁敌人的疗

---

## 里程碑分布（更新后）

| # | 里程碑 | 能力 |
|---|--------|------|
| 1 | 初次夜冕 | 灵巧、恩赐永驻 |
| 2 | 初尝败绩 | 虚空踏步、**休养生息** |
| 3 | 初次交易 | 灵魂绑定、**不动如山** |
| 4 | 初探地底 | 伤害抗性 |
| 5 | 初次附魔 | 不朽装备 |
| 6 | 下界 | 环境免疫、**嗜血** |
| 7 | 凋零 | 禁疗之触 |
| 8 | 监守者 | 受击坚韧、净魂 |
| 9 | 末影龙 | 翱翔、淬魂、破敌、死亡抗拒 |
| 10 | 鞘翅 | 影杀、真实血量、拒绝篡改、旅者之力 |

共 21 种能力（新增 3 种），分布更加均匀。

---

## 需要修改的文件

### 新增文件
- `ability/RapidRecoveryAbility.java`
- `ability/KnockbackResistAbility.java`
- `ability/LifestealAbility.java`
- `handler/HealingAbilityHandler.java` — 休养生息 + 嗜血效果
- `handler/KnockbackResistHandler.java` — 不动如山属性管理

### 修改文件
- `AbilityRegistry.java` — 注册 3 个新能力
- `AdventureProgressCapability.java` — `KNOWN_ABILITIES` 添加 3 个条目
- `ModConfig.java` — 添加 3 组能力数值配置
- `lang/zh_cn.json` — 添加 3 个能力翻译键
- `lang/en_us.json` — 添加 3 个能力翻译键

### 新增资源
- `data/adventure_power/advancements/` — 无需新增（里程碑不变）

---

## 与现有系统的交互

| 交互 | 影响 |
|------|------|
| 净魂 | 休养生息施压的再生是正面效果，不会被净魂清除（净魂只清负面） |
| 灵魂绑定 | 嗜血不受灵魂绑定影响（不是 Buff） |
| 恩赐永驻 | 休养生息施加的再生受恩赐永驻延长（是正面 Buff） |
| 禁疗之触 | 目标身上的禁疗不影响嗜血的回血（嗜血回攻击者自己，禁疗禁的是目标回血） |
| 淬魂/影杀 | 嗜血对内部穿透伤害跳过，防止递归 |
| 伤害抗性/受击坚韧 | 嗜血按实际造成伤害计算，先减伤后吸血 |
| 不动如山 | 直接改属性值，不影响伤害计算链 |

---

## 配置默认值

```toml
[冒险能力配置.能力数值]

[冒险能力配置.能力数值.休养生息]
rapid_recovery_amplifier_base = 0       # 里程碑2=再生I(0级), 配置的是amplifier
rapid_recovery_amplifier_step = 1       # 每2里程碑+1级
rapid_recovery_delay_ticks = 100        # 脱战等待时间(tick), 默认5秒

[冒险能力配置.能力数值.不动如山]
knockback_resist_base = 30              # 里程碑3=30%
knockback_resist_per_milestone = 7      # 每里程碑+7%

[冒险能力配置.能力数值.嗜血]
lifesteal_base = 5                      # 里程碑6=5%
lifesteal_per_milestone = 2             # 每里程碑+2%
lifesteal_cap_ratio = 0.2               # 单次吸血上限=最大生命值20%
```
