package com.ayin90723.adventure_power.util.probe;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 淬魂强化·五层通用改血引擎（设计文档 {@code docs/quench-upgrade-proposal.md} v5.1）。
 * <p>
 * L0 正门伤害链（hurt，由 {@code CombatAbilityHandler.handleSoulQuench} 完成并判定拦截）→
 * 失败后本引擎接手：<b>L1</b> 通用 setter（{@link HealthUtil#setHealthLikeGeneric}）→
 * <b>L2</b> 对象图插针（{@link HealthUtil#tryLayeredWrite}，值闸双向形态分类）→
 * <b>L3</b> 类静态容器探针（本类 {@link #staticMapStrike}）→
 * <b>L4</b> 广义写路径（{@link L4MethodProbe}，行为学找写方法，覆盖加密存血/
 * 双字段校验/不变量维护型；L4-B 已知明文恢复未实施，等真实对手）→
 * 封存前放宽补探 → engine-exhausted 静默封存（raw 显示层兜底保留）。
 * <p>
 * 六条设计不变量（文档 §3）的落点：
 * ①值安全闸→probeGraph 形态分类；②联动后确认→各层验证闭环；
 * ③探测方向永远向下→L1/L2 降向写入、L3 put 探针为降向；
 * ④主线程串行→全部探测写+验证读同调用栈；⑤合法密文→L4 未实施暂不涉及；
 * ⑥方法调用副作用闸→L1 仅扫名字含 health 的 (F)V。
 * <p>
 * 缓存体系（文档 §6）：正负双缓存 per-class；三种失效触发源——
 * 正缓存漂移（各层写前验证失败）、读数条件化（读数漂移超阈值）、
 * 级联失效（任意正缓存漂移 → 全部负缓存失效一次）；
 * 封存前补探 per-class 一次性 tombstone。
 * <p>
 * 兜底姿态（文档 §8）：per-kill 非 per-hit——全层失败静默封存，
 * 影杀是独立并行能力非本引擎的调用下游。
 */
public final class BloodWriteEngine {

    private BloodWriteEngine() {
    }

    // ==================== 级联失效 ====================

    /** 全局级联纪元：任意正缓存漂移时递增，各 ClassProbeState 发现纪元落后即清空自身负缓存（一次性）。 */
    private static final AtomicInteger CASCADE_EPOCH = new AtomicInteger();

    /** 已执行全局负缓存清理的纪元（跨类负缓存 GENERIC_NO_HIT 每纪元清一次）。 */
    private static volatile int lastClearedEpoch = 0;

    /** 正缓存漂移信号入口：HealthUtil 各写路径缓存失效（漂移/异常作废）时调用。 */
    public static void onPositiveCacheDrift() {
        CASCADE_EPOCH.incrementAndGet();
    }

    /**
     * v1.4.3 新情报解除封存：结构定位通路注入（淬魂破盾真血定向直写）时调用——
     * exhausted 封存的前提「全层放弃」被新情报推翻，解除后重走梯（缓存快路径先命中）。
     * GRAPH_OVERWHELMED 全图封存<b>保留</b>：注入通路走缓存快路径不进全图扫描，不受影响。
     */
    public static void onNewChannelIntel(Class<?> cls) {
        ClassProbeState st = STATES.computeIfAbsent(cls, k -> new ClassProbeState());
        st.exhausted = false;
        st.graphNoHit = false;
        st.l3NoMap = false;
    }

    // ==================== per-class 探测状态 ====================

    /** 类级探测状态：负缓存三标记 + 读数快照 + 封存 tombstone + 级联纪元。 */
    private static final Map<Class<?>, ClassProbeState> STATES = new ConcurrentHashMap<>();

    private static final class ClassProbeState {
        /** L2 对象图插针未命中（读数条件化，存探测时刻读数）。 */
        boolean graphNoHit;
        float cachedReading = Float.NaN;
        /** L3 类静态容器未命中（读数条件化，与 graphNoHit 同步失效）。 */
        boolean l3NoMap;
        /** 全层放弃封存标记（读数条件化可解除；relaxedProbeDone 不可）。 */
        boolean exhausted;
        /** 封存前放宽补探 tombstone：补探过即补探过，不随读数失效重燃（文档 §6）。 */
        boolean relaxedProbeDone;
        /** 已观察到的级联纪元：落后即清负缓存并追平。 */
        int seenEpoch = -1;
        /** 首次走梯日志已发（per-class 一次，缓存天然防刷屏）。 */
        boolean walkLogged;
    }

    // ==================== 主入口 ====================

    /**
     * 淬魂兜底直写入口：hurt() 被拦截后由 {@code handleSoulQuench} 调用，
     * 走 L1→L2→L3→封存补探→exhausted 的完整梯子。
     * <p>
     * v1.4.4：调用方参数（{@code caller}）——引擎探针日志按调用方能力开关归属
     * （淬魂→淬魂开关 / 破敌→破敌开关 / 影杀→影杀开关 / 禁疗→禁疗开关 / 审判→淬魂开关），
     * 调用点显式传递（编译期零纪律），引擎入口设置 ThreadLocal 上下文，重入保持外层。
     *
     * @param target        目标（非玩家；PVP 禁用在调用方）
     * @param targetHealth  期望写入的血量值（读数−伤害，降向；处决语义传 0）
     * @param caller        调用方（日志归属）
     * @return true 表示某层通道命中或 raw 显示层兜底已执行；false 表示 engine-exhausted 静默
     */
    public static boolean execute(LivingEntity target, float targetHealth, DebugLog.EngineCaller caller) {
        // 重入守卫（子代理审查修）：禁疗 HEAD → L4 探针 m(eps) 在 tracked<eps 时再次触发 HEAD
        // → 嵌套 execute → 同方法再探 → 无限递归（SOE）。重入时跳过走梯直接 raw（v1.4.1 行为）；
        // 不设置调用方上下文——保持外层调用方（嵌套日志归属外层）
        if (REENTRANT.get()) {
            HealthUtil.setAllHealthLikeRaw(target, targetHealth);
            return true;
        }
        if (!ModConfig.QUENCH_ENGINE_ENABLED.get()) {
            // 开关关闭：退回 v1.4.1 三级直写链（含本次升级的量纲/双向形态内核，行为兼容）
            HealthUtil.setHealthLikeAny(target, targetHealth);
            return true;
        }
        REENTRANT.set(true);
        DebugLog.EngineCaller prevCaller = DebugLog.setEngineCaller(caller);
        try {
            // 二十轮：磨血语义统一清盾前置（自引擎入口下沉——原散布在淬魂/破敌/禁疗钳制×2/审判
            // 五个调用点，每新增调用点都要记得手动加，破敌漏加实测血量乱跳）。写入正确性是引擎
            // 的责任；淬魂入口另有一份清盾服务于伤害计算基准（灵魂打击语义），保留互不冲突
            // （clearShieldComponents 幂等 + per-class 缓存，重复调用零成本）。处决语义
            // （targetHealth==0）跳过——多存储处决公式数学自洽天然双清零。
            // 目标值换算：调用方目标值基于其时点读数 R0 计算（磨血量 D=R0−target），引擎清盾后
            // 读数降至 R1（R0−R1=盾扣除量），等价目标 = target−(R0−R1)——数学上与"调用点清盾后
            // 以 R1 为基计算"完全一致（R1−D），真血下降量精确等于调用方期望的磨血量 D
            if (targetHealth > 0.0F && !(target instanceof net.minecraft.world.entity.player.Player)) {
                float readingBefore = HealthUtil.getEffectiveHealth(target);
                MultiStoreWriter.clearShieldComponents(target);
                float shieldDelta = readingBefore - HealthUtil.getEffectiveHealth(target);
                if (shieldDelta > 0.0F) {
                    targetHealth = Math.max(0.0F, targetHealth - shieldDelta);
                }
            }
            // v1.4.8 实机修复（失序之影·末日降临实测）：归零反弹型自适应——磨血语义调用方
            // （淬魂/破敌/审判）写 0 后被对方的归零批准制反弹（reboundUnsaneZero/tick 对账
            // 回滚），写 0 死循环白刀。自适应：写 0 的下一刀若读数 >0 即标记该类反弹型，
            // 此后磨血写值钳到最小存活值（1.0）——血趋近但不归零，最后一击留给正规 hurt
            // 管线完成批准跨零（对方自己的 die 演出+掉落完整）。普通怪写 0 即死无第二刀，
            // 永不进反弹集，零回归。处决语义（影杀/禁疗 FORCE_KILL）不参与——写 0 是其
            // 本意，反弹由各自 pending 裁决链处理。
            if (isGrindingCaller(caller)) {
                Class<?> cls0 = target.getClass();
                long nowTick = target.level().getGameTime();
                // 超窗清理：写 0 后百 tick 无下一刀（目标已死/战斗结束）→ 清 stale 标记
                PENDING_ZERO_TICK.entrySet().removeIf(e -> nowTick - e.getValue() > 100L);
                Long pending = PENDING_ZERO_TICK.remove(target);
                if (targetHealth <= 0.0F) {
                    // 写 0 刀：检测上刀写 0 后本实体是否仍存活（读数 >0 = 被弹回，无论弹回值大小）
                    if (pending != null && HealthUtil.getEffectiveHealth(target) > 0.0F) {
                        ZERO_REBOUND.add(cls0);
                        DebugLog.probe("[走梯] {} 归零被反弹（批准制/对账回滚型），磨血改写最小存活值",
                            cls0.getSimpleName());
                    }
                    if (ZERO_REBOUND.contains(cls0)) {
                        // 钳值取 min 防升写（读数已 <1 时保持现值，不变量③ 探测方向恒向下）
                        targetHealth = Math.min(1.0F, HealthUtil.getEffectiveHealth(target));
                    } else {
                        PENDING_ZERO_TICK.put(target, nowTick);
                    }
                } else if (pending != null) {
                    // 上刀写 0 本刀读数存活（targetHealth>0 由调用方按读数算出）——弹回任意值
                    // （含大值弹回风格：写 0 → 弹回 R>extra → 继续磨，同样属归零反弹）——标记
                    ZERO_REBOUND.add(cls0);
                    DebugLog.probe("[走梯] {} 归零被反弹（弹回存活值，磨血改写最小存活值）", cls0.getSimpleName());
                }
            }
            return executeInner(target, targetHealth);
        } finally {
            DebugLog.restoreEngineCaller(prevCaller);
            REENTRANT.set(false);
        }
    }

    /** 重入守卫：execute 嵌套调用（禁疗 HEAD→L4 探针链）时跳过走梯直接 raw。 */
    private static final ThreadLocal<Boolean> REENTRANT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** v1.4.8 归零反弹型：写 0 后被弹回（批准制/对账回滚）的类，磨血写值钳最小存活值。
     *  per-class 永久（反弹是类行为，一次观察可推广；对方停反弹后的退化方向=最后一击走
     *  正规管线批准跨零，安全）。 */
    private static final Set<Class<?>> ZERO_REBOUND = ConcurrentHashMap.newKeySet();
    /** 磨血写 0 存在标记（复查修 P2：按<b>实体</b>弱键控——按类键控时同类实体 A 被正常
     *  击杀残留的标记会让实体 B 误判反弹、永久误标该类导致打不死；弱 key 目标死亡自动
     *  回收，超窗清理同步全清）。值为写 0 时刻 gameTime。 */
    private static final Map<LivingEntity, Long> PENDING_ZERO_TICK =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 磨血语义调用方（写 0 需自适应反弹）；处决语义（影杀/禁疗 FORCE_KILL）不参与。 */
    private static boolean isGrindingCaller(DebugLog.EngineCaller caller) {
        return caller == DebugLog.EngineCaller.SOUL_QUENCH
            || caller == DebugLog.EngineCaller.PIERCING_GAZE
            || caller == DebugLog.EngineCaller.JUDGMENT;
    }

    private static boolean executeInner(LivingEntity target, float targetHealth) {
        ClassProbeState st = STATES.computeIfAbsent(target.getClass(), k -> new ClassProbeState());

        // ① 级联失效：正缓存漂移（对方形态真实变化）→ 负缓存全部失效一次
        //    全局跨类负缓存（通用层 no-hit）每纪元清一次；类内负缓存各自追平纪元
        int epoch = CASCADE_EPOCH.get();
        if (epoch != lastClearedEpoch) {
            lastClearedEpoch = epoch;
            HealthUtil.invalidateGenericNegativeCache();
            L4MethodProbe.invalidateNegativeCache();
            MultiStoreWriter.invalidateNegativeCache();
            com.ayin90723.adventure_power.util.probe.gate.GateOracle.invalidate();
        }
        if (st.seenEpoch != epoch) {
            st.graphNoHit = false;
            st.l3NoMap = false;
            st.exhausted = false;
            st.seenEpoch = epoch;
        }

        // ② 读数条件化失效：读数较 L2 负缓存记录时刻漂移超阈值 → 允许 L2/L3 重探一次。
        //    注意：不解除 exhausted（全层放弃的强封存）——限伤 Boss 每刀读数持续漂移，
        //    若随漂移清封存，每刀重走整梯（泽林实测：门禁+L2 超预算+L3+L4+补探+raw 全流程
        //    每刀 0.1~0.5 秒，且磨血读数被反复重置）。exhausted 只随级联（对方形态真实变化）失效。
        float reading = HealthUtil.getEffectiveHealth(target);
        if (st.graphNoHit
            && Math.abs(reading - st.cachedReading) >= ProbeScales.reprobeThreshold(target.getMaxHealth())) {
            st.graphNoHit = false;
            st.l3NoMap = false;
        }

        // ③ engine-exhausted 静默封存：不再走梯，仅保留 raw 显示层兜底（血条反馈，开销小）
        if (st.exhausted) {
            HealthUtil.setAllHealthLikeRaw(target, targetHealth);
            return false;
        }

        // ④ L1 + L2：现有分级链（通用 setter → 对象图插针；DataItem 门禁通过时 raw 即主通道）
        HealthUtil.LayerOutcome outcome = HealthUtil.tryLayeredWrite(target, targetHealth);
        if (outcome == HealthUtil.LayerOutcome.GENERIC_HIT) {
            st.graphNoHit = false;
            st.l3NoMap = false;
            logWalk(target, st, "L1 通用 setter");
            return true;
        }
        if (outcome == HealthUtil.LayerOutcome.PROBE_HIT) {
            st.graphNoHit = false;
            st.l3NoMap = false;
            logWalk(target, st, "L2 对象图插针");
            return true;
        }
        if (outcome == HealthUtil.LayerOutcome.DATA_GATE_PASS) {
            // 轻微项修复（子代理审查）：门禁直通道也是"通道有效"证据，清负缓存（与 GENERIC/PROBE 分支一致）
            st.graphNoHit = false;
            st.l3NoMap = false;
            logWalk(target, st, "DataItem 直通道（门禁通过）");
            return true;
        }

        // ⑤ L1+L2 双负 → 记 L2 负缓存（含读数快照）→ L3 类静态容器
        st.graphNoHit = true;
        st.cachedReading = reading;
        if (ModConfig.QUENCH_LAYER3_ENABLED.get()) {
            if (!st.l3NoMap) {
                if (staticMapStrike(target, targetHealth)) {
                    st.l3NoMap = false;
                    logWalk(target, st, "L3 类静态容器");
                    return true;
                }
                st.l3NoMap = true;
            }
        }

        // ⑤' L4 广义写路径：按行为找"调了它 getHealth 就动"的写方法
        // （覆盖加密存血/双字段校验/不变量维护型；方法探针协议见 L4MethodProbe）
        if (ModConfig.QUENCH_LAYER4_ENABLED.get() && L4MethodProbe.strike(target, targetHealth)) {
            st.graphNoHit = false;
            st.l3NoMap = false;
            logWalk(target, st, "L4 广义写路径");
            return true;
        }

        // ⑥ 封存前放宽补探（一次性 tombstone）：放宽反向地板全图补探反向形态候选
        if (!st.relaxedProbeDone) {
            st.relaxedProbeDone = true;
            if (HealthUtil.probeGraphFull(target, targetHealth, true)) {
                st.graphNoHit = false;
                st.l3NoMap = false;
                logWalk(target, st, "封存前放宽补探（反向形态）");
                return true;
            }
        }

        // ⑦ 全层放弃：raw 显示层兜底（保持 v1.4.1 行为）+ 静默封存
        HealthUtil.setAllHealthLikeRaw(target, targetHealth);
        st.exhausted = true;
        if (!st.walkLogged) {
            st.walkLogged = true;
            DebugLog.probe("[走梯] {} 全层放弃 → engine-exhausted（影杀兜底通道独立运行）",
                target.getClass().getSimpleName());
        }
        return false;
    }

    /** per-class 首次走梯结果日志（停在 L 几 / 哪条路径 / 全层放弃）。 */
    private static void logWalk(LivingEntity target, ClassProbeState st, String layerName) {
        if (!st.walkLogged) {
            st.walkLogged = true;
            DebugLog.probe("[走梯] {} 首次走梯：命中 {}（target={}）", target.getClass().getSimpleName(), layerName, target);
        }
    }

    // ==================== L3：类静态容器探针 ====================

    /** L3 正缓存：实体类 → 命中的静态 Map 字段句柄 + key 形态（句柄缓存，field.get(null) 每次解析当前实例）。 */
    private static final Map<Class<?>, StaticMapPath> L3_CACHE = new ConcurrentHashMap<>();

    /**
     * 静态 Map 写入通路：static Map 字段 + key 形态（按实体解析具体 key）。
     * v1.4.8 E3 条目对象模式：值不是数值而是自定义对象（static WeakHashMap&lt;实体,
     * HealthSlots&gt; 藏血型）时，{@code entryField} 指向条目对象内的真血字段，
     * 写入走 {@code map.get(key).field}（值语义或位打包），不再向 map put 数值。
     */
    private static final class StaticMapPath {
        final Field field;
        final KeyKind kind;
        /** 条目对象下钻字段（null = 传统 put 数值模式）。 */
        final Field entryField;
        /** 条目字段位打包（int/long 位型存血）。 */
        final boolean entryBitPacked;
        /** E2.5 条目位打包刻度倍率（字段值 = 读数 × scale；put 模式无意义恒 1）。 */
        final float entryScale;

        StaticMapPath(Field field, KeyKind kind) {
            this(field, kind, null, false, 1.0F);
        }

        StaticMapPath(Field field, KeyKind kind, Field entryField, boolean entryBitPacked, float entryScale) {
            this.field = field;
            this.kind = kind;
            this.entryField = entryField;
            this.entryBitPacked = entryBitPacked;
            this.entryScale = entryScale;
        }
    }

    /** 静态 Map 的候选 key 形态（泛型擦除无法静态判型，枚举试 + 联动验证定夺，文档 §9）。 */
    private enum KeyKind {
        UUID_KEY {
            @Override Object keyOf(LivingEntity t) { return t.getUUID(); }
        },
        INT_ID {
            @Override Object keyOf(LivingEntity t) { return t.getId(); }
        },
        ENTITY {
            @Override Object keyOf(LivingEntity t) { return t; }
        },
        UUID_STRING {
            @Override Object keyOf(LivingEntity t) { return t.getUUID().toString(); }
        },
        NAME {
            @Override Object keyOf(LivingEntity t) { return t.getScoreboardName(); }
        };

        abstract Object keyOf(LivingEntity t);
    }

    /**
     * L3 攻击入口：命中缓存直接写；未命中全扫（目标类+父链 static Map 字段，
     * 含 Mixin 合并字段——getDeclaredFields 天然可见）。
     * <p>
     * 探测协议（不变量③④）：key 已存在（运行时缓存型血量表必然已有本实体条目）
     * → 快照旧值探针后还原旧值；key 不存在（冷缓存）→ 孤儿探测后 remove 还原；
     * put 降向探针（读数−ε）→ 同栈读联动 → finally 按探测前状态还原；
     * 联动验证（变化≥门槛 且 指向测试值）通过才落攻击写入。
     * put 期间对方代码无执行窗口（主线程同调用栈），跨类型 value 的瞬时条目不残留。
     *
     * @param writeValue 攻击写入值：淬魂传"读数−本次伤害"（磨血/伤害语义）；
     *                   影杀处决传 0（归零/处决语义）——能力差异化
     * @return true 表示攻击写入完成
     */
    @SuppressWarnings("unchecked")
    static boolean staticMapStrike(LivingEntity target, float writeValue) {
        Class<?> cls = target.getClass();

        // 缓存快路径：句柄→当前 Map 实例→put 攻击值（写后联动验证，审查修 P3#2）；
        // 解析失败视为漂移作废重扫
        StaticMapPath cached = L3_CACHE.get(cls);
        if (cached != null && applyAttack(target, cached, writeValue)) {
            return true;
        }
        if (cached != null) {
            L3_CACHE.remove(cls);
            onPositiveCacheDrift();
        }

        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (!(f.get(null) instanceof Map<?, ?> rawMap)) continue;
                    Map<Object, Object> map = (Map<Object, Object>) rawMap;
                    for (KeyKind kind : KeyKind.values()) {
                        // 审查修 P2#3：per-kind 异常隔离——TreeMap 类 Map 对跨类型 key 的
                        // containsKey/put 会抛 ClassCastException，原实现异常直接跳出字段级
                        // catch，NAME/UUID_STRING 等本可命中的 kind 被拦腰截断（L3 整体误封存）
                        try {
                            Object key = kind.keyOf(target);
                            if (key == null) continue;
                            // key 已存在（运行时缓存型血量表必然已有本实体条目）→ 快照旧值探针后还原；
                            // key 不存在（冷缓存）→ 孤儿探测后 remove（无旧值可写回，文档 §9）
                            boolean existed = map.containsKey(key);
                            Object oldVal = existed ? map.get(key) : null;
                            // v1.4.8 E3 类型预检 + 条目对象下钻：条目值是自定义对象（非数值）时
                            // 严禁 put 数值探针——瞬时破坏 map 的类型约定（对方代码同栈外的窗口
                            // 虽不存在，但 ClassCastException 会在对方下一次读条目时炸出本探测域），
                            // 且探测本身因联动验证失败而无效。改为对条目对象做一层字段插针
                            if (existed && oldVal != null && !(oldVal instanceof Number) && !(oldVal instanceof Boolean)) {
                                DrillHit drilled = probeEntryDrill(target, oldVal, writeValue);
                                if (drilled != null) {
                                    L3_CACHE.put(cls, new StaticMapPath(f, kind,
                                        drilled.field(), drilled.bitPacked(), drilled.scale()));
                                    DebugLog.probe("[L3] 静态容器条目对象命中 {}#{} key={} 字段{}{} → {}",
                                        c.getSimpleName(), f.getName(), kind, drilled.field().getName(),
                                        drilled.bitPacked() ? "(位打包 x" + drilled.scale() + ")" : "", writeValue);
                                    return true;
                                }
                                continue; // 本 key 形态的条目非血量载体 → 下一 kind
                            }
                            if (probeStaticMap(target, map, key, existed, oldVal)) {
                                // 命中：落攻击值 + 缓存句柄
                                map.put(key, writeValue);
                                L3_CACHE.put(cls, new StaticMapPath(f, kind));
                                DebugLog.probe("[L3] 静态容器命中 {}#{} key={} (条目原{}) → {}",
                                    c.getSimpleName(), f.getName(), kind, existed ? "已存在" : "不存在", writeValue);
                                return true;
                            }
                        } catch (Exception ignored) {
                            // 跨类型 key / 探测异常 → 下一 kind
                        }
                    }
                } catch (Exception ignored) {
                    // 反射不可访问/字段值异常 → 下一候选
                }
            }
        }
        DebugLog.probe("[L3] {} 静态 Map 扫描未命中", cls.getSimpleName());
        return false;
    }

    /**
     * 缓存快路径攻击写入（审查修 P3#2：补写后联动验证——与慢路径 probeStaticMap 同款闭环。
     * 原实现 put 即 return true，对方改版换血量源后缓存命中静默空转且无失效信号[不触发
     * onPositiveCacheDrift 级联]，引擎恒返回 true 让调用方误判磨血成功）。
     * 预期下降量按写前条目值推算（部分联动型合成血：合成读数 = map 分量 + B，B 不变时
     * 读数下降量 = mapBefore − writeValue，与纯源场景统一）。
     */
    @SuppressWarnings("unchecked")
    private static boolean applyAttack(LivingEntity target, StaticMapPath path, float writeValue) {
        try {
            if (!(path.field.get(null) instanceof Map<?, ?> rawMap)) return false;
            Map<Object, Object> map = (Map<Object, Object>) rawMap;
            Object key = path.kind.keyOf(target);
            float before = target.getHealth();
            // v1.4.8 E3：条目对象模式（entryField != null）写 map.get(key).entryField
            // （值语义或位打包），不再向 map put 数值（类型约定保护）
            if (path.entryField != null) {
                Object entry = map.get(key);
                if (entry == null) return false;
                path.entryField.setAccessible(true);
                Object prev = path.entryField.get(entry);
                if (path.entryBitPacked) {
                    bitsWrite(entry, path.entryField, Float.floatToRawIntBits(writeValue * path.entryScale) & 0xFFFFFFFFL);
                } else {
                    Class<?> ft = path.entryField.getType();
                    if (ft == Float.class || ft == Object.class) {
                        path.entryField.set(entry, writeValue);
                    } else {
                        path.entryField.setFloat(entry, writeValue);
                    }
                }
                float after = target.getHealth();
                float eps = ProbeScales.epsilon(Math.max(before, 1.0F));
                float driftTol = ProbeScales.driftTolerance(eps);
                if (after > before + driftTol) return false;
                // 复查修（P1）：位打包条目的 prev 是位型整数（如 100.0F 的位型
                // 0x42C80000≈1.12e9），按值语义算 expectedDrop 恒天文数字 → 验证恒失败 →
                // 每刀级联失效风暴（L3_CACHE.remove + onPositiveCacheDrift + 全层重扫）。
                // 位打包分支按解码值参与预期下降量推算
                if (prev instanceof Number n) {
                    float prevVal = path.entryBitPacked
                        ? Float.intBitsToFloat((int) n.longValue())
                        : n.floatValue();
                    // E2.5：位打包条目的解码值在字段刻度上，期望降幅换回读数刻度再比对
                    float expectedDrop = path.entryBitPacked
                        ? prevVal / path.entryScale - writeValue
                        : prevVal - writeValue;
                    float actualDrop = before - after;
                    if (expectedDrop >= ProbeScales.verifyThreshold(eps) && actualDrop < expectedDrop - driftTol) {
                        return false;
                    }
                }
                return true;
            }
            Object prev = map.get(key);
            map.put(key, writeValue);
            float after = target.getHealth();
            float eps = ProbeScales.epsilon(Math.max(before, 1.0F));
            float driftTol = ProbeScales.driftTolerance(eps);
            // 读数异常上升 = 通路指向错误宿主
            if (after > before + driftTol) return false;
            if (prev instanceof Number n) {
                float expectedDrop = n.floatValue() - writeValue;
                float actualDrop = before - after;
                if (expectedDrop >= ProbeScales.verifyThreshold(eps) && actualDrop < expectedDrop - driftTol) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 单个静态 Map 的单 key 探测：put 降向探针 → 联动验证 → finally 按探测前状态还原
     * （key 原存在→写回快照旧值；原不存在→remove 条目）。
     * 联动验证失败（含对方 getHealth 因跨类型 value 抛异常）返回 false，无残留。
     */
    @SuppressWarnings("unchecked")
    private static boolean probeStaticMap(LivingEntity target, Map<?, ?> rawMap, Object key,
                                          boolean existed, Object oldVal) {
        Map<Object, Object> map = (Map<Object, Object>) rawMap;
        // 验证读数用裸 getHealth()（实测修复）：getEffectiveHealth 的架空判定阈值 1.0 会
        // 恰好吞掉 eps=1.0 的探针扰动（49360 血下 |49359−49360|=1.0 不大于 1.0 → 回落 direct，
        // 读数恒不变 → 验证必败）。联动验证关心"变化+指向"，与 probeGraph/门禁统一用 getHealth()。
        float reading = target.getHealth();
        float eps = ProbeScales.epsilon(reading);
        // 审查修 P2#3：探针 put 也纳入异常防护——TreeMap 类 Map 跨类型 key 的 ClassCastException
        // 抛在 put（compareTo）时不再中断本方法，直接下一 kind
        try {
            map.put(key, reading - eps);
        } catch (Exception e) {
            return false; // 写入未生效，无需还原
        }
        float after;
        try {
            after = target.getHealth();
        } catch (Exception e) {
            restoreEntryQuietly(map, key, existed, oldVal);
            return false; // 探测读数失败，仍需还原
        }
        // 还原（审查修 P3#1：显式 null 值条目对部分 Map 实现 put(key, null) 会 NPE——
        // 退化为 remove，并对还原本身的异常兜底）
        restoreEntryQuietly(map, key, existed, oldVal);
        boolean changed = Math.abs(after - reading) >= ProbeScales.verifyThreshold(eps);
        boolean directed = Math.abs(after - (reading - eps)) <= ProbeScales.driftTolerance(eps);
        return changed && directed;
    }

    /** 探测条目还原：原存在→写回快照旧值（null 值条目退化为 remove）；原不存在→remove。异常静默。 */
    private static void restoreEntryQuietly(Map<Object, Object> map, Object key, boolean existed, Object oldVal) {
        try {
            if (existed && oldVal != null) {
                map.put(key, oldVal);
            } else {
                map.remove(key);
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== v1.4.8 E3：条目对象下钻 ====================

    /** E2.5 倍率刻度候选（与 HealthUtil.BIT_SCALE_CANDIDATES 同款，本地副本）。 */
    private static final float[] BIT_SCALES = {1.0F, 10.0F, 0.1F};

    /** E3 条目对象下钻命中：字段 + 位打包标记 + E2.5 刻度倍率。 */
    private record DrillHit(Field field, boolean bitPacked, float scale) {}

    /**
     * v1.4.8 E3 条目对象下钻：对静态 Map 条目值（自定义对象，static WeakHashMap&lt;实体,
     * HealthSlots&gt; 藏血型的值侧）做<b>一层</b>字段插针。
     * <p>
     * 与 {@code HealthUtil.probeGraph} ① 循环同判据族（值闸形态分类 → 降向扰动 →
     * getHealth 联动验证 → finally 同栈还原 → 命中写入目标值），两种形态：
     * float/Float 正向值语义；int/long 位打包（解码值闸 + 位型扰动）。只扫条目对象本体
     * 字段不递归（深层嵌套归 L2 对象图管），验证读用裸 {@code getHealth()}（与
     * {@link #probeStaticMap} 同口径——getEffectiveHealth 的架空阈值会吞掉 eps 级扰动）。
     * 命中后 L3 在走梯中直接返回（与 probeStaticMap 同姿态，无 verifyComposite 总读数
     * 验证）——合成血（条目分量 + 另一分量）的"错误成功"由缓存快路径 applyAttack 的
     * 写后方向/降幅校验与级联失效兜底，单分量场景（本形态主用例）无此顾虑。
     *
     * @return 命中字段 + 位打包标记；未命中返回 null（条目对象已按快照还原，无残留）
     */
    private static DrillHit probeEntryDrill(LivingEntity target, Object entryObj, float writeValue) {
        float reading = target.getHealth();
        float eps = ProbeScales.epsilon(reading);
        float gateTol = ProbeScales.gateTolerance(reading);
        float verifyTh = ProbeScales.verifyThreshold(eps);
        float driftTol = ProbeScales.driftTolerance(eps);
        for (Class<?> c = entryObj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                boolean bitsTyped = ft == int.class || ft == long.class || ft == Integer.class || ft == Long.class;
                if (!bitsTyped && ft != float.class && ft != Float.class) continue;
                try {
                    f.setAccessible(true);
                    if (bitsTyped) {
                        // E2.5：倍率刻度值闸（内部轨放大存储型，解码值 = 读数 × scale）；
                        // 倍率集与 HealthUtil 同款（本地副本，与 bitsRead/bitsWrite 同纪律）
                        long origBits = bitsRead(entryObj, f);
                        float decoded = Float.intBitsToFloat((int) origBits);
                        if (!Float.isFinite(decoded) || decoded <= 0.0F) continue;
                        // E2.5（复查修 P3 同款）：候选不短路，探针失败 continue 下一候选
                        for (float scale : BIT_SCALES) {
                            float refScaled = reading * scale;
                            if (refScaled <= 0.0F || Math.abs(decoded - refScaled) > gateTol * scale) continue;
                            long probeBits = Float.floatToRawIntBits(decoded - eps * scale) & 0xFFFFFFFFL;
                            float before = target.getHealth();
                            bitsWrite(entryObj, f, probeBits);
                            float after;
                            try {
                                after = target.getHealth();
                            } finally {
                                bitsWrite(entryObj, f, origBits);
                            }
                            if (Math.abs(after - before) < verifyTh) continue;
                            if (Math.abs(after - (reading - eps)) > driftTol) continue;
                            bitsWrite(entryObj, f, Float.floatToRawIntBits(writeValue * scale) & 0xFFFFFFFFL);
                            return new DrillHit(f, true, scale);
                        }
                        continue;
                    }
                    // float 值语义形态
                    boolean boxed = ft == Float.class;
                    float orig = boxed ? ((Number) f.get(entryObj)).floatValue() : f.getFloat(entryObj);
                    if (Math.abs(orig - reading) > gateTol) continue;
                    float before = target.getHealth();
                    if (boxed) {
                        f.set(entryObj, orig - eps);
                    } else {
                        f.setFloat(entryObj, orig - eps);
                    }
                    float after;
                    try {
                        after = target.getHealth();
                    } finally {
                        if (boxed) {
                            f.set(entryObj, orig);
                        } else {
                            f.setFloat(entryObj, orig);
                        }
                    }
                    if (Math.abs(after - before) < verifyTh) continue;
                    if (Math.abs(after - (reading - eps)) > driftTol) continue;
                    if (boxed) {
                        f.set(entryObj, writeValue);
                    } else {
                        f.setFloat(entryObj, writeValue);
                    }
                    return new DrillHit(f, false, 1.0F);
                } catch (Exception ignored) {
                    // 访问失败/类型异常 → 下一字段
                }
            }
        }
        return null;
    }

    /** 位打包字段读（低 32 位位型统一装 long；与 HealthUtil 同语义，L3 局部副本避免跨层依赖扩散）。 */
    private static long bitsRead(Object obj, Field f) throws IllegalAccessException {
        if (f.getType() == int.class) return f.getInt(obj);
        if (f.getType() == long.class) return f.getLong(obj);
        Object o = f.get(obj);
        if (o instanceof Integer i) return i;
        if (o instanceof Long l) return l;
        throw new IllegalStateException("not a bits-typed field: " + f);
    }

    /** 位打包字段写（int 位宽取低 32 位；装箱类型走 get/set）。 */
    private static void bitsWrite(Object obj, Field f, long bits) throws IllegalAccessException {
        if (f.getType() == int.class) {
            f.setInt(obj, (int) bits);
        } else if (f.getType() == long.class) {
            f.setLong(obj, bits);
        } else if (f.getType() == Long.class) {
            f.set(obj, Long.valueOf(bits));
        } else {
            f.set(obj, Integer.valueOf((int) bits));
        }
    }
}
