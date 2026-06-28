# 第11阶段「冒险者的觉醒」设计文档

**日期**: 2026-06-28  
**状态**: 待实现

## 概述

在全部 10 个里程碑解锁后，持有"冒险的终点"饰品的玩家自动激活第 11 阶段——「冒险者的觉醒」。觉醒对已有的 25 种能力进行全面强化：10 个标志性能力获得机制质变，15 个能力获得数值倍增。

## 触发机制

- **条件**: `IAdventureProgress.isFullyUnlocked() == true`（持有冒险的终点时自动为 true）
- **方式**: 自动生效，无需额外开关或操作
- **实现**: 各 Handler 层检测 `fullyUnlocked` 状态，在现有 `isAdventurer()` 检查之后追加觉醒逻辑

## 强化设计

### 机制质变（10 个能力）

#### 1. 虚空踏步 (void_step) — 三段跳

- **现状**: 二段跳
- **觉醒**: 允许在空中再跳一次（总共三段）
- **实现**: `DoubleJumpHandler` 追踪跳跃次数，觉醒时允许 `jumps >= 2` 而非 `>= 1`

#### 2. 灵魂绑定 (soul_bind) — 保留经验值

- **现状**: 死亡时保留 Buff 种类和时长
- **觉醒**: 死亡时经验值不掉落（设为 0 掉落而非清零经验）
- **实现**: `PlayerStateHandler` 在 `LivingDeathEvent` 中检测觉醒，调用 `player.setExperienceLevels()` 和 `player.totalExperience` 保留值并在重生后恢复

#### 3. 不朽装备 (undying_gear) — 装备属性加成

- **现状**: 装备不消耗耐久
- **觉醒**: 每件已装备的护甲提供额外 +1 护甲值（总计 +4），主手武器 +15% 攻击伤害
- **实现**: `PlayerStateHandler` 的 tick 中检测觉醒，通过 `AttributeModifier` 动态应用加成

#### 4. 翱翔 (soar) — 飞行速度 +50%

- **现状**: 创造模式飞行
- **觉醒**: 飞行速度提升 50%（`player.getAbilities().flyingSpeed *= 1.5`）
- **实现**: 检测觉醒后一次性设置 `flyingSpeed *= 1.5`（注意不能在 tick 中重复乘法，会指数爆炸），能力关闭时恢复原值

#### 5. 死亡抗拒 (death_defy) — 触发时释放免费审判

- **现状**: 致命伤害取消死亡 + 无敌 + 回血 + 冷却
- **觉醒**: 触发死亡抗拒时自动对周围敌人释放一次旅者审判（无视审判冷却和 GCD）
- **实现**: `AdventureProgressCapability.onPlayerDeath` 中，在取消死亡后调用 `ActiveSkillHandler.executeJudgment(player, free=true)`

#### 6. 影杀 (shadow_kill) — AOE 爆炸

- **现状**: 影子血量归零时饱和式斩杀单体
- **觉醒**: 斩杀时对目标 8 格范围内所有敌对生物施加 15% 最大生命的影子血量削减
- **实现**: `CombatAbilityHandler.handleShadowKill` 中，在斩杀逻辑后遍历周围实体

#### 7. 淬魂之力 (soul_quench) — 斩杀线

- **现状**: 真实百分比伤害（固定 + maxHP% + currentHP%）
- **觉醒**: 目标当前生命低于 20% 最大生命时，真实伤害翻倍
- **实现**: `CombatAbilityHandler.handleSoulQuench` 中追加判断

#### 8. 嗜血 (lifesteal) — 过量治疗转护盾

- **现状**: 攻击吸血（5%→13%），有单次上限
- **觉醒**: 超过满血部分的治疗量转为吸收护盾（上限 20% 最大生命，持续 5 秒）
- **实现**: `RecoveryHandler` 中计算 `healAmount - toFull`，差值应用 `absorptionAmount`

#### 9. 净魂 (purified_soul) — 虚弱光环

- **现状**: 免疫所有负面药水效果
- **觉醒**: 周围 16 格内敌对生物自动获得虚弱 II（每 2 秒刷新）
- **实现**: `PlayerStateHandler` 的净魂 tick 中追加范围遍历

#### 10. 旅者之力 (active_skill) — 审判扩大 + 庇护可移动

- **现状**: 审判 AOE（6 格），庇护期间不可移动
- **觉醒**: 审判范围 +50%（9 格），庇护期间允许缓慢移动（速度 ×0.3）
- **实现**: `ActiveSkillHandler` 中检测觉醒状态调整参数

### 数值强化（15 个能力）

统一应用 **×1.3 倍率**（配置项 `AWAKEN_MULTIPLIER`，默认 1.3）到当前能力数值：

