# 破敌之眼 ASM 通用穿透方案（草案）

> 用途：评估"是否用 ASM/CoreMod 给破敌之眼做一层通用穿透兜底"。
> 结论先行：技术上可行，但接入复杂、风险高，性价比不如针对性 Mixin。建议先不做，除非未来要兼容大量未知 Boss。

## 一、为什么需要这层

当前破敌之眼 4 层 Mixin 对**重写 `hurt()` 且不调 `super.hurt()`** 的 Boss（钢铁守护者、暮色巫妖）在整合包战斗模组下击穿不了，根因是 Java 虚分派：

- Boss override 了 `hurt`，调用 `target.hurt()` 直接进 Boss 自己的实现，**不经过 `Entity.hurt` / `LivingEntity.hurt`（super）的字节码**。
- 所以注入 super 的 Mixin（Layer 1/2）永远不触发；Forge 的 `LivingHurtEvent` 等事件也都在 `LivingEntity.hurt` 内部 fire，同样不触发。
- Layer 0 注入 `Player.attack`，属攻击发起侧，被 BetterCombat / Epic Fight 等战斗模组绕开。

**Mixin 的能力边界**：Mixin 只能注入"具体类的具体方法"。它能注入 `LivingEntity.hurt`，但注入不了"所有未知子类各自 override 的那个 `hurt`"——那些子类编译时未知，且虚分派让 super 的注入不触发。

**ASM 能突破这个边界**：CoreMod/`IClassTransformer` 在**类加载时**逐个改字节码，可以给"每一个 override 了 `hurt` 的类"都注入钩子，不论它是原版还是哪个模组的 Boss。这是真正意义上的通用方案。

## 二、方案原理

注册一个类转换器，对每一个被 JVM 加载的类做检查：

1. 解析 `ClassNode`。
2. 若该类声明了 `hurt` 方法（SRG `m_6469_`，描述符 `(Lnet/minecraft/world/damagesource/DamageSource;F)Z`），且该类不是 `LivingEntity` 本身（避免重复改根方法）——就在该 `hurt` 方法的 **HEAD** 插入一段代码：
   ```
   Boolean __ap = PiercingGazeHook.onHurtHead(this, source, amount);
   if (__ap != null) return __ap.booleanValue();
   ```
3. 钩子 `onHurtHead` 内部判断：若不是破敌之眼攻击，返回 `null`（放行原 hurt 逻辑）；若是，直接走自有伤害链（`postHurtEvent` → `invokeActuallyHurt` → `afterPierceFallback`）并返回 `Boolean.TRUE`，**跳过 Boss 自己的全部防御代码**。

这样无论近战、弓箭、还是 BetterCombat 连击，只要有人调了 `boss.hurt()`，钩子必先于 Boss 自己的逻辑执行——战斗模组绕不开。

## 三、接入路径（Forge 1.20.1）

Forge 1.20.1 有两条路，各有坑：

### 路径 A：CoreMod JavaScript（Forge 原生）

- 在 `META-INF/coremods.json` 声明，指向一个 `.js` 文件。
- JS 里 `initializeCoreMod()` 返回 transformer 配置，`transformer(node)` 拿到 `ClassNode` 用 Tree API 改。
- **局限**：`target` 是按类名匹配的（`type: "CLASS"`, `name: "全限定名"`），**不支持"所有子类"通配**。要覆盖未知 Boss，要么穷举类名（退化成针对性方案），要么……见路径 B。
- JS 写 ASM Tree API 很痛苦，调试靠日志。

### 路径 B：Java `IClassTransformer`（真正通用，推荐）

- 实现一个 Java 的类转换器，在 ModLauncher 早期阶段注册，能看到**所有**被加载的类。
- 在 `transform(name, transformedName, bytes)` 里反编译字节码→检查是否有 `m_6469_`→有则注入 HEAD 钩子→返回新字节码。
- 这才能实现"扫描所有 override hurt 的类"，覆盖未知 Boss。
- **接入坑**：Forge 1.20.1 注册 Java transformer 的官方入口是 `ITransformationService` 或 `FMLCorePlugin`，属于 ModLauncher 底层，文档稀少，不同 Forge 小版本有差异。需在 `META-INF/services` 或 `mods.toml` 之外另配启动期入口。**这部分 API 需按实际 Forge 47.4.10 核实，是本方案最大的不确定性。**

## 四、核心代码草案

### 钩子类（普通 Java，无需 ASM）

```java
public final class PiercingGazeHook {
    /**
     * 由 ASM 注入到每个 override hurt 的类的 hurt HEAD。
     * @return null=放行原 hurt 逻辑；TRUE=已自行结算，要求 return true
     */
    public static Boolean onHurtHead(LivingEntity self, DamageSource source, float amount) {
        if (self.level().isClientSide()) return null;              // 客户端不干预
        if (!PiercingGazeUtil.isPiercingGazeAttack(source, self)) return null; // 非破敌之眼放行
        float healthBefore = self.getHealth();
        float effective = PiercingGazeUtil.postHurtEvent(self, source, amount);
        PiercingGazeUtil.invokeActuallyHurt(self, source, effective);
        PiercingGazeUtil.afterPierceFallback(self, effective, healthBefore);
        return Boolean.TRUE;
    }
}
```

