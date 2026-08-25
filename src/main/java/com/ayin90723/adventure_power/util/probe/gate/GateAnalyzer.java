package com.ayin90723.adventure_power.util.probe.gate;

import com.ayin90723.adventure_power.util.DebugLog;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GateOracle 阶段一~三（docs/gate-oracle-proposal.md §3/§4/§5）：
 * 覆写者定位（反射，SRG/dev 双名）→ ASM 一跳消费反推（运行时字节码只读分析）→ 状态五分类。
 * <p>
 * 触发语境：五层引擎 engine-exhausted（数值探针全败）后的语义通道——liveness 判定
 * （isAlive/isDeadOrDying）还在消费真实存活状态（许可标志/进度/隐藏血量）的"假血 Boss"。
 * <p>
 * <b>阶段一</b>：目标类链（实体类 → 模组父类，LivingEntity 前）扫描 6 个方法的模组层覆写
 * （getDeclaredMethods 命中即覆写者）。无覆写 → 目标不适用 GateOracle。
 * <b>阶段二</b>：{@code Class.getResourceAsStream} 拿类原始字节码（mod 类不受 reobf 名影响，
 * 方法名与运行时反射同口径——SRG 环境双方都是 SRG 名），ASM tree 只读分析覆写方法体的
 * 一跳消费：字段读（GETFIELD/GETSTATIC）、同步槽读（GETSTATIC EntityDataAccessor）、
 * this 委托调用（ALOAD_0 + INVOKEVIRTUAL，最多 2 层）、静态击杀调用（KILL_TOOL 发现）。
 * <b>阶段三</b>：消费候选按字节码结构分类——布尔（Z desc / 槽值运行时定型）= GATE_PERMIT、
 * 数值与常量比较（IFxx）= GATE_PROGRESS、float 比零（isDeadOrDying 型）= 派生血。
 * 名字启发只做排序不做定论（全混淆目标结构候选兜底）。
 * <p>
 * <b>字节码下界（§9 P3）</b>：{@code getResourceAsStream} 拿到的是 transformation 之前的
 * 字节——本模组及其他模组的 Mixin/coremod 注入不在分析视野。分析结果 = 模组原始意图的
 * <b>下界</b>：分析不到 ≠ 不存在（真门可能在被注入的委托方法里）——探针验证不过即退回
 * 影杀兜底，零退化；排查"分析出门但验证不翻"时应先怀疑注入层。
 * <p>
 * 缓存：per-class（类字节码运行时不变，分析结论终身有效；只有 {@link GatePlan#resolved}
 * 击杀方案缓存随级联/验证失败作废）。
 */
public final class GateAnalyzer {

    private GateAnalyzer() {
    }

    // ==================== 阶段一：覆写者定位 ====================

    /** 覆写监视表：SRG 名 + dev 名双匹配（生产 getDeclaredMethods 返回 SRG，dev 返回 Mojmap）。 */
    private static final Map<String, MethodKind> WATCHED = Map.ofEntries(
        Map.entry("m_6084_", MethodKind.IS_ALIVE), Map.entry("isAlive", MethodKind.IS_ALIVE),
        Map.entry("m_21224_", MethodKind.IS_DEAD_OR_DYING), Map.entry("isDeadOrDying", MethodKind.IS_DEAD_OR_DYING),
        Map.entry("m_6667_", MethodKind.DIE), Map.entry("die", MethodKind.DIE),
        Map.entry("m_6074_", MethodKind.KILL), Map.entry("kill", MethodKind.KILL),
        Map.entry("m_142687_", MethodKind.REMOVE), Map.entry("remove", MethodKind.REMOVE),
        Map.entry("m_6469_", MethodKind.HURT), Map.entry("hurt", MethodKind.HURT)
    );

    enum MethodKind { IS_ALIVE, IS_DEAD_OR_DYING, DIE, KILL, REMOVE, HURT }

    /**
     * 阶段三分类：消费候选状态。
     * <ul>
     *   <li>{@link #PERMIT_FIELD} 布尔字段（许可标志，泽林 SHOULD_EXALTED_AWAY 字段形态）</li>
     *   <li>{@link #PERMIT_DATA_ITEM} 布尔同步槽（许可标志的 SynchedEntityData 形态——
     *       值类型 Boolean/非数值，运行时定型）</li>
     *   <li>{@link #PROGRESS_FIELD} 数值字段与常量比较（进度阈值）</li>
     *   <li>{@link #PROGRESS_DATA_ITEM} 数值同步槽与常量比较（值类型运行时定型）</li>
     *   <li>{@link #DERIVED_BLOOD_FIELD} 委托 float 字段比 0（isDeadOrDying 型——真血在
     *       数值存储但 getHealth 表演化，转交定向直写 + 语义验证）</li>
     * </ul>
     */
    enum CandidateKind { PERMIT_FIELD, PERMIT_DATA_ITEM, PROGRESS_FIELD, PROGRESS_DATA_ITEM, DERIVED_BLOOD_FIELD }

    /** 消费候选：字段或同步槽 + 分类 + 比较常量（PROGRESS 的最小过阈值写入用）。 */
    static final class StateCandidate {
        final CandidateKind kind;
        /** 字段声明类 JVM 名（a/b/C）；DATA_ITEM 形态为 accessor 静态字段的声明类。 */
        final String owner;
        final String name;
        /** 字段 desc（"Z"/"F"/"I"/"D"；DATA_ITEM 为 EntityDataAccessor desc）。 */
        final String desc;
        final boolean staticField;
        /** PROGRESS：isAlive/isDeadOrDying 与该常量比较（阈值）；探针翻转用。PERMIT 为 null。 */
        final Float compareConst;

        StateCandidate(CandidateKind kind, String owner, String name, String desc,
                       boolean staticField, Float compareConst) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.staticField = staticField;
            this.compareConst = compareConst;
        }

        @Override public String toString() {
            return kind + ":" + owner.substring(owner.lastIndexOf('/') + 1) + "#" + name;
        }
    }

    /** KILL_TOOL 候选：覆写方法体内的静态击杀调用 + 调用点常量实参回放记录。 */
    static final class KillToolCandidate {
        /** 静态方法声明类 JVM 名。 */
        final String owner;
        final String name;
        final String desc;
        /** 调用点紧邻常量装载序列（ICONST/LDC，按栈序）——回放照搬（如 killEntity(target, true) 的 true）。 */
        final List<Object> constArgs;

        KillToolCandidate(String owner, String name, String desc, List<Object> constArgs) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.constArgs = constArgs;
        }

        @Override public String toString() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "#" + name + constArgs;
        }
    }

    /** 覆写者：方法名 + desc + 语义。 */
    record Overrider(String name, String desc, MethodKind kind) {
    }

    /** per-class 分析结论 + 击杀方案缓存（resolved 由 GateOracle 首杀走梯后填充）。 */
    public static final class GatePlan {
        final List<Overrider> overrides;
        final List<StateCandidate> candidates;
        final List<KillToolCandidate> killTools;
        /** 首杀升级梯裁决产物（GateOracle 写入；验证失败时作废置 null 走完整梯）。 */
        volatile Object resolved;

        GatePlan(List<Overrider> overrides, List<StateCandidate> candidates, List<KillToolCandidate> killTools) {
            this.overrides = overrides;
            this.candidates = candidates;
            this.killTools = killTools;
        }

        public boolean isEmpty() {
            return candidates.isEmpty() && killTools.isEmpty();
        }

        /**
         * 死亡拦截判定（{@code util.DeathFinalizer} 补完原版 die 前的门禁）：
         * die / isAlive / isDeadOrDying 存在模组层覆写即视为拦死者--裸调 die() 会触发
         * 对面的中断/复活逻辑（十七轮半开门同款根因），死亡补完只对干净目标执行。
         * <p>
         * hurt / remove / kill 覆写<b>不算</b>：拦伤害不拦死亡（fdbosses 调 super 扣血型）、
         * 死亡表演延迟移除（remove 覆写）都不阻止 die() 完整走完（掉落/事件/dead 标志）。
         */
        public boolean hasDeathInterception() {
            for (Overrider ov : overrides) {
                MethodKind k = ov.kind();
                if (k == MethodKind.DIE || k == MethodKind.IS_ALIVE || k == MethodKind.IS_DEAD_OR_DYING) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<Class<?>, GatePlan> PLANS = new ConcurrentHashMap<>();

    /** 阶段一~三主入口（per-class 缓存；分析异常/无字节码返回空 plan，GateOracle 侧 FAILED 零退化）。 */
    public static GatePlan analyze(LivingEntity target) {
        return PLANS.computeIfAbsent(target.getClass(), k -> scan(target.getClass()));
    }

    private static GatePlan scan(Class<?> cls) {
        List<Overrider> overrides = new ArrayList<>();
        // 阶段一：实体类 → 模组父类链，LivingEntity 前（原版/Forge 层覆写不算模组层意图）
        for (Class<?> c = cls; c != null && c != Object.class && c != net.minecraft.world.entity.LivingEntity.class;
             c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                MethodKind kind = WATCHED.get(m.getName());
                if (kind == null) continue;
                overrides.add(new Overrider(m.getName(), methodDesc(m), kind));
            }
        }
        if (overrides.isEmpty()) {
            DebugLog.probe("[GateOracle] {} 类链无 liveness 覆写（isAlive/isDeadOrDying/die/kill/remove/hurt），不适用",
                cls.getSimpleName());
            return new GatePlan(List.of(), List.of(), List.of());
        }
        // 阶段二+三：对每个覆写方法跑 ASM 一跳消费分析
        List<StateCandidate> candidates = new ArrayList<>();
        List<KillToolCandidate> killTools = new ArrayList<>();
        for (Overrider ov : overrides) {
            analyzeOverrider(cls, ov, candidates, killTools, 0);
        }
        // 名字启发仅排序：alive/permit/flag/gate 词根的许可候选优先（非混淆目标受益，混淆目标退化结构序）
        candidates.sort((a, b) -> Integer.compare(nameScore(b), nameScore(a)));
        DebugLog.probe("[GateOracle] {} 分析：覆写={} 候选={} killTool={}",
            cls.getSimpleName(), overrides.size(), candidates, killTools);
        return new GatePlan(List.copyOf(overrides), List.copyOf(candidates), List.copyOf(killTools));
    }

    /** 名字启发排序分（PERMIT 词根加分；结构分类不变，只影响尝试顺序）。 */
    private static int nameScore(StateCandidate c) {
        String n = c.name.toLowerCase();
        int s = 0;
        if (n.contains("alive") || n.contains("permit") || n.contains("allow") || n.contains("gate")) s += 2;
        if (n.contains("flag") || n.contains("dead") || n.contains("kill") || n.contains("exalt")) s += 1;
        return s;
    }

    private static String methodDesc(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(org.objectweb.asm.Type.getDescriptor(p));
        return sb.append(")").append(org.objectweb.asm.Type.getDescriptor(m.getReturnType())).toString();
    }

    // ==================== 阶段二：ASM 一跳消费反推 ====================

    /** 委托分析深度上限（§4：一跳即止防组合爆炸，委托方法本身再走分析，至多 2 层）。 */
    private static final int DELEGATE_DEPTH_LIMIT = 2;

    /**
     * 单覆写方法的 ASM 一跳分析：定位覆写类字节码 → 找 MethodNode → 扫指令。
     */
    private static void analyzeOverrider(Class<?> targetClass, Overrider ov,
                                         List<StateCandidate> candidates, List<KillToolCandidate> killTools,
                                         int depth) {
        if (depth > DELEGATE_DEPTH_LIMIT) return;
        MethodNode mn = findMethodNode(targetClass, ov.name(), ov.desc());
        if (mn == null) return;
        scanInstructions(targetClass, mn, candidates, killTools, depth);
    }

    /** 读类字节码并定位方法（transform 前原始字节——下界语义见类注释）。 */
    private static MethodNode findMethodNode(Class<?> declaring, String name, String desc) {
        ClassNode cn = readClassNode(declaring);
        if (cn == null) return null;
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    private static ClassNode readClassNode(Class<?> cls) {
        try {
            // hidden class（运行时 defineHiddenClass 产物，类名带 /0x... 后缀——本末起源系同构）
            // 没有同名 class 资源，直接读路径非法——剥离后缀读原始类字节码（hidden 从原始字节
            // define 而来，方法体内容一致；十轮实测 BenMoOriginEntity/0x... 因此全盲：候选=[]
            // killTool=[]，而反编译源码 hurt 覆写第 334 行明调 BMUtil.killEntity）
            String name = cls.getName();
            int slash = name.indexOf('/');
            if (slash > 0) {
                name = name.substring(0, slash);
            }
            try (InputStream is = cls.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                if (is == null) return null;
                ClassNode cn = new ClassNode();
                new ClassReader(is).accept(cn, 0);
                return cn;
            }
        } catch (Exception e) {
            DebugLog.probe("[GateOracle] 字节码读取失败 {}: {}", cls.getName(), e.toString());
            return null;
        }
    }

    /**
     * 指令级消费扫描（一跳语义）：
     * <ul>
     *   <li>GETFIELD/GETSTATIC 字段读 → 分类候选（按 desc：Z=PERMIT、F/I/D=PROGRESS 或派生血）</li>
     *   <li>GETSTATIC EntityDataAccessor → 同步槽候选（值类型运行时定型 PERMIT/PROGRESS）</li>
     *   <li>数值读 + 常量压栈 + IFxx 比较跳转 → PROGRESS 的 compareConst 提取</li>
     *   <li>float 读 + FCONST_0 + FCMP → 派生血（isDeadOrDying 委托 float 比 0）</li>
     *   <li>ALOAD_0 + INVOKEVIRTUAL this 委托 → 委托方法再分析（≤2 层）</li>
     *   <li>INVOKESTATIC + kill 语义名 + 非 MC owner → KILL_TOOL 候选 + 常量实参回放</li>
     * </ul>
     * 实现为"最近压栈槽"线性追踪：lastFieldRead/lastConst 槽随压栈指令更新、随消费指令读取。
     */
    private static void scanInstructions(Class<?> targetClass, MethodNode mn,
                                         List<StateCandidate> candidates, List<KillToolCandidate> killTools,
                                         int depth) {
        // 最近字段读 / 最近常量（供 IFxx 比较与 FCMP 模式匹配）
        FieldInsnNode lastField = null;
        Object lastConst = null;
        // KILL_TOOL 调用点常量收集（ICONST/LDC 栈序）
        List<Object> pendingConsts = new ArrayList<>();
        boolean lastWasAload0 = false;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof FieldInsnNode f) {
                if (f.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD
                    || f.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC) {
                    lastField = f;
                    // 同步槽读：EntityDataAccessor 静态字段（GETSTATIC accessor 基本为 get 消费）
                    if (f.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC
                        && f.desc.equals("Lnet/minecraft/network/syncher/EntityDataAccessor;")) {
                        addCandidate(candidates, new StateCandidate(
                            CandidateKind.PERMIT_DATA_ITEM, f.owner, f.name, f.desc, true, null));
                    } else {
                        classifyFieldRead(candidates, f);
                    }
                }
                lastWasAload0 = false;
            } else if (insn instanceof LdcInsnNode ldc) {
                lastConst = ldc.cst instanceof Number || ldc.cst instanceof Boolean || ldc.cst instanceof String
                    ? ldc.cst : null;
                if (lastConst != null) pendingConsts.add(lastConst);
                lastWasAload0 = false;
            } else if (insn instanceof IntInsnNode node
                && (node.getOpcode() == org.objectweb.asm.Opcodes.BIPUSH
                    || node.getOpcode() == org.objectweb.asm.Opcodes.SIPUSH)) {
                lastConst = node.operand;
                pendingConsts.add(lastConst);
                lastWasAload0 = false;
            } else if (insn instanceof InsnNode n) {
                int op = n.getOpcode();
                if (op >= org.objectweb.asm.Opcodes.ICONST_M1 && op <= org.objectweb.asm.Opcodes.ICONST_5) {
                    lastConst = op - org.objectweb.asm.Opcodes.ICONST_0;
                    pendingConsts.add(lastConst);
                } else if (op == org.objectweb.asm.Opcodes.FCONST_0
                    || op == org.objectweb.asm.Opcodes.FCONST_1
                    || op == org.objectweb.asm.Opcodes.FCONST_2) {
                    lastConst = (float) (op - org.objectweb.asm.Opcodes.FCONST_0);
                    pendingConsts.add(lastConst);
                } else if (op == org.objectweb.asm.Opcodes.FCMPG || op == org.objectweb.asm.Opcodes.FCMPL) {
                    // float 比较：前序 = 字段读 + FCONST_0 → 派生血（isDeadOrDying 型委托 float 比 0）
                    if (lastField != null && Float.valueOf(0.0F).equals(asFloat(lastConst))
                        && lastField.desc.startsWith("F")) {
                        addCandidate(candidates, new StateCandidate(
                            CandidateKind.DERIVED_BLOOD_FIELD, lastField.owner, lastField.name,
                            lastField.desc, lastField.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC, null));
                    }
                }
                lastWasAload0 = false;
            } else if (insn instanceof VarInsnNode v && v.getOpcode() == org.objectweb.asm.Opcodes.ALOAD) {
                lastWasAload0 = v.var == 0;
                if (!lastWasAload0) pendingConsts.clear();
            } else if (insn instanceof JumpInsnNode j) {
                // 数值与常量比较（IFGE/IFGT/IFLT/IFLE 单操作数形式：栈顶常量 vs 已存数值的典型编译产物）
                int op = j.getOpcode();
                boolean isIf = op >= org.objectweb.asm.Opcodes.IFEQ && op <= org.objectweb.asm.Opcodes.IF_ACMPNE;
                if (isIf && lastField != null && lastConst != null && lastField.desc.matches("[FID]")) {
                    Float c = asFloat(lastConst);
                    if (c != null) {
                        addCandidate(candidates, new StateCandidate(
                            CandidateKind.PROGRESS_FIELD, lastField.owner, lastField.name, lastField.desc,
                            lastField.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC, c));
                    }
                }
                lastWasAload0 = false;
            } else if (insn instanceof MethodInsnNode mi) {
                int op = mi.getOpcode();
                if (op == org.objectweb.asm.Opcodes.INVOKESTATIC) {
                    // KILL_TOOL 发现：kill/despawn/death 语义名 + 非 net.minecraft owner（命名仅排序，
                    // 严格裁决在 GateOracle 运行时签名闸 + 双条件验证）
                    String lower = mi.name.toLowerCase();
                    boolean killish = lower.contains("kill") || lower.contains("despawn")
                        || lower.contains("death") || lower.equals("remove") || lower.endsWith("die");
                    if (killish && !mi.owner.startsWith("net/minecraft") && !mi.owner.startsWith("java/")) {
                        killTools.add(new KillToolCandidate(mi.owner, mi.name, mi.desc,
                            new ArrayList<>(pendingConsts)));
                    }
                } else if (op == org.objectweb.asm.Opcodes.INVOKEVIRTUAL && lastWasAload0
                    && !mi.owner.startsWith("net/minecraft")) {
                    // this 委托调用（ALOAD_0 + INVOKEVIRTUAL）：委托方法再走分析（≤2 层）。
                    // 只跟进模组 owner 的无副作用嫌疑方法（get/is 前缀 getter 不进——消费已在当前方法可见）
                    String lower = mi.name.toLowerCase();
                    if (!lower.startsWith("get") && !lower.startsWith("is")) {
                        analyzeDelegate(targetClass, mi, candidates, killTools, depth);
                    }
                }
                pendingConsts.clear();
                lastField = null;
                lastConst = null;
                lastWasAload0 = false;
            }
        }
    }

    /** this 委托方法分析：owner 类解析 + MethodNode 定位（找不到静默跳过——二跳不可达即下界）。 */
    private static void analyzeDelegate(Class<?> targetClass, MethodInsnNode mi,
                                        List<StateCandidate> candidates, List<KillToolCandidate> killTools,
                                        int depth) {
        try {
            Class<?> owner = Class.forName(mi.owner.replace('/', '.'), false, targetClass.getClassLoader());
            MethodNode mn = findMethodNode(owner, mi.name, mi.desc);
            if (mn != null) {
                scanInstructions(owner, mn, candidates, killTools, depth + 1);
            }
        } catch (ClassNotFoundException ignored) {
            // 委托 owner 不可达（隔离 classloader 等）——下界语义，静默
        }
    }

    /** GETFIELD/GETSTATIC 普通（非 accessor）字段读的结构分类：Z=PERMIT、F=派生血候选（无比较常量时）/PROGRESS、I/D=PROGRESS。
     * 审查修 P3#9：staticField=true 的候选不再生成——GateOracle.resolveInstanceField 对静态
     * 候选恒 null（不可解死条目，每次击杀空转+日志噪声）。 */
    private static void classifyFieldRead(List<StateCandidate> candidates, FieldInsnNode f) {
        boolean isStatic = f.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC;
        if (isStatic) return;  // 静态许可字段介质不可解（v1 支持实例字段/槽两形态已覆盖已知形态）
        switch (f.desc) {
            case "Z" -> addCandidate(candidates, new StateCandidate(
                CandidateKind.PERMIT_FIELD, f.owner, f.name, f.desc, isStatic, null));
            case "F" -> addCandidate(candidates, new StateCandidate(
                CandidateKind.DERIVED_BLOOD_FIELD, f.owner, f.name, f.desc, isStatic, null));
            case "I", "D", "J" -> addCandidate(candidates, new StateCandidate(
                CandidateKind.PROGRESS_FIELD, f.owner, f.name, f.desc, isStatic, null));
            default -> { }
        }
    }

    /** 去重添加（同 owner+name+kind 不重复）。 */
    private static void addCandidate(List<StateCandidate> list, StateCandidate c) {
        for (StateCandidate ex : list) {
            if (ex.kind == c.kind && ex.owner.equals(c.owner) && ex.name.equals(c.name)) return;
        }
        list.add(c);
    }

    private static Float asFloat(Object o) {
        if (o instanceof Integer i) return (float) i;
        if (o instanceof Float fl) return fl;
        if (o instanceof Long l) return (float) l.longValue();
        if (o instanceof Double d) return (float) d.doubleValue();
        return null;
    }

    // ==================== 合成血结构分析（淬魂破盾，2026-08-21 实测驱动） ====================

    /** 合成血分量成员：getHealth 覆写直接读取的 float 存储。 */
    public static final class CompMember {
        /** 声明类 JVM 名。 */
        public final String owner;
        /** 字段名（slot=false）或 accessor 静态字段名（slot=true）。 */
        public final String name;
        /** true = GETSTATIC EntityDataAccessor（DataItem 槽形态）；false = GETFIELD float 实例字段。 */
        public final boolean slot;

        CompMember(String owner, String name, boolean slot) {
            this.owner = owner;
            this.name = name;
            this.slot = slot;
        }

        @Override public boolean equals(Object o) {
            return o instanceof CompMember m && m.owner.equals(owner) && m.name.equals(name);
        }

        @Override public int hashCode() {
            return owner.hashCode() * 31 + name.hashCode();
        }

        @Override public String toString() {
            return (slot ? "槽:" : "字段:") + owner.substring(owner.lastIndexOf('/') + 1) + "#" + name;
        }
    }

    /**
     * 合成血结构信息：分量集合 + 真血分量。结构识别（零名字依赖，混淆免疫）：
     * <ul>
     *   <li><b>分量集合</b> = getHealth 覆写直接读取的 float 字段/槽（合成读数读了谁是分量）；
     *       宽松收集（不严格配对 FADD——复杂表达式里的回血/衰减因子也会入集，
     *       由清零侧的行为验证[读数下降]兜底剔除）</li>
     *   <li><b>真血分量</b> = die / isDeadOrDying 覆写一跳消费字段与分量集合的交集
     *       （死亡判定读谁谁就是真血——Boss 死不死看真血）；交集恰为 1 才定论</li>
     * </ul>
     * 次分量 = 集合 − 真血（护盾/护甲），由淬魂破盾清零。识别失败（无 getHealth 覆写 /
     * 分量 &lt; 2 / 真血无法定论）返回 null，调用方回落名字词根兜底。
     */
    public static final class CompositeInfo {
        final java.util.List<CompMember> components;
        /** die/isDeadOrDying 消费交集（或名字主次）定论的真血分量；null = 未定论。 */
        public final CompMember primary;
        /** 预计算次分量集合（护盾类）。liveness 定论=集合−真血（实锤可信）；名字定论=盾词根成员
         *  （真血组的其余成员[如真血槽]不进次集合——磨血语境"清盾"不得清真血）。 */
        private final java.util.List<CompMember> secondaries;

        CompositeInfo(java.util.List<CompMember> components, CompMember primary,
                      java.util.List<CompMember> secondaries) {
            this.components = components;
            this.primary = primary;
            this.secondaries = secondaries;
        }

        /** 次分量集合（护盾类）；真血未定论时为空（调用方回落名字级扫描）。 */
        public java.util.List<CompMember> secondaries() {
            return secondaries;
        }
    }

    private static final Map<Class<?>, CompositeInfo> COMPOSITE = new ConcurrentHashMap<>();

    /** 结构分析失败哨兵（computeIfAbsent 不缓存 null——失败结论也要 per-class 终身缓存，防每刀重扫 ASM）。 */
    private static final Set<Class<?>> COMPOSITE_SCANNED = ConcurrentHashMap.newKeySet();

    /**
     * 合成血结构分析入口（per-class 缓存，失败结论同样缓存；识别失败返回 null，
     * 调用方回落名字词根兜底）。
     */
    public static CompositeInfo analyzeComposite(LivingEntity target) {
        Class<?> cls = target.getClass();
        CompositeInfo info = COMPOSITE.get(cls);
        if (info != null) return info;
        if (COMPOSITE_SCANNED.contains(cls)) return null;
        info = scanComposite(cls);
        COMPOSITE_SCANNED.add(cls);
        if (info != null) COMPOSITE.put(cls, info);
        return info;
    }

    private static CompositeInfo scanComposite(Class<?> cls) {
        try {
            // ① getHealth 覆写（m_21223_/getHealth ()F）——类链 LivingEntity 前
            java.lang.reflect.Method getter = findOverriderMethod(cls, "m_21223_", "getHealth", "()F");
            if (getter == null) return null;
            MethodNode mn = findMethodNode(getter.getDeclaringClass(), getter.getName(), methodDesc(getter));
            if (mn == null) return null;
            // 分量集合：覆写体内 GETFIELD float（desc F）+ GETSTATIC EntityDataAccessor
            java.util.List<CompMember> components = new java.util.ArrayList<>();
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof FieldInsnNode f) {
                    if (f.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD && "F".equals(f.desc)) {
                        addComp(components, new CompMember(f.owner, f.name, false));
                    } else if (f.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC
                        && f.desc.equals("Lnet/minecraft/network/syncher/EntityDataAccessor;")) {
                        addComp(components, new CompMember(f.owner, f.name, true));
                    }
                }
            }
            if (components.size() < 2) return null;
            // ② 真血分量：die / isDeadOrDying 覆写一跳消费字段与分量集合的交集（恰 1 定论）
            java.util.Set<CompMember> livenessReads = new java.util.HashSet<>();
            collectLivenessReads(cls, "m_6667_", "die",
                "(Lnet/minecraft/world/damagesource/DamageSource;)V", livenessReads);
            collectLivenessReads(cls, "m_21224_", "isDeadOrDying", "()Z", livenessReads);
            livenessReads.retainAll(components);
            CompMember primary = livenessReads.size() == 1 ? livenessReads.iterator().next() : null;
            String how = primary != null ? "liveness交集" : null;
            // 名字级主次 fallback（2026-08-21 三轮实测：太阳神使 die/isDeadOrDying 与两分量交集非唯一，
            // liveness 判定失灵）——结构集合内用词根分辨主次：结构已保证候选确实是分量
            // （getHealth 覆写亲读，不会误中无关字段），名字只判谁是盾谁是血，低风险
            if (primary == null) {
                primary = resolvePrimaryByName(components);
                how = primary != null ? "名字主次" : null;
            }
            if (primary == null) {
                DebugLog.probe("[合成血分析] {} 分量={} 真血未定论（liveness 交集 {} 个，名字启发未分辨——注入不发生，清盾仍走名字级）",
                    cls.getSimpleName(), components, livenessReads.size());
                return new CompositeInfo(java.util.List.copyOf(components), null, java.util.List.of());
            }
            // 次集合：liveness 定论=集合−真血；名字定论=盾词根成员（真血组其余成员不清——磨血语境保护）
            java.util.List<CompMember> secs = new java.util.ArrayList<>("liveness交集".equals(how)
                ? components
                : components.stream()
                    .filter(c -> com.ayin90723.adventure_power.util.probe.MultiStoreWriter.isShieldName(c.name))
                    .toList());
            secs.remove(primary);
            DebugLog.probe("[合成血分析] {} 分量={} 真血={}（{}）次分量={}",
                cls.getSimpleName(), components, primary, how, secs);
            return new CompositeInfo(java.util.List.copyOf(components), primary, java.util.List.copyOf(secs));
        } catch (Exception e) {
            DebugLog.probe("[合成血分析] {} 结构分析异常: {}", cls.getSimpleName(), e.toString());
            return null;
        }
    }

    /** 健康词根（真血侧判定，与盾词根互补；仅作主次分辨用——集合成员资格由结构保证）。 */
    private static final String[] BLOOD_WORDS = {"health", "true", "hp", "body", "blood", "life"};

    /**
     * 名字级主次判定（liveness 交集失灵的 fallback）：分量集合内分辨真血。
     * ① 恰 2 分量且盾词根恰 1 → 另一个即真血；② 健康词根（非盾）恰 1 → 它是真血；
     * 都不唯一返回 null（保守——注入不发生，清盾侧名字级仍可工作）。
     */
    private static CompMember resolvePrimaryByName(java.util.List<CompMember> components) {
        java.util.List<CompMember> nonShields = components.stream()
            .filter(c -> !com.ayin90723.adventure_power.util.probe.MultiStoreWriter.isShieldName(c.name)).toList();
        if (nonShields.isEmpty()) return null;
        // 非盾组恰 1 → 它是真血（两分量经典形态）
        if (nonShields.size() == 1) return nonShields.get(0);
        // 非盾组多个（槽+字段双轨形态，如 [槽:DATA_CUSTOM_HEALTH, 字段:trueHealth]）：
        // 健康词根命中唯一 → 它；否则组内唯一字段形态成员 → 注入用（槽形态由槽插针天然覆盖）
        java.util.List<CompMember> bloods = nonShields.stream()
            .filter(c -> {
                String n = c.name.toLowerCase();
                for (String w : BLOOD_WORDS) {
                    if (n.contains(w)) return true;
                }
                return false;
            }).toList();
        if (bloods.size() == 1) return bloods.get(0);
        java.util.List<CompMember> fields = nonShields.stream().filter(c -> !c.slot).toList();
        return fields.size() == 1 ? fields.get(0) : null;
    }

    /** 在类链（LivingEntity 前）定位覆写方法的反射 Method（SRG/dev 双名 + desc 匹配；找不到返回 null）。 */
    private static java.lang.reflect.Method findOverriderMethod(Class<?> cls, String srgName,
                                                                String devName, String desc) {
        for (Class<?> c = cls; c != null && c != Object.class
            && c != net.minecraft.world.entity.LivingEntity.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if ((m.getName().equals(srgName) || m.getName().equals(devName))
                    && methodDesc(m).equals(desc)) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 定位 liveness 覆写并把方法体内的 float 字段/槽读收集进 out（一跳内的直接读，v1 不追委托）。 */
    private static void collectLivenessReads(Class<?> cls, String srgName, String devName,
                                             String desc, java.util.Set<CompMember> out) {
        java.lang.reflect.Method m = findOverriderMethod(cls, srgName, devName, desc);
        if (m == null) return;
        MethodNode mn = findMethodNode(m.getDeclaringClass(), m.getName(), methodDesc(m));
        if (mn == null) return;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof FieldInsnNode f) {
                if (f.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD && "F".equals(f.desc)) {
                    out.add(new CompMember(f.owner, f.name, false));
                } else if (f.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC
                    && f.desc.equals("Lnet/minecraft/network/syncher/EntityDataAccessor;")) {
                    out.add(new CompMember(f.owner, f.name, true));
                }
            }
        }
    }

    private static void addComp(java.util.List<CompMember> list, CompMember c) {
        if (!list.contains(c)) list.add(c);
    }

    /** 级联失效清空（GateOracle 调用；分析结论随类终身有效，仅击杀方案作废）。 */
    public static void invalidate() {
        PLANS.values().forEach(p -> p.resolved = null);
    }

    /** 测试/调试：清空全部 per-class 分析缓存。 */
    static void clearAll() {
        PLANS.clear();
    }
}
