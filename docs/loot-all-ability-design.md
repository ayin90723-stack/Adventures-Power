# 掉落满载能力 实现设计文档（已实现 · 方案 G+）

> 状态：已实现并 build 通过，经 3 路子代理审查 + 修复
> 方案 G+（Mixin 绕过条件 + 遍历 entries）
> 能力为第 26 个能力，由用户反馈提出
> 1.20.1 原版结构已反编译核实，Mixin 拦截点均经验证存在

## 一、能力定位

| 项 | 值 |
|----|----|
| 名称 | 满载而归 |
| ID | `loot_all` |
| 所属里程碑 | `warden`（幽匿之惧） |
| 定位 | 原版掉落保留，额外按掉落表"每样至少一份"追加，无视条件、全模组兼容 |

## 二、需求确认

1. **每样至少一份**（遍历所有 entries，打破 weight 单选）
2. **全兼容**（模组新增掉落 + 自定义 entry 类型都拿得到，因用原版 expand/createItemStack）
3. **无视条件**（特定方法杀 Boss、火杀熟肉等条件性掉落，不用满足条件也掉）
4. **放弃取最大数量**（基础数量随机，由 copies 控制每样份数）；**觉醒补回取最大**
5. Boss 纳入（末影龙/凋灵等照走）
6. 抢夺：1.20.1 通过 `KILLER_ENTITY` 自动随玩家抢夺附魔（`getLootingModifier` -> `ForgeHooks.getLootingLevel`），无法单独关闭，无配置项

## 三、1.20.1 核实结构（反编译确认）

| 类/方法 | 可见性 | SRG 名 | 作用 |
|---------|--------|--------|------|
| `LootPoolEntryContainer.canRun(LootContext)Z` | protected final | `m_79639_` | entry 级条件检查 |
| `LootPool.entries` 字段 | package-private final | `f_79023_` | 该 pool 的所有 entry |
| `LootPool.addRandomItems(Consumer,LootContext)V` | public | `m_79053_` | pool 滚动入口（按 weight 抽 rolls 次） |
| `LootPoolEntryContainer.expand(LootContext,Consumer<LootPoolEntry>)Z` | public（继承自 ComposableEntryContainer） | - | 展开 entry 为 LootPoolEntry（内部调 canRun） |
| `LootPoolEntry.createItemStack(Consumer,LootContext)V` | public（接口） | `m_6941_` | 生成物品（跑 functions） |
| `LootContext.Builder.create(ResourceLocation)` | public | `m_287259_` | 构造 LootContext（内部 new LootContext 4 参数 private 构造） |
| `LootTable.getRandomItems(LootParams)` | public | `m_287195_` | 表滚取入口 |

> **关键修正**（build 失败发现）：`LootPoolEntryContainer` **没有** `createItemStack`，只有 `expand`。正确链路：`expand` 展开 `LootPoolEntry`（内部 canRun 被 Mixin 绕过）-> `lootPoolEntry.createItemStack`。

## 四、方案原理

### 核心
用 `getRandomItems(lootParams)` 作入口（全兼容 + 零自构 LootContext），加 **3 个 Mixin + 2 个 ThreadLocal 标志位**：
- **Mixin1** 让 `canRun` 总返回 true -> 无视 entry 级条件
- **Mixin2** 拦截 `addRandomItems`，BYPASS=true 时遍历 entries 各调 `expand` -> `createItemStack` -> 无视 pool 级条件 + 每样一份
- **Mixin3（觉醒）** `@ModifyArg` 拦截 `LootContext` 构造的 random 参数，AWAKEN=true 时注入 `ConstantMaxRandomSource` -> 取最大数量
- ThreadLocal 标志位 **save-restore** 隔离，原版掉落不受影响

### expand 对复合 entry 的行为（审查反编译验证）
- `LootItem`/`TagEntry`（SingletonContainer）：expand accept 自己 -> 每样一份 ✅
- `EntryGroup`（and）：全展开 -> 每个子一份 ✅
- `AlternativesEntry`（or）：短路首个命中 -> 只给一个 alternative。**符合 alternatives"任选一"语义，非 bug**

### luck 无意义
Mixin2 绕过 rolls 计算，luck 不生效，无 luck 配置。

