# CLAUDE.md

Minecraft Forge 1.20.1 模组 **冒险的力量 (Adventure's Power)** v1.0.2。

- **Forge**: 47.4.10
- **Java**: JDK 17
- **Mod ID**: `adventure_power`
- **包名**: `com.ayin90723.adventure_power`
- **依赖**: Curios API (curios-forge-5.14.1+1.20.1, body slot)
- **Git**: https://github.com/ayin90723-stack/Adventures-Power

## 构建

```bash
cd D:\download\模组\冒险的力量
./gradlew build -x test
```

构件 `build/libs/adventure_power-1.0.0.jar`。

`gradle.properties` 中代理指向 `127.0.0.1:7897`（Clash Verge），端口变更需同步修改。

## 设计理念

**力量属于冒险者自身，而非装备上的附魔。** 玩家通过 10 个里程碑（初次夜冕→苍穹之证）解锁 25 种内在能力，所有能力可自由开关。全部里程碑达成后，持有"冒险的终点"自动激活第 11 阶段「冒险者的觉醒」，10 个能力获得机制质变 + 15 个能力获得数值强化（×1.3 倍率，可配置）。

里程碑使用 **Minecraft 原版 Advancement 系统**，玩家在成就界面可查看解锁路线。6 个里程碑用原生触发器（`changed_dimension`、`player_killed_entity` 等），4 个用 `impossible` 触发器由 Java 事件检测后授予。和原版成就双向联动（如完成 `story/enter_the_nether` 自动完成 `adventure_power:nether`）。

## 代码架构

```
AdventurePower.java              # @Mod 主类：注册效果/属性/物品/配方/事件
│
├── ability/                     # 25 种能力（元数据 + 成长曲线）
│   ├── Ability.java             #   能力接口
│   └── AbilityRegistry.java     #   注册表
│
├── handler/                     # 能力效果处理 + 里程碑 + 成就 + 觉醒逻辑
│   ├── CombatAbilityHandler.java    # 灵巧/伤害抗性/淬魂/影杀/禁疗/破敌之眼觉醒
│   ├── PlayerStateHandler.java      # 灵魂绑定/净魂/翱翔/受击坚韧/环境免疫/不朽装备觉醒/庇护觉醒
│   ├── RecoveryHandler.java         # 休养生息/嗜血（含觉醒过量转护盾）
│   ├── KnockbackResistHandler.java  # 不动如山
│   ├── ExplorationAbilityHandler.java # 大地之力/无形之手/坚韧之躯
│   ├── FortuneFavorHandler.java     # 鸿运当头（抢夺+时运上下文，含觉醒 +2）
│   ├── MilestoneTriggerHandler.java # 4 个 impossible 里程碑事件检测
│   └── AdvancementEventHandler.java # 成就事件中枢（原版联动 + Capability 同步 + 补回遗漏）
│
├── capability/                  # Forge Capability — 玩家持久数据
│   ├── IAdventureProgress.java  #   接口：里程碑/能力开关/计时器/血量备份
│   ├── AdventureProgress.java   #   实现
│   └── AdventureProgressCapability.java  # 注册 + 事件 + grantMilestone 统一入口
│
├── milestone/
│   └── Milestone.java           # 10 个里程碑枚举（含 fromId）
│
├── mixin/                       # 11 个 Mixin
│   ├── SeeAndSlashMixin.java               # 见既斩 Layer 1：isInvulnerableTo
│   ├── SeeAndSlashLivingEntityAccessor.java # @Invoker 接口，暴露 actuallyHurt
│   ├── SeeAndSlashLivingEntityMixin.java    # Layer 2+2.5+3：hurt 拦截+兜底
│   ├── SeeAndSlashPlayerAttackMixin.java    # Layer 0：Player.attack 预清理
│   ├── UndyingSlashMixin.java              # 不死斩禁疗钳制
│   ├── TrueHealthMixin.java                # 真实血量备份+自动修复
│   ├── RejectHealthManipMixin.java         # HURT_DEPTH 标记+setHealth 拦截
│   ├── RejectHealthManipAttributeMixin.java # maxHealth 底线保护
│   ├── DeathDefyMixin.java                 # 死亡抗拒无敌期双层拦截
│   ├── LegacyHurtAndBreakMixin.java        # 不朽装备禁止耐久消耗
│   └── FortuneFavorMixin.java             # 鸿运当头时运加成
│
├── effect/                       # 自定义药水效果与属性
│   ├── ModEffects.java
│   ├── ModAttributes.java
│   └── UndyingSlashEffect.java   # 不死斩标记（NBT 持久化 + 三层拦截）
│
├── skill/
│   └── ActiveSkillHandler.java   # 旅者审判/庇护（含觉醒免费审判+范围扩大）
│
├── item/
│   ├── ModItems.java             # 冒险的开始/终点
│   └── AdventureEndRecipe.java   # 龙蛋+鞘翅→终点 配方
│
├── input/                        # 客户端输入处理
│   ├── InputHandler.java         # 按键注册（@EventBusSubscriber + Bus.FORGE）
│   ├── JumpInputHandler.java     # 二段跳监听
│   ├── DoubleJumpHandler.java    # 二段跳/三段跳（觉醒）服务端逻辑
│   └── ClientModEvents.java      # 客户端按键事件
│
├── ui/                           # 面板与 HUD（均支持滚动）
│   ├── BuffManagementScreen.java
│   ├── AbilityManagementScreen.java
│   ├── DeathDefyHudOverlay.java
│   └── ActiveSkillHudOverlay.java
│
├── network/
│   └── NetworkHandler.java       # 7 个网络包
│
├── config/
│   └── ModConfig.java            # TOML 配置
│
└── util/                         # 工具类
    ├── HealthUtil.java           # 反射写入 SynchedEntityData
    ├── InvulClearUtil.java       # 反射清除 Boss 自定义无敌
    ├── RejectHealthManipUtil.java # AttributeInstance 所有者映射
    ├── FriendlyFireProtection.java # 超模能力不对自身驯服生物生效
    └── FortuneContext.java       # 时运 Mixin 上下文传递（含觉醒标记）
```

### 资源目录

```
resources/
├── mixins.adventure_power.json
├── mixins.adventure_power.refmap.json
├── pack.mcmeta
├── META-INF/mods.toml
├── assets/adventure_power/lang/{zh_cn,en_us}.json
├── assets/adventure_power/models/item/{adventure_begin,adventure_end}.json
├── assets/adventure_power/textures/item/{adventure_begin,adventure_end}.png
├── data/adventure_power/
│   ├── advancements/             # 12 个成就 JSON（root + 10 里程碑 + end）
│   └── damage_type/{judgment,soul_strike}.json
└── data/curios/{entities/player.json,tags/items/body.json}
```

## 25 种能力

| 里程碑 | 能力 | ID | 类型 |
|--------|------|-----|------|
| 1 初次夜冕 | 灵巧 | agility | 闪避 10%→73% |
| 1 初次夜冕 | 恩赐永驻 | perpetual_blessing | Buff 保底 |
| 1 初次夜冕 | 大地之力 | digging_power | 挖掘速度 1.3x→1.75x |
| 2 初尝败绩 | 虚空踏步 | void_step | 二段跳 |
| 2 初尝败绩 | 休养生息 | rapid_recovery | 脱战再生 I→V |
| 3 初次交易 | 灵魂绑定 | soul_bind | 死亡保 Buff+经验 |
| 3 初次交易 | 不动如山 | knockback_resist | 击退抗性 30%→79% |
| 4 初探地底 | 伤害抗性 | damage_resist | 减伤 10%→40% |
| 4 初探地底 | 无形之手 | extended_reach | 触及距离 +1→+2.8 格 |
| 5 初次附魔 | 不朽装备 | undying_gear | 装备免耐久 |
| 5 初次附魔 | 鸿运当头 | fortune_favor | 时运+抢夺 +1→+3 |
| 6 下界 | 环境免疫 | env_immunity | 免疫环境伤害 |
| 6 下界 | 嗜血 | lifesteal | 攻击吸血 5%→13% |
| 7 凋零 | 禁疗之触 | healing_block | 禁疗 3s→6s |
| 7 凋零 | 坚韧之躯 | vitality | 最大生命 +4→+12 |
| 8 监守者 | 受击坚韧 | resilience | 叠层减伤 |
| 8 监守者 | 净魂 | purified_soul | 免疫负面效果 |
| 9 末影龙 | 淬魂之力 | soul_quench | 真实百分比伤害 |
| 9 末影龙 | 破敌之眼 | piercing_gaze | 穿透无敌 |
| 9 末影龙 | 死亡抗拒 | death_defy | 免死 |
| 9 末影龙 | 翱翔 | soar | 创造飞行 |
| 10 鞘翅 | 影杀 | shadow_kill | 影子血量斩杀 |
| 10 鞘翅 | 真实血量 | true_health | 备份修复血量 |
| 10 鞘翅 | 拒绝篡改 | reject_manip | 拦截非法修改 |
| 10 鞘翅 | 旅者之力 | active_skill | 审判+庇护 |

## 新增能力流程

1. 在 `ability/` 下创建类，实现 `Ability` 接口（~30 行）
2. 在 `AbilityRegistry` 的 static 块中 `register(new XxxAbility())`
3. 在 `handler/` 中实现效果逻辑（`@EventBusSubscriber` + `@SubscribeEvent`）
4. 在 `AdventureProgressCapability.KNOWN_ABILITIES` 中添加条目
5. 在 `lang/zh_cn.json` + `lang/en_us.json` 中添加翻译

## 新增里程碑流程

1. 在 `Milestone` 枚举中添加条目（含 `fromId` 自动识别）
2. 创建 `data/adventure_power/advancements/<id>.json`（原生触发器或 `impossible`）
3. 若有合适原版成就，在 `AdvancementEventHandler.VANILLA_TO_MILESTONE` 中添加映射
4. 若使用 `impossible`，在 `MilestoneTriggerHandler` 中添加事件检测
5. 在 `lang/zh_cn.json` + `lang/en_us.json` 中添加翻译键

## 核心设计约定

1. **能力 ID 统一**：`Ability.id()` 返回值必须与 `KNOWN_ABILITIES` 的 key 和所有 `isAbilityEnabled("xxx")` 调用的字符串一致
2. **事件驱动优先**：能力效果通过 Forge 事件触发，tick 仅用于周期性检查（恩赐永驻、受击坚韧过期等）
3. **Mixin 只做最低层拦截**：业务逻辑留在事件层，Mixin 只处理事件无法覆盖的场景
4. **能力数值配置化**：倍率/概率/持续时间通过 `ModConfig` 获取
5. **里程碑即成就**：`Milestone.getId()` = advancement JSON 的文件名 = criterion key
6. **数据三层备份**：Capability（内存）→ persistentData（实体 NBT）→ 冒险饰品物品 NBT。死亡/换维度自动恢复
7. **`@EventBusSubscriber` 明确总线**：Forge 事件必须用 `bus = Bus.FORGE`，否则静默失效
8. **觉醒通过 `isFullyUnlocked()` 检测**：各 Handler 在现有能力门禁后追加觉醒分支，不新增 Capability 字段，不侵入 Ability 接口。数值使用 `ModConfig.AWAKEN_MULTIPLIER`（默认 1.3）+ 各能力独立配置项

## Mixin 编写规范

- `@At` 的 method/target 用 **SRG 名**（如 `m_6469_` 而非 `hurt`）
- `@Mixin` 的类名用 **Mojang 名**（如 `LivingEntity.class`）
- `@Invoker` 的 method 名用 **Mojang 名**（如 `"actuallyHurt"`），需 refmap 映射
- `@Inject` 优于 `@Redirect`：不依赖 refmap
- `@Mixin` 类中禁止非 private 的 static 方法
- `@Invoker` 跨 Mixin 调用时使用 Accessor 接口模式，禁止 `(MixinClass)(Object)target` 强转
- Curios/模组类方法使用 `remap = false`
- 新增 Mixin 后须更新 `src/main/resources/mixins.adventure_power.json`
- SRG 名查找：`~/.gradle/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/srg_to_official_1.20.1.tsrg`

### refmap

`mixins.adventure_power.refmap.json` 需手动维护，格式遵循 Mixin 0.8 `ReferenceMapper` 规范：

- **外层 key**：含 `@Invoker` 的 Mixin/Accessor 类的 JVM 内部名（`/` 分隔），由 `MixinTargetContext.getClassRef()` 传入
- **内层 key**：`@Invoker` 注解值
- **值**：完整的 SRG 方法描述符 `L<owner>;<srg_name>(<params>)<return>`

当前唯一映射：`com/ayin90723/adventure_power/mixin/SeeAndSlashLivingEntityAccessor` → `actuallyHurt` → `Lnet/minecraft/world/entity/LivingEntity;m_6475_(Lnet/minecraft/world/damagesource/DamageSource;F)V`

## 冒险饰品流程

```
玩家首次登录 → 自动获得"冒险的开始" → 佩戴后授予根成就 → 激活 adventurer
里程碑 1~9 逐步解锁（任意顺序，成就界面可查看）
里程碑 10（获得鞘翅）→ 全里程碑达成 → 冒险的开始自动替换为冒险的终点
  → 授予终极成就 → 激活 fullyUnlocked → 全部 25 种能力可用
  → 第 11 阶段「冒险者的觉醒」自动生效（10 质变 + 15 数值强化）
```

## 配置

`ModConfig.java` — 字段声明在上，static 块 `push/pop` 初始化。顺序错误会导致 TOML 子目录失效。