| 能力 | ID | 觉醒前(10里程碑满值) | 觉醒后 |
|------|-----|---------------------|--------|
| 灵巧 | agility | 73% 闪避 | 95%（封顶，不超100%） |
| 大地之力 | digging_power | 1.75x 挖掘 | 2.28x |
| 恩赐永驻 | perpetual_blessing | Buff 保底延长 | ×1.3 |
| 休养生息 | rapid_recovery | 再生 V | 再生 V + 2 级 = VII |
| 不动如山 | knockback_resist | 79% 抗性 | 100%（封顶） |
| 伤害抗性 | damage_resist | 40% 减伤 | 52% |
| 无形之手 | extended_reach | +2.8 格 | +3.6 格 |
| 鸿运当头 | fortune_favor | +3 等级 | +5 等级（+2 固定） |
| 禁疗之触 | healing_block | 6 秒 | 8 秒（向上取整） |
| 坚韧之躯 | vitality | +12 HP | +16 HP |
| 受击坚韧 | resilience | 12 层 | 18 层（+6 固定） |
| 环境免疫 | env_immunity | 免疫环境伤害 | 额外免疫所有无源伤害 |
| 破敌之眼 | piercing_gaze | 穿透无敌 | 对无敌目标 +30% 伤害 |
| 真实血量 | true_health | 血量备份修复 | 修复速度翻倍 |
| 拒绝篡改 | reject_manip | 拦截非法 HP 修改 | 反弹 30% 被拦截的伤害给来源 |

## 实现架构

### 配置层

`ModConfig.java` 新增配置项：

```java
// 觉醒全局
public static final DoubleValue AWAKEN_MULTIPLIER;  // 默认 1.3

// 各质变能力的独立配置
public static final IntValue AWAKEN_VOID_STEP_JUMPS;        // 3
public static final DoubleValue AWAKEN_SOAR_SPEED;           // 1.5
public static final DoubleValue AWAKEN_SOUL_QUENCH_EXECUTE_THRESHOLD; // 0.2
public static final DoubleValue AWAKEN_SHADOW_KILL_AOE_RADIUS;        // 8.0
public static final DoubleValue AWAKEN_SHADOW_KILL_AOE_RATIO;         // 0.15
public static final DoubleValue AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS;   // 16
public static final DoubleValue AWAKEN_LIFESTEAL_SHIELD_CAP;          // 0.2
public static final IntValue AWAKEN_LIFESTEAL_SHIELD_DURATION;       // 100
public static final IntValue AWAKEN_PURIFIED_SOUL_RADIUS;             // 16
public static final DoubleValue AWAKEN_JUDGMENT_RANGE_MULT;           // 1.5
public static final DoubleValue AWAKEN_SANCTUARY_SPEED;               // 0.3
public static final DoubleValue AWAKEN_UNDYING_ARMOR_BONUS;           // 1.0
public static final DoubleValue AWAKEN_UNDYING_WEAPON_BONUS;          // 0.15
public static final IntValue AWAKEN_ENV_IMMUNITY_SOURCELESS;          // 1 (bool)
```

### 能力接口

`Ability.value(int milestones)` 保持不变。觉醒检查在 Handler 层进行，不侵入 Ability 接口。

### Handler 改动

各 Handler 在现有 `isAbilityEnabled()` 检查之后，追加一层觉醒逻辑：

```java
boolean awakened = progress.isFullyUnlocked();
if (awakened) {
    value = applyAwakenBoost(value);
}
```

### 数据流

```
玩家持有冒险的终点
  → onPlayerTick 检测 → activateFullyUnlocked()
  → 各 Handler 通过 isFullyUnlocked() 感知觉醒状态
  → 应用数值倍率或质变逻辑
```

无需新增 Capability 字段，`fullyUnlocked` 现有布尔值完全够用。

## 测试要点

1. 冒险的终点替换冒险的开始时，觉醒同步生效
2. 冒险的终点丢失（如死亡掉落）后，觉醒立即失效
3. 每个质变能力的独立效果验证（三段跳、AOE 斩杀等）
4. 数值强化倍率正确应用，封顶值（如灵巧95%、不动如山100%）不溢出
5. 觉醒状态下关闭对应能力，觉醒效果也停止
6. 服务端/客户端数据同步（能力面板 tooltip 显示觉醒状态）

## 翻译键

```
awakened.adventure_power.title=冒险者的觉醒
awakened.adventure_power.desc=所有能力获得大幅强化
ability.adventure_power.xxx.awakened=觉醒效果描述（各能力追加的 tooltip 行）
```

## 与现有系统的兼容性

- **里程碑系统**: 不变，`Milestone` 枚举不新增条目
- **冒险饰品流程**: 不变，`replaceBeginWithEnd` 已触发 `activateFullyUnlocked`
- **能力开关**: 不变，开关照常工作，觉醒只在开启状态增强
- **存档兼容**: 无需迁移，`fullyUnlocked` 已在 NBT 中持久化
- **网络同步**: `AdventureSyncPacket` 已传输 `fullyUnlocked` 状态