## 五、Mixin 设计

### Mixin 1：`LootPoolEntryContainerMixin`（无视 entry 条件）
`@Inject(method="m_79639_", at=@At("HEAD"), cancellable=true)`，BYPASS=true 时 `cir.setReturnValue(true)`。

### Mixin 2：`LootPoolMixin`（无视 pool 条件 + 每样一份）
`@Inject(method="m_79053_", at=@At("HEAD"), cancellable=true)`，BYPASS=true 时遍历 `((LootPoolAccessor)(Object)this).getEntries()`，对每个调 `entryContainer.expand(ctx, lootPoolEntry -> lootPoolEntry.createItemStack(consumer, ctx))`，`ci.cancel()`。

### Mixin 3：`LootContextBuilderMixin`（觉醒取最大）
`@ModifyArg(method="m_287259_", at=@At(INVOKE, target="LootContext;<init>(LootParams;RandomSource;LootDataResolver;ResourceLocation;)V"), index=1)`，BYPASS && AWAKEN 时返回 `ConstantMaxRandomSource.INSTANCE`。
> target 是 4 参数 private 构造（javap -c 字节码确认 create 调此构造，CFR 反编译误显示 3 参数）。

### Accessor：`LootPoolAccessor`
`@Accessor("entries")` 暴露 entries 字段。refmap: `"entries": "f_79023_"`。

## 六、数据流

```
LivingDropsEvent（bus=FORGE）
  ├─ 门禁：killer instanceof ServerPlayer + isAdventurer + isAbilityEnabled("loot_all")
  ├─ 友伤保护 FriendlyFireProtection.isOwnerTarget
  ├─ LootTable table = getLootData().getLootTable(entity.getLootTable())
  ├─ LootParams = Builder(level)
  │     .withParameter(THIS_ENTITY, entity)
  │     .withParameter(ORIGIN, entity.position())
  │     .withParameter(DAMAGE_SOURCE, event.getSource())
  │     .withOptionalParameter(KILLER_ENTITY, player)            // 抢夺自动随此
  │     .withOptionalParameter(DIRECT_KILLER_ENTITY, source.getDirectEntity())
  │     .withOptionalParameter(LAST_DAMAGE_PLAYER, player)
  │     .create(ENTITY)
  ├─ 觉醒判定：fullyUnlocked && LOOT_ALL_AWAKENED_MAX_COUNT
  ├─ save-restore 标志位：prevBypass/prevAwaken 保存，BYPASS=true, AWAKEN=awakened
  │   try { for copies 次: getRandomItems 追加 ItemEntity，++generated>=maxItems 则 break }
  │   finally { 恢复 prevBypass/prevAwaken }
  └─ 原版 drops 不动
```

## 七、配置项（ModConfig）

```toml
[满载而归]
loot_all_copies = 1                    # 基础每样份数
loot_all_max_items = 100              # 单次击杀额外掉落物总数量上限（防极端配置卡服）
[觉醒强化]
loot_all_awakened_max_count = true    # 觉醒取最大数量
loot_all_awakened_copies = 2          # 觉醒每样份数
```

> 无 luck/looting 配置（luck 无效；1.20.1 抢夺自动随 KILLER_ENTITY）。

## 八、零反射说明
除 `@Accessor` entries 字段外，无裸反射、无自构 LootContext、无改 final 字段。`expand`/`createItemStack`/`getRandomItems` 全原版 public API，模组自定义 entry 与 function 全兼容。

## 九、注册流程（已全部完成）
1. `LootAllAbility` 实现 Ability 接口
2. `AbilityRegistry` register
3. `LootAllHandler`（LivingDropsEvent + 标志位 + 滚取 + maxItems 上限）
4. 3 Mixin + 1 Accessor + `ConstantMaxRandomSource`
5. `KNOWN_ABILITIES` 加 loot_all
6. `MilestoneRegistry.loadBuiltinDefaults` + `milestones.json` 的 warden 加 loot_all
7. lang zh_cn + en_us
8. ModConfig 4 项配置
9. mixin json + refmap

## 十、边界与限制

