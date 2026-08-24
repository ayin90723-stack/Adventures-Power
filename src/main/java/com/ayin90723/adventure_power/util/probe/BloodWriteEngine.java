package com.ayin90723.adventure_power.util.probe;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
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
            return executeInner(target, targetHealth);
        } finally {
            DebugLog.restoreEngineCaller(prevCaller);
            REENTRANT.set(false);
        }
    }

    /** 重入守卫：execute 嵌套调用（禁疗 HEAD→L4 探针链）时跳过走梯直接 raw。 */
    private static final ThreadLocal<Boolean> REENTRANT = ThreadLocal.withInitial(() -> Boolean.FALSE);

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

    /** 静态 Map 写入通路：static Map 字段 + key 形态（按实体解析具体 key）。 */
    private static final class StaticMapPath {
        final Field field;
        final KeyKind kind;

        StaticMapPath(Field field, KeyKind kind) {
            this.field = field;
            this.kind = kind;
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

        // 缓存快路径：句柄→当前 Map 实例→put 攻击值；解析失败视为漂移作废重扫
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
                        Object key = kind.keyOf(target);
                        if (key == null) continue;
                        // key 已存在（运行时缓存型血量表必然已有本实体条目）→ 快照旧值探针后还原；
                        // key 不存在（冷缓存）→ 孤儿探测后 remove（无旧值可写回，文档 §9）
                        boolean existed = map.containsKey(key);
                        Object oldVal = existed ? map.get(key) : null;
                        if (probeStaticMap(target, map, key, existed, oldVal)) {
                            // 命中：落攻击值 + 缓存句柄
                            map.put(key, writeValue);
                            L3_CACHE.put(cls, new StaticMapPath(f, kind));
                            DebugLog.probe("[L3] 静态容器命中 {}#{} key={} (条目原{}) → {}",
                                c.getSimpleName(), f.getName(), kind, existed ? "已存在" : "不存在", writeValue);
                            return true;
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

    /** 缓存快路径攻击写入。 */
    @SuppressWarnings("unchecked")
    private static boolean applyAttack(LivingEntity target, StaticMapPath path, float writeValue) {
        try {
            if (!(path.field.get(null) instanceof Map<?, ?> rawMap)) return false;
            Object key = path.kind.keyOf(target);
            ((Map<Object, Object>) rawMap).put(key, writeValue);
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
        map.put(key, reading - eps);
        float after;
        try {
            after = target.getHealth();
        } catch (Exception e) {
            return false; // finally 仍会还原
        } finally {
            if (existed) {
                map.put(key, oldVal); // 条目原存在：还原 = 写回快照旧值
            } else {
                map.remove(key);      // 条目原不存在：还原 = remove（孤儿条目规则）
            }
        }
        boolean changed = Math.abs(after - reading) >= ProbeScales.verifyThreshold(eps);
        boolean directed = Math.abs(after - (reading - eps)) <= ProbeScales.driftTolerance(eps);
        return changed && directed;
    }
}
