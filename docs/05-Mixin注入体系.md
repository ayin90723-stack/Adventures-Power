# Mixin 注入体系

## 总览

共 9 个 Mixin（从旧模组的 11 个中移除了 2 个 Curios 反扒 Mixin — 灵魂绑定不再保护装备卸除）。

## Mixin 列表

### 1. SeeAndSlashMixin

| 属性 | 值 |
|------|-----|
| 目标 | `Entity.isInvulnerableTo(DamageSource)` |
| 注入方式 | `@Inject` HEAD，cancellable |
| 作用 | **Layer 1**：持有"见既斩"的玩家攻击时，目标 isInvulnerableTo 直接返回 false |
| 优先级 | -5000（早于其他 mod） |

### 2. SeeAndSlashLivingEntityMixin

| 属性 | 值 |
|------|-----|
| 目标 | `LivingEntity.hurt(DamageSource, float)` |
| 注入方式 | `@Redirect` × 1 + `@Inject` × 2 + `@Invoker` × 1 |
| 作用 | **Layer 2 + 2.5 + 3**： |

**注入点详解：**

```
① @Redirect ForgeHooks.onLivingHurt()
  → Layer 2: 绕过 Forge 事件取消伤害

② @Inject HEAD (damage clamping)
  → Layer 2.5: 见既斩持有者 limit damage >= 1.0F（单向不可降）

③ @Inject RETURN + @Invoker actuallyHurt
  → Layer 3: hurt() 返回 false 时兜底穿透
     - 清除 needDamageIndicator
     - 用 InvulClearUtil 清除目标自定义无敌
     - 直接调用 actuallyHurt（Mojang → SRG: actuallyHurt → m_6475_）
     - 若 actuallyHurt 后仍活着 → 直接调 die()
```

### 3. SeeAndSlashPlayerAttackMixin

| 属性 | 值 |
|------|-----|
| 目标 | `Player.attack(Entity)` |
| 注入方式 | `@Redirect` target.hurt() 调用 |
| 作用 | **Layer 0**： |

Boss 可能重写 `hurt()` 完全绕过 `super.hurt()`，导致 Layer 2/2.5/3 所有注入点都失效。此 Mixin 在 `Player.attack()` 的最外层 `target.hurt()` 调用前，先用 `InvulClearUtil` 清除目标自定义无敌字段，确保伤害能到达底层。

### 4. UndyingSlashMixin

| 属性 | 值 |
|------|-----|
| 目标 | `LivingEntity` |
| 注入方式 | `@Inject` setHealth HEAD + tick TAIL |
| 作用 | 不死斩禁疗钳制 |

- setHealth HEAD：非 hurt 路径的回血 → 钳制
- tick TAIL：每 tick 反射写入强制钳制（反制 DataItem 篡改）

### 5. TrueHealthMixin

| 属性 | 值 |
|------|-----|
| 目标 | `LivingEntity` |
| 注入方式 | `@Inject` × 4 |
| 作用 | 真实血量备份与自动修复 |

- getHealth HEAD → 备份血量 > 0 且与 DataItem 不一致 → 修复
- setHealth RETURN → 同步备份血量
- isDeadOrDying HEAD → 备份 > 0 强制返回 false
- die HEAD → 备份 > 0 取消死亡

### 6. RejectHealthManipMixin

| 属性 | 值 |
|------|-----|
| 目标 | `LivingEntity` |
| 注入方式 | `@Inject` × 4 |
| 作用 | HURT_DEPTH 标记系统 |

### 7. RejectHealthManipAttributeMixin

| 属性 | 值 |
|------|-----|
| 目标 | `AttributeInstance` |
| 注入方式 | `@Inject` setBaseValue HEAD，cancellable |
| 作用 | maxHealth 低于 6 时取消修改（遍历所有者反查） |

### 8. DeathDefyMixin

| 属性 | 值 |
|------|-----|
| 目标 | `LivingEntity` |
| 注入方式 | `@Inject` setHealth HEAD + tick TAIL |
| 作用 | 死亡抗拒无敌期双层拦截 |

### 9. LegacyHurtAndBreakMixin

| 属性 | 值 |
|------|-----|
| 目标 | `ItemStack.hurtAndBreak()` |
| 注入方式 | `@Inject` HEAD，cancellable |
| 作用 | 永恒/传承附魔：禁止装备耐久消耗 |

---

## Mixin 写作规范

### 命名规则

- `@At` 的 method/target 使用 **SRG 名**（如 `m_6469_` 而非 `hurt`）
- `@Mixin` 的类名使用 **Mojang 名**（如 `LivingEntity.class`）
- `@Invoker` 的 method 名使用 **Mojang 名**（如 `"actuallyHurt"`），通过 refmap 映射

### 注入策略

- **优先 `@Inject`**：不依赖 refmap，更可靠
- `@Redirect` 需要 refmap，但目标为 Forge 自有静态方法时无需 refmap 条目
- 优先注入**目标方法本身**，而非注入调用方内部的 INVOKE 指令

### 限制

- `@Mixin` 类中禁止非 private 的 static 方法
- Curios/模组类方法使用 `remap = false`

### refmap

`mixins.mme.refmap.json` 需手动维护（ForgeGradle 6.x + MCPConfig 无法自动生成）。

当前唯一映射：`actuallyHurt` → `m_6475_`（Mojang → SRG）。

---

## 关键文件

```
src/main/resources/mixins.mme.json         # Mixin 配置文件（11 个入口）
src/main/resources/mixins.mme.refmap.json   # 手动维护的 refmap

src/main/java/.../mixin/
├── SeeAndSlashMixin.java
├── SeeAndSlashLivingEntityMixin.java
├── SeeAndSlashPlayerAttackMixin.java
├── UndyingSlashMixin.java
├── TrueHealthMixin.java
├── RejectHealthManipMixin.java
├── RejectHealthManipAttributeMixin.java
├── DeathDefyMixin.java
└── LegacyHurtAndBreakMixin.java
```
