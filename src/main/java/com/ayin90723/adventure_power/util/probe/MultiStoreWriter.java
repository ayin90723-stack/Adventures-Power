package com.ayin90723.adventure_power.util.probe;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.HealthUtil.GraphWritePath;
import com.ayin90723.adventure_power.util.probe.gate.GateAnalyzer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多存储合成血写入器（v1.4.3，docs/gate-oracle-proposal.md §5「多存储合成血」行 + §11 第 4 条）。
 * <p>
 * 覆盖形态：{@code getHealth()} 覆写 = 多个存储之和（真血字段 + 护盾字段 / 身体槽 + 护甲槽，
 * 太阳神使 trueHealth+shieldHealth、起源 bodyHealth+armorHealth 实证）。单分量写入后
 * 合成读数 ≠ 目标（探针联动验证可通过——写分量 A 读数确实动了 −ε，但写入 targetValue 后
 * 读数 = targetValue + B，血越打越少的"错误成功"）。
 * <p>
 * 检测信号（三个入口，全部为「单分量写入后总读数验证失败」）：
 * <ol>
 *   <li>门禁直通道写后验证失败（dc2e5a4 既有信号，部分联动型：原版主槽参与合成）→
 *       {@link #upgradePrimarySlot}</li>
 *   <li>DataItem 槽插针 / 对象图插针命中写入后总读数验证失败（v1.4.3 补齐的分支级验证）→
 *       {@link #upgrade}（分量 A = 刚命中的单分量通路）</li>
 *   <li>{@link #strikeCached} 缓存快路径（首次升级成功后按实例缓存双分量通路）</li>
 * </ol>
 * 写入算法（纯行为学，不需要 ASM）：分量 A 已写 {@code writeValue} → 合成读数
 * {@code after} → 第二分量现值 {@code B_cur = after − writeValue}（加法合成 + 探针已
 * 验证 1:1 联动，差值即 B）→ 按值匹配扫 B 通路（DataItem 槽 → 对象图字段）→
 * 双分量分配写 → 总读数终态验证 → 下 tick 复验（对面 tick 分量对账回刷检测，
 * 太阳神使 0.01 震荡实证——被回刷则 per-class 封存走既有梯）。
 * <p>
 * 分配语义（§5 v2 评审）：处决（writeValue=0）公式天然双清零；磨血次分量 B 优先承伤
 * （{@code B_new = max(0, B_cur − damage)}、{@code A_new = writeValue − B_new}）。
 * 硬约束是总读数 = writeValue（A_new + B_new 恒等）；主次顺序是软偏好——若对面恰以
 * 分量 A 为死亡判定，处决路径双清零仍覆盖（磨血阶段读数照常下降，触发影杀处决时双零）。
 * 两分量假设：三分量形态（A+B+C）的 B_cur 实为 B+C 合计，按值匹配找不到单槽/字段 →
 * 验证失败回落既有梯（同「与门多旗标」立场，遇到再扩）。
 */
public final class MultiStoreWriter {

    private MultiStoreWriter() {
    }

    /** per-instance 双分量通路缓存（弱 key，同 HealthUtil.CAP_WRITE_CACHE 模式：同类多实例互不干扰）。 */
    private static final Map<LivingEntity, MultiStorePath> PATHS =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** per-class tick 回刷封存：对面 tick 分量对账把写入重算回去（下 tick 复验失败），该类不再走多存储。 */
    private static final Set<Class<?>> TICK_REVERTED = ConcurrentHashMap.newKeySet();

    /** 级联失效清空入口（BloodWriteEngine 级联点调用）。 */
    static void invalidateNegativeCache() {
        TICK_REVERTED.clear();
        SHIELD_PATHS.clear();
        NO_SHIELD.clear();
        PRIMARY_FIELDS.clear();
        INTEL_NOTIFIED.clear();
    }

    /**
     * 双分量通路。A 可能是原版主槽（部分联动型：getHealth = 主槽 + B，主槽写走
     * {@code setHealthDirect} 与直通道一致）；B 一定是 DataItem 槽或对象图字段
     * （GraphWritePath 统一表示，DataItem 槽即 field=DataItem.value + steps=[entityData, id]）。
     */
    private static final class MultiStorePath {
        /** true = A 是原版 DATA_HEALTH_ID 主槽；false = A 是 GraphWritePath 通路。 */
        final boolean aMain;
        final GraphWritePath a;
        final GraphWritePath b;

        MultiStorePath(boolean aMain, GraphWritePath a, GraphWritePath b) {
            this.aMain = aMain;
            this.a = a;
            this.b = b;
        }
    }

    private static boolean enabled(LivingEntity target) {
        return ModConfig.QUENCH_MULTI_STORE_ENABLED.get()
            && !TICK_REVERTED.contains(target.getClass());
    }

    // ==================== 入口一：缓存快路径 ====================

    /**
     * 多存储缓存快路径：双分量通路命中 → 读现值 → 合成校验（通路漂移防御）→
     * 分配 → 双写 → 终态验证 → 下 tick 复验。
     *
     * @return true 表示写入完成（总读数 ≈ writeValue）
     */
    public static boolean strikeCached(LivingEntity target, float writeValue) {
        MultiStorePath cached = PATHS.get(target);
        if (cached == null) return false;
        if (!enabled(target)) {
            PATHS.remove(target);
            return false;
        }
        try {
            float reading = target.getHealth();
            float aCur = cached.aMain ? HealthUtil.getHealthDirect(target)
                : readOrZero(HealthUtil.readGraphPathValue(target, cached.a));
            float bCur = readOrZero(HealthUtil.readGraphPathValue(target, cached.b));
            // 通路漂移防御：分量现值之和必须仍解释当前合成读数，否则通路指向错误宿主
            if (Math.abs(aCur + bCur - reading) > ProbeScales.gateTolerance(reading)) {
                PATHS.remove(target);
                BloodWriteEngine.onPositiveCacheDrift();
                DebugLog.probe("[多存储] 缓存通路漂移（A={} B={} 读数={}），作废重探", aCur, bCur, reading);
                return false;
            }
            float damage = reading - writeValue;
            float bNew = Math.max(0.0F, bCur - damage);
            float aNew = writeValue - bNew;
            HealthUtil.writeGraphPath(target, cached.b, bNew);
            if (cached.aMain) {
                HealthUtil.setHealthDirect(target, aNew);
            } else {
                HealthUtil.writeGraphPath(target, cached.a, aNew);
            }
            // 各写入无失败信号路径（主槽写 void / 图写吞异常）——统一由终态验证裁决
            return verifyFinal(target, writeValue, cached);
        } catch (Exception e) {
            PATHS.remove(target);
            BloodWriteEngine.onPositiveCacheDrift();
            return false;
        }
    }

    /** 终态验证：合成读数 ≈ writeValue；通过登记下 tick 复验并缓存通路，失败作废。 */
    private static boolean verifyFinal(LivingEntity target, float writeValue, MultiStorePath path) {
        float after = HealthUtil.getEffectiveHealth(target);
        if (Math.abs(after - writeValue) > ProbeScales.driftTolerance(ProbeScales.epsilon(writeValue))) {
            PATHS.remove(target);
            BloodWriteEngine.onPositiveCacheDrift();
            DebugLog.probe("[多存储] 终态验证失败（读数={} 目标={}），作废", after, writeValue);
            return false;
        }
        scheduleReverify(target, writeValue);
        return true;
    }

    /** 下 tick 复验（太阳神使 0.01 震荡——对面 tick 分量对账回刷检测）：读数回升超容差 = 回刷。
     * 审查修 P2#7：delay=2——写入若发生在实体 tick 内调用链（非包处理窗口），同 tick END 在
     * 对面下一轮对账之前到达，delay=1 会漏检回刷且任务已移除；2 个 END 保证对面至少跑完一轮。 */
    private static void scheduleReverify(LivingEntity target, float expected) {
        PendingVerifyRegistry.register(target, 2, new PendingVerifyRegistry.PendingTask() {
            @Override
            public boolean onVerify(LivingEntity t) {
                // 单边判定：读数更低 = 写住了（甚至被继续打，正常）；读数回升 = 对面重算回刷
                return HealthUtil.getEffectiveHealth(t) - expected
                    <= ProbeScales.driftTolerance(ProbeScales.epsilon(Math.max(expected, 0.0F)));
            }

            @Override
            public void onFail(LivingEntity t) {
                PATHS.remove(t);
                TICK_REVERTED.add(t.getClass());
                PendingVerifyRegistry.cancelAll(t);
                DebugLog.probe("[多存储] {} 下 tick 复验失败（读数被对账回刷，tick 延迟耦合），封存该类多存储通道",
                    t.getClass().getSimpleName());
            }
        });
    }

    // ==================== 入口二：直通道升级（部分联动型） ====================

    /**
     * 门禁直通道写后验证失败升级（dc2e5a4 信号）：原版主槽已是分量 A（直通道已写
     * {@code writeValue}），差值推断 B 并分配重写。
     *
     * @param readingBefore 直通道写入前的合成读数（damage = readingBefore − writeValue）
     * @param after         直通道写入后的合成读数（调用方 probeFresh 已读，免重复调用）
     */
    public static boolean upgradePrimarySlot(LivingEntity target, float writeValue, float readingBefore, float after) {
        if (!enabled(target)) return false;
        float bCur = Math.max(0.0F, after - writeValue);
        return upgradeCore(target, writeValue, readingBefore, bCur, true, null);
    }

    // ==================== 入口三：槽/图插针命中升级 ====================

    /**
     * 单分量插针命中后的升级：分量 A = 刚命中的通路（{@code HealthUtil} 单分量缓存
     * 当前条目，已写入 {@code writeValue}），差值推断 B 并分配重写。
     *
     * @param readingBefore 本轮任何写入前的合成读数
     */
    public static boolean upgrade(LivingEntity target, float writeValue, float readingBefore) {
        if (!enabled(target)) return false;
        GraphWritePath aSnap = HealthUtil.getCachedGraphPath(target);
        if (aSnap == null || aSnap.reverse()) return false; // 承伤累计分量参与合成：不 supported，回落既有梯
        float after = HealthUtil.getEffectiveHealth(target);
        float bCur = Math.max(0.0F, after - writeValue);
        return upgradeCore(target, writeValue, readingBefore, bCur, false, aSnap);
    }

    // ==================== 升级核心 ====================

    /**
     * 升级公共核心：找 B 通路（DataItem 槽 → 对象图，值匹配 + 联动验证）→
     * B 写 {@code B_new}、A 重写 {@code A_new}（B 优先承伤分配）→ 终态验证 →
     * 缓存双分量通路 + 下 tick 复验。
     * <p>
     * 失败语义：A 已写 writeValue 的部分写入状态保留（读数较写前下降 damage，方向无害），
     * 调用方继续走既有梯（L3/L4/raw）。
     */
    private static boolean upgradeCore(LivingEntity target, float writeValue, float readingBefore,
                                       float bCur, boolean aMain, GraphWritePath aPath) {
        float damage = readingBefore - writeValue;
        float bNew = Math.max(0.0F, bCur - damage);
        float aNew = writeValue - bNew;
        GraphWritePath bPath = writeSecondary(target, bCur, bNew);
        if (bPath == null) {
            DebugLog.probe("[多存储] 第二分量未找到（B_cur={}），回落既有梯", bCur);
            return false;
        }
        // A 重写：主槽走 setHealthDirect（与直通道同款 data.set 完整链）；图通路静默直写
        if (aMain) {
            HealthUtil.setHealthDirect(target, aNew);
        } else if (!HealthUtil.writeGraphPath(target, aPath, aNew)) {
            return false;
        }
        MultiStorePath path = new MultiStorePath(aMain, aPath, bPath);
        if (!verifyFinal(target, writeValue, path)) {
            return false;
        }
        PATHS.put(target, path);
        // A 的单分量缓存让位（双分量通路接管快路径；B 通路若来自图插针也已在 writeSecondary 摘出）
        HealthUtil.dropCachedWritePath(target);
        DebugLog.probe("[多存储] 命中 A={} B={} → A'={} B'={}（读数 → {}）",
            aMain ? "主槽" : "图通路", bPath.field().getName(), aNew, bNew, writeValue);
        return true;
    }

    /**
     * 找第二分量并写入 {@code bNew}：先扫 DataItem 槽（便宜），再走对象图插针
     * （值闸参照 override 为 bCur 的形态匹配）。每个候选：快照 → 写 → 合成读数联动
     * 验证（读数变化量 = bNew − bCur 才认）→ 失败还原下一个。
     *
     * @return B 通路；找不到返回 null
     */
    private static GraphWritePath writeSecondary(LivingEntity target, float bCur, float bNew) {
        float gateTol = ProbeScales.gateTolerance(bCur);
        float driftTol = ProbeScales.driftTolerance(ProbeScales.epsilon(bCur));
        float verifyTh = ProbeScales.verifyThreshold(ProbeScales.epsilon(bCur));
        // ① DataItem 槽扫描
        Map<Integer, Object> items = HealthUtil.getDataItems(target);
        if (items != null) {
            int mainId = HealthUtil.getDataHealthId() != null ? HealthUtil.getDataHealthId().getId() : -1;
            for (Map.Entry<Integer, Object> e : items.entrySet()) {
                int slotId = e.getKey();
                if (slotId == mainId) continue;
                Object item = e.getValue();
                Float v = HealthUtil.readDataItemFloat(item);
                if (v == null || Math.abs(v - bCur) > gateTol) continue;
                if (bNew == v) continue; // 无变化写无联动可验，跳过（防误中无关等值槽）
                float before = target.getHealth();
                HealthUtil.writeDataItemFloat(item, bNew);
                float afterRead = target.getHealth();
                if (Math.abs(afterRead - before) >= verifyTh
                    && Math.abs(afterRead - (before + (bNew - v))) <= driftTol) {
                    return HealthUtil.dataItemSlotPath(slotId);
                }
                HealthUtil.writeDataItemFloat(item, v); // 还原，下一个候选
            }
        }
        // ② 对象图插针（值闸参照 override：找"值 ≈ bCur"的 float 字段；内部命中写 bNew 并缓存通路）
        if (HealthUtil.probeGraphFull(target, bNew, false, bCur)) {
            GraphWritePath bPath = HealthUtil.getCachedGraphPath(target);
            HealthUtil.dropCachedWritePath(target); // B 通路从单分量缓存摘出，归 MultiStorePath 管
            if (bPath != null && !bPath.reverse()) {
                return bPath;
            }
        }
        return null;
    }

    /** 读通路值失败（路径失效/类型异常）按 0 兜底参与合成校验（校验不过自然作废缓存）。 */
    private static float readOrZero(Float v) {
        return v == null ? 0.0F : v;
    }

    // ==================== 灵魂打击（淬魂破盾，2026-08-21 实测驱动·用户构思） ====================

    /** 名字级兜底词根：护盾类次分量的名字识别（明文名目标；混淆目标走结构级，无词根零影响）。 */
    private static final String[] SHIELD_WORDS = {
        "shield", "armor", "protect", "guard", "ward", "barrier", "aegis"
    };

    /** per-class 护盾通路缓存（类 → 已验证的清零通路；级联失效时清空重扫）。 */
    private static final Map<Class<?>, List<ShieldPath>> SHIELD_PATHS = new ConcurrentHashMap<>();

    /** per-class 无护盾负缓存（结构与名字级都零命中，跳过扫描）。 */
    private static final Set<Class<?>> NO_SHIELD = ConcurrentHashMap.newKeySet();

    /** per-class 结构定位的真血字段（清盾成功即注入单分量插针缓存——对象图超预算类的定向直写，太阳神使 300001 卡线实测驱动）。 */
    private static final Map<Class<?>, java.lang.reflect.Field> PRIMARY_FIELDS = new ConcurrentHashMap<>();

    /** 护盾通路三形态：DataItem 槽（静默直写）/ 实例字段（静默直写）/ (F)V 写方法（需配套 getter 可还原）。 */
    private static final class ShieldPath {
        static final int SLOT = 0;
        static final int FIELD = 1;
        static final int METHOD = 2;

        final int kind;
        final int slotId;
        final java.lang.reflect.Field field;
        final java.lang.reflect.Method method;
        /** METHOD 形态的配套 getter（扫描期还原旧值用；其余形态 null）。 */
        final java.lang.reflect.Method getter;

        ShieldPath(int kind, int slotId, java.lang.reflect.Field field,
                   java.lang.reflect.Method method, java.lang.reflect.Method getter) {
            this.kind = kind;
            this.slotId = slotId;
            this.field = field;
            this.method = method;
            this.getter = getter;
        }
    }

    /** 护盾词根判定（public：GateAnalyzer 合成血分析的名字级主次判定复用，单一词根来源）。 */
    public static boolean isShieldName(String name) {
        String n = name.toLowerCase();
        for (String w : SHIELD_WORDS) {
            if (n.contains(w)) return true;
        }
        return false;
    }

    /**
     * 灵魂打击（淬魂语义，用户构思 2026-08-21）：淬魂直击灵魂，护盾类次分量先清零。
     * <p>
     * 设计动机（太阳神使实测）：多存储升级三入口都依赖"单分量先被值闸命中"作锚点，
     * 盾满时两分量都远离合成读数（真血 ~263/护盾 ~100 vs 读数 ~363）、值闸全挡——引擎
     * 全层失败走 raw 空转（每刀读数写 332 → 下刀回刷 366）。破盾绕开锚点依赖：清盾后
     * {@code getHealth = 真血}，单分量模型成立，值闸/插针/L4 全通道自动恢复。
     * <p>
     * 识别两级：
     * <ol>
     *   <li>结构级（优先，混淆免疫）：{@link GateAnalyzer#analyzeComposite}——getHealth
     *       覆写读取的 float 分量集合（合成读数读了谁是分量）减去 die/isDeadOrDying 覆写
     *       消费的真血分量（死亡判定读谁谁是真血），余集即护盾类次分量；</li>
     *   <li>名字级（兜底）：无覆写可分析/结构识别失败/结构级零命中时，类链扫
     *       shield/armor/protect/guard/ward/barrier/aegis 词根（槽 → 实例字段 → 带配套
     *       getter 的 (F)V 写方法）。</li>
     * </ol>
     * 两级统一行为验证：写 0 后合成读数下降 ≥ verifyTh 才认通路（防词根误中/
     * ASM 下界与运行时差异；失败还原下一候选）。
     * <p>
     * 调用纪律：淬魂每刀先清（{@code handleSoulQuench} 入口）——对面 tick 回充护盾下刀
     * 再清，"护盾对灵魂打击无效"由频率对冲维持；清盾后调用方读到的
     * {@code getEffectiveHealth} 即真血（伤害基准/斩杀线/兜底写入自动切换，无需改调用点）。
     * 普通目标：两级都零命中空转（per-class 负缓存，一次扫描后零开销）。
     */
    public static void clearShieldComponents(LivingEntity target) {
        if (!ModConfig.QUENCH_MULTI_STORE_ENABLED.get()) return;
        if (target instanceof Player) return;
        Class<?> cls = target.getClass();
        List<ShieldPath> cached = SHIELD_PATHS.get(cls);
        if (cached != null) {
            // 缓存通路：幂等清零（分量已 ≤0 跳过），不复验——首刀行为验证已确认通道，
            // 对面改版/回充的漂移由每刀重清对冲 + 幂等无害兜底
            for (ShieldPath p : cached) {
                applyShieldZero(target, p, Float.NaN);
            }
            injectPrimaryPath(target, cls);
            return;
        }
        if (NO_SHIELD.contains(cls)) return;
        float reading = HealthUtil.getEffectiveHealth(target);
        List<ShieldPath> found = new java.util.ArrayList<>();
        // ① 结构级：ASM 分量集合 − 死亡判定消费 = 护盾类次分量
        GateAnalyzer.CompositeInfo info = GateAnalyzer.analyzeComposite(target);
        if (info != null) {
            for (GateAnalyzer.CompMember sec : info.secondaries()) {
                ShieldPath p = resolveCompMember(target, sec);
                if (p != null && applyShieldZero(target, p, reading)) {
                    found.add(p);
                }
            }
            // 真血通路注入（结构定位情报）：对象图超预算封存的类（全图扫描永远到不了真血
            // 字段——太阳神使 300001 卡线实测）由 ASM 定位直达；槽形态真血不注入——
            // 槽插针不走对象图扫描，本就不受超预算封存影响
            if (info.primary != null && !info.primary.slot) {
                java.lang.reflect.Field pf = resolveCompMemberField(target, info.primary);
                if (pf != null) {
                    PRIMARY_FIELDS.put(cls, pf);
                    HealthUtil.injectFieldWritePath(target, pf);
                    if (INTEL_NOTIFIED.add(cls)) {
                        BloodWriteEngine.onNewChannelIntel(cls);  // 审查修 P2#5：首次注入才解封
                    }
                    DebugLog.probe("[灵魂打击] {} 真血字段定向通路注入：{}#{}（绕过全图扫描）",
                        cls.getSimpleName(), pf.getDeclaringClass().getSimpleName(), pf.getName());
                }
            }
        }
        // ② 名字级兜底（仅在结构级零命中时——避免双重扫描，幂等无害但日志干净）
        if (found.isEmpty()) {
            scanShieldSlots(target, found, reading);
            scanShieldFields(target, found, reading);
            scanShieldMethods(target, found, reading);
        }
        if (found.isEmpty()) {
            NO_SHIELD.add(cls);
            return;
        }
        SHIELD_PATHS.put(cls, found);
        DebugLog.probe("[灵魂打击] {} 护盾分量清零：{} 条通路（读数 {} → {}）",
            cls.getSimpleName(), found.size(), reading, HealthUtil.getEffectiveHealth(target));
    }

    /**
     * 写 0。{@code readingBefore} 非 NaN 时做行为验证（读数下降 ≥ verifyTh 才认通路，
     * 失败还原返回 false）；NaN = 缓存路径幂等清零（分量已 ≤0 直接跳过）。
     */
    private static boolean applyShieldZero(LivingEntity target, ShieldPath p, float readingBefore) {
        boolean verify = !Float.isNaN(readingBefore);
        float verifyTh = ProbeScales.verifyThreshold(ProbeScales.epsilon(Math.max(readingBefore, 0.0F)));
        try {
            switch (p.kind) {
                case ShieldPath.SLOT: {
                    GraphWritePath gp = HealthUtil.dataItemSlotPath(p.slotId);
                    Float cur = gp == null ? null : HealthUtil.readGraphPathValue(target, gp);
                    if (cur == null) return false;
                    if (cur <= 0.0F) return true; // 已清（回充未发生）
                    HealthUtil.writeGraphPath(target, gp, 0.0F);
                    if (!verify || HealthUtil.getEffectiveHealth(target) < readingBefore - verifyTh) {
                        return true;
                    }
                    HealthUtil.writeGraphPath(target, gp, cur); // 验证失败还原
                    return false;
                }
                case ShieldPath.FIELD: {
                    Object curObj = p.field.get(target);
                    float cur = curObj instanceof Float f ? f
                        : curObj instanceof Number n ? n.floatValue() : Float.NaN;
                    if (Float.isNaN(cur)) return false;
                    if (cur <= 0.0F) return true;
                    if (p.field.getType() == float.class) {
                        p.field.setFloat(target, 0.0F);
                    } else {
                        p.field.set(target, 0.0F);
                    }
                    if (!verify || HealthUtil.getEffectiveHealth(target) < readingBefore - verifyTh) {
                        return true;
                    }
                    if (p.field.getType() == float.class) {
                        p.field.setFloat(target, cur);
                    } else {
                        p.field.set(target, curObj);
                    }
                    return false;
                }
                default: { // METHOD：缓存路径幂等清零（扫描期已带 getter 验证，运行期 invoke(0) 即可）
                    // 审查修 P3#11：先读后判——盾已空时不再空转 invoke（避免每刀触发 setter 回调/同步）
                    if (p.getter != null) {
                        Object curObj = p.getter.invoke(target);
                        if (curObj instanceof Number n && n.floatValue() <= 0.0F) return true;
                    }
                    p.method.invoke(target, 0.0F);
                    return !verify || HealthUtil.getEffectiveHealth(target) < readingBefore - verifyTh;
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 结构级 CompMember → ShieldPath 介质解析（槽 accessor / 实例字段；宿主须为实体本体）。 */
    private static ShieldPath resolveCompMember(LivingEntity target, GateAnalyzer.CompMember sec) {
        try {
            if (sec.slot) {
                // 沿 target 类链按名找静态 accessor（hidden 副本 accessor 独立初始化新 id）
                for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    try {
                        java.lang.reflect.Field f = c.getDeclaredField(sec.name);
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        if (f.get(null) instanceof EntityDataAccessor<?> acc) {
                            return new ShieldPath(ShieldPath.SLOT, acc.getId(), null, null, null);
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                return null;
            }
            java.lang.reflect.Field f = resolveCompMemberField(target, sec);
            return f == null ? null : new ShieldPath(ShieldPath.FIELD, -1, f, null, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 结构级 CompMember 的实例字段解析（宿主须为实体本体——capability 宿主 v1 不追；float/Float 型）。 */
    private static java.lang.reflect.Field resolveCompMemberField(LivingEntity target, GateAnalyzer.CompMember sec) {
        try {
            // 沿 target 类链按名找（hidden class 非 isInstance——原始类校验恒 false；普通场景等价）
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(sec.name);
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Class<?> ft = f.getType();
                    if (ft == float.class || ft == Float.class) return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 已通知引擎新情报的类（审查修 P2#5：onNewChannelIntel 只在首次注入时调用——
     * 重注入每刀触发会把 exhausted/graphNoHit 封存清 false，通路漂移时每刀重走全梯
     * [泽林级 0.1~0.5s/刀]，封存机制形同虚设）。 */
    private static final Set<Class<?>> INTEL_NOTIFIED = ConcurrentHashMap.newKeySet();

    /** 每刀重注入真血通路（per-instance 插针缓存可能被引擎作废，重注入幂等自愈；
     * 封存解封只在类首次注入时做一次）。 */
    private static void injectPrimaryPath(LivingEntity target, Class<?> cls) {
        java.lang.reflect.Field pf = PRIMARY_FIELDS.get(cls);
        if (pf != null) {
            HealthUtil.injectFieldWritePath(target, pf);
            if (INTEL_NOTIFIED.add(cls)) {
                BloodWriteEngine.onNewChannelIntel(cls);  // 首次注入解封一次，重注入不再反复解封
            }
        }
    }

    /** 名字级：扫描类链 static EntityDataAccessor 字段，字段名含护盾词根 → 槽通路候选（行为验证）。 */
    private static void scanShieldSlots(LivingEntity target, List<ShieldPath> found, float reading) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c == net.minecraft.world.entity.player.Player.class) continue;
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                if (!isShieldName(f.getName())) continue;
                try {
                    f.setAccessible(true);
                    if (!(f.get(null) instanceof EntityDataAccessor<?> acc)) continue;
                    ShieldPath p = new ShieldPath(ShieldPath.SLOT, acc.getId(), null, null, null);
                    if (applyShieldZero(target, p, reading)) {
                        found.add(p);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 名字级：扫描类链（LivingEntity 前）实例 float/Float 字段，名字含护盾词根 → 字段通路候选（行为验证）。 */
    private static void scanShieldFields(LivingEntity target, List<ShieldPath> found, float reading) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class
            && c != net.minecraft.world.entity.LivingEntity.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft != float.class && ft != Float.class) continue;
                if (!isShieldName(f.getName())) continue;
                try {
                    f.setAccessible(true);
                    ShieldPath p = new ShieldPath(ShieldPath.FIELD, -1, f, null, null);
                    if (applyShieldZero(target, p, reading)) {
                        found.add(p);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 名字级：扫描类链 (F)V 写方法，名字含护盾词根且配套 getter（set→get 前缀，()F/()D）
     * 存在 → 方法通路候选。getter 是还原的前提（验证失败写回旧值，不残留乱写）——
     * invoke 前先取旧值快照，失败先还原再继续。
     */
    private static void scanShieldMethods(LivingEntity target, List<ShieldPath> found, float reading) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1 || (pts[0] != float.class && pts[0] != double.class)) continue;
                if (m.getReturnType() != void.class) continue;
                if (!isShieldName(m.getName())) continue;
                String getterName = "get" + m.getName().replaceFirst("(?i)^set", "");
                java.lang.reflect.Method getter;
                try {
                    getter = c.getDeclaredMethod(getterName);
                    if (getter.getReturnType() != float.class && getter.getReturnType() != double.class) continue;
                    getter.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    continue; // 无 getter 不可还原——保守跳过
                }
                try {
                    m.setAccessible(true);
                    float verifyTh = ProbeScales.verifyThreshold(ProbeScales.epsilon(Math.max(reading, 0.0F)));
                    Object oldObj = getter.invoke(target);
                    float old = oldObj instanceof Number n ? n.floatValue() : Float.NaN;
                    if (Float.isNaN(old) || old <= 0.0F) continue;
                    Object zero = pts[0] == double.class ? (Object) Double.valueOf(0.0) : (Object) Float.valueOf(0.0F);
                    m.invoke(target, zero);
                    if (HealthUtil.getEffectiveHealth(target) < reading - verifyTh) {
                        found.add(new ShieldPath(ShieldPath.METHOD, -1, null, m, getter));
                    } else {
                        Object restore = pts[0] == double.class ? (Object) Double.valueOf(old) : (Object) Float.valueOf(old);
                        m.invoke(target, restore);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }
}