### ASM 注入（Tree API 伪代码）

```java
for (MethodNode m : classNode.methods) {
    if (!"m_6469_".equals(m.name) && !"hurt".equals(m.name)) continue;
    if (!"(Lnet/minecraft/world/damagesource/DamageSource;F)Z".equals(m.desc)) continue;
    if (classNode.name.equals("net/minecraft/world/entity/LivingEntity")) continue; // 跳过根类

    InsnList head = new InsnList();
    // ALOAD 0 (this), ALOAD 1 (source), FLOAD 2 (amount)
    head.add(new VarInsnNode(Opcodes.ALOAD, 0));
    head.add(new VarInsnNode(Opcodes.ALOAD, 1));
    head.add(new VarInsnNode(Opcodes.FLOAD, 2));
    // INVOKESTATIC PiercingGazeHook.onHurtHead
    head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
        "com/ayin90723/adventure_power/asm/PiercingGazeHook", "onHurtHead",
        "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)Ljava/lang/Boolean;"));
    // if (result == null) goto original;
    LabelNode skip = new LabelNode();
    head.add(new JumpInsnNode(Opcodes.IFNULL, skip));
    // return result.booleanValue();
    head.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z"));
    head.add(new InsnNode(Opcodes.IRETURN));
    head.add(skip);
    m.instructions.insert(head);
}
```

## 五、代价与风险

| 项 | 说明 |
|----|------|
| **接入复杂** | 路径 B 的 `ITransformationService`/`FMLCorePlugin` 是 ModLauncher 底层，API 不稳定、文档少，是最大成本 |
| **性能** | 每个类加载都过一遍 transformer（可先按类名前缀过滤 `net.minecraft.*` / 已知 modid 前缀，命中再解析字节码） |
| **稳定性** | 钩子一旦有 bug，会**破坏全游戏所有 override hurt 的生物的伤害结算**，影响面极大 |
| **Forge 版本敏感** | SRG 名 `m_6469_`、方法描述符随版本可能变；Forge 升级需重新核对 |
| **调试困难** | ASM 字节码出错通常表现为运行时崩溃或静默失效，排错靠日志和反编译 |
| **与现有层重叠** | 仍需保留 Layer 1/2 处理原版无敌；与 Layer 0/2 的 `actuallyHurt` 路径有重复结算风险，要靠 `IN_PIERCING` 之类防重入 |

## 六、工作量评估

- 钩子类 + `PiercingGazeUtil` 复用：**0.5 天**（已有 `PiercingGazeUtil`，钩子很薄）
- ASM 注入逻辑（Tree API）：**1 天**（含 `hurt` 两个名字 `m_6469_`/`hurt` 兼容、跳过根类、防重入）
- `ITransformationService` 接入 + `META-INF` 配置 + Forge 47.4.10 API 核实：**1–3 天**（不确定性最高，可能踩坑）
- 测试（原版生物、钢铁守护者、巫妖、BetterCombat、各种伤害源回归）：**1–2 天**

**合计约 3.5–6.5 天**，主要风险在接入层。

## 七、方案对比

| 维度 | 当前 Mixin（已优化） | 针对性 Mixin | ASM 通用 |
|------|------|------|------|
| 原版生物 | ✅ | ✅ | ✅ |
| override hurt 调 super 的 Boss | ✅ | ✅ | ✅ |
| override hurt 不调 super 的已知 Boss（战斗模组下） | ❌ | ✅ | ✅ |
| 未来未知 Boss | ❌ | ❌（要逐个加） | ✅ |
| 接入成本 | 已完成 | 低（每 Boss ~30 行 + 条件加载） | 高（3.5–6.5 天 + 接入坑） |
| 风险 | 低 | 低 | 高（影响全游戏伤害） |
| 维护 | 低 | 中（依赖模组类名） | 高（Forge 版本敏感） |

## 八、建议

1. **当前阶段不做 ASM**。优化后的 Mixin + `PiercingGazeUtil` 已覆盖原版和大多数调 super 的 Boss；剩下击穿不了的是少数特定 Boss 在战斗模组下的场景。
2. 若要治这两个 Boss，**针对性 Mixin 性价比远高于 ASM**（每 Boss 约 30 行，`targets` 字符串 + `IMixinConfigPlugin` 条件加载，不污染全局伤害）。
3. 只有当出现"大量未知 Boss 都击穿不了、且无法逐一适配"时，ASM 通用方案才值得投入。届时优先解决路径 B 的 `ITransformationService` 接入不确定性，再做。

---

## 附：若决定做针对性 Mixin（ASM 的轻量替代）

为 `EntityWroughtnaut`、`Lich` 各写一个 Mixin：
- `@Mixin(targets = "全限定类名")` 字符串形式，编译期不依赖模组类。
- `@Inject(method = "m_6469_", at = @At("HEAD"), cancellable = true)`：破敌之眼时调 `PiercingGazeUtil` 结算 + `cir.setReturnValue(true)`。
- `IMixinConfigPlugin.shouldApplyMixin` 按 `mowziesmobs`/`twilightforest` 是否安装决定加载，没装零报错。

注意：暮色森林需以**实际整合包里的 1.20.1 版**类名为准（1.20.6 参考 jar 包结构可能不同）。