1. **Forge GlobalLootModifier (GLM)**：独立于 loot table 的 Forge 扩展层（如星月纯净之星），G+ 无法覆盖。详见第十三节。
2. **table 级 conditions**：`getRandomItems` 入口仍检查。原版表无 table 级条件；若模组用了且不满足，G+ 拿不到（可升级 `@Accessor` 拿 `LootTable.pools` 直接遍历 `addRandomItems` 绕过 table 级）。
3. **min=0 的 entry**：基础模式下 createItemStack 有概率生成空 stack（被过滤），"放弃取最大"的代价；觉醒取最大后无此问题。
4. **AlternativesEntry**：只给首个 alternative（符合"任选一"语义，非 bug）。
5. **嵌套 LootTableReference**：BYPASS=true 期间被引用表的 entry 条件也被绕过（"无视条件"设计意图，含被引用表）。
6. `entity.getLootTable()` 返回 null/空 -> 跳过
7. Boss 照走；末影龙经验不受影响（非 loot table）
8. 性能：max_items 上限防极端 copies 配置卡服
9. 与"鸿运当头"自然叠加（抢夺通过 KILLER_ENTITY 自动）

## 十一、复杂度评估

~200 行 + 3 Mixin + 1 Accessor + 1 工具类（ConstantMaxRandomSource），中等。

## 十二、方案对比

| 维度 | F | G | **G+** | E |
|------|---|---|--------|---|
| 每样至少一份 | ❌ | ❌ | ✅ | ✅ |
| 全兼容（含自定义 entry） | ✅ | ✅ | ✅ | ❌ 罕见 entry 漏 |
| 无视条件 | ❌ | ✅ | ✅ | ✅ |
| 取最大数量 | ❌ | ❌ | ❌（觉醒 ✅） | ✅ |
| 复杂度 | 最低 | 低 | 中 | 最高 |

**G+ 命中"每样一份 + 全兼容 + 无视条件"三要素交集，觉醒补回取最大，是当前最优解。**

## 十三、模拟验证结论（星月核心纯净之星）

反编译确认：纯净之星用 **Forge GlobalLootModifier (GLM)** 实现，**不是** loot table 的 entry/pool/table 级 condition。

- GLM 类型 `celestial_core:add_item`，conditions：`entity_properties(type=wither)` + 自定义 `celestial_core:player_effect(BENEFICIAL >= 12)`
- 执行点 `LootModifier.apply()` -> `AddItemModifier.doApply()`，独立于 LootTable/LootPool 体系（在 `LootTable.getRandomItems` 内部 `ForgeHooks.modifyLoot` 后处理）
- G+ 的 Mixin1(canRun)/Mixin2(addRandomItems) 命中不了 GLM 的 apply()，**故 G+ 拿不到纯净之星**

### 能力边界
- **G+ 覆盖**：所有走 loot table entry 的掉落（原版全部 + 模组 entry 注入）
- **G+ 不覆盖**：GLM 类掉落（纯净之星这种 Forge GlobalLootModifier 机制）
- 这是**所有 loot table 方案（F/G/G+/E）的共同边界**，非 G+ 独有缺陷--GLM 本就设计为游离在 loot table 之外

### 覆盖 GLM 的代价（不推荐）
要拿 GLM 需 Mixin `LootModifier.apply()` 绕过 conditions，但副作用是所有 GLM 在所有实体触发（杀僵尸也掉纯净之星），破坏平衡。不接受。

## 十四、审查修复记录（3 路子代理）

- **Bug A（严重）** ThreadLocal finally 非 save-restore -> 已改保存-恢复前值（防嵌套 LivingDropsEvent 重入破坏外层标志位）
- **Bug B** expand 漏 AlternativesEntry -> 反编译确认符合"任选一"语义，非 bug，不修
- **Bug C（中等）** copies 性能尖峰 -> 已加 `loot_all_max_items` 上限（默认 100）
- **Bug D** 嵌套 LootTableReference 被绕过 -> "无视条件"设计意图，文档说明
- **Bug E** 文本残留 25->26（AbilityRegistry 注释、zh/en lore_intro、mods.toml）-> 已修
- **误报** value()=-1 UI 显示（惯例，UI 不渲染 value）
- **增强** 加 DIRECT_KILLER_ENTITY 参数（完整性）
