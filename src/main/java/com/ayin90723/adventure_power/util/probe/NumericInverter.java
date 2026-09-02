package com.ayin90723.adventure_power.util.probe;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.TrustedRead;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L5 通用数值反演（v1.4.9 第四部分）——引擎决策梯最后一层黑盒求解。
 * <p>
 * L1~L4 的值闸识别要求候选字段与读数的关系落在已知形态内（正向/承伤累计反向/位打包/
 * E2.5 固定倍率集）；变换超集（任意公式、多字段组合）时候选被 continue 丢弃，最终
 * exhausted 走封存。本层不识别公式：扰动观察响应、数值求解——把"变换未知"从死路
 * 变成慢路。
 * <p>
 * <b>爆炸半径声明（评审修订）</b>：斜率筛对收集到的任何数值字段都扰动一针——这是引擎
 * 建成以来最大的一次爆炸半径扩张。半径已收缩为"target 自身对象图 + 静态容器中归属
 * target 的条目"：walk 遇任何 {@code Entity != target} 即剪（不限 LivingEntity——弹射物/
 * 盔甲架同剪），静态 Map 条目 key 为其他实体时其 value 整体不进收集——非目标实体
 * （含玩家）字段被触碰的通路显式关死。护栏：δ 小、同栈还原、快照全量回滚、
 * 位型字段与判据字段排除、独立时间预算。tick 延迟耦合盲区原样继承（写入下 tick 才被
 * 对账回刷的 Boss，即时读数差分看不到响应——误判斜率为零跳过，退 exhausted 不硬闯）。
 * <p>
 * <b>触发纪律</b>：只在 L4 失败 + 封存前放宽补探失败后触发（常规 Boss L1 命中零开销
 * 零风险）；失败全量回滚后照常 exhausted。
 * <p>
 * <b>已知限制（四轮剪枝代价）</b>：{@code Entity != target 即剪}意味着"血量存储在关联
 * 实体上"的形态 walk 被剪断——其中"关联实体 + 线性镜像"子形态 L2 本就可命中（L2 无
 * 实体剪枝、值闸驱动），实际损失仅"关联实体 + 非线性变换"的双重 exotic 组合，退
 * exhausted 走影杀兜底。
 */
final class NumericInverter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NumericInverter() {
    }

    /** walk 深度上限（同 probeGraph 的 GRAPH_DEPTH_LIMIT 量级，放宽到 12 供公式型更深宿主）。 */
    private static final int WALK_DEPTH_LIMIT = 12;
    /** visited 对象安全上限（独立于配置——防深广度组合爆内存/时间）。 */
    private static final int VISIT_HARD_LIMIT = 200_000;
    /** 割线下降轮数上限。 */
    private static final int MAX_ROUNDS = 64;
    /** 步内 scale 减半重试上限。 */
    private static final int MAX_STEP_RETRIES = 12;
    /** 斜率有效容差（无量纲比值，|slope| ≤ 此值视为无耦合噪声）。 */
    private static final double SLOPE_TOLERANCE = 0.1;
    /** E1 纪律：静态 Map 仅根域（depth==0）开放递归，条目数上限同 HealthUtil.STATIC_MAP_ENTRY_LIMIT。 */
    private static final int STATIC_MAP_ENTRY_LIMIT = 4096;

    // ==================== Cell 模型 ====================

    /** 可扰动单元：数值原始类型字段 + 宿主 + 实测斜率 + 快照。 */
    private static final class Cell {
        final Field field;
        /** 宿主（null = 静态字段）。 */
        final Object owner;
        /** 实测斜率（读数变化 / 字段值变化）。 */
        double slope;
        /** 快照（进入求解时的初值，全量回滚用）。 */
        Object snapshot;

        Cell(Field field, Object owner) {
            this.field = field;
            this.owner = owner;
        }

        Object read() throws IllegalAccessException {
            return field.get(owner);
        }

        void write(double v) throws IllegalAccessException {
            Class<?> ft = field.getType();
            if (ft == float.class) field.setFloat(owner, (float) v);
            else if (ft == double.class) field.setDouble(owner, v);
            else if (ft == int.class) field.setInt(owner, (int) Math.round(v));
            else if (ft == long.class) field.setLong(owner, Math.round(v));
            else if (ft == Float.class) field.set(owner, (float) v);
            else if (ft == Double.class) field.set(owner, v);
            else if (ft == Integer.class) field.set(owner, (int) Math.round(v));
            else field.set(owner, Math.round(v));  // Long
        }

        double doubleValue() throws IllegalAccessException {
            Object v = field.get(owner);
            return v instanceof Number n ? n.doubleValue() : Double.NaN;
        }
    }

    /** 求解缓存（per-instance 弱 key——CAP_WRITE_CACHE 同款教训：路径含实例相关 key，per-class 缓存对成群同类 Boss 互相作废）。 */
    private static final class CachedSolution {
        final List<CachedCell> cells;

        CachedSolution(List<CachedCell> cells) {
            this.cells = cells;
        }
    }

    private static final class CachedCell {
        final Field field;
        /** 宿主弱引用（null 引用本身 = 静态字段；引用过期 → 缓存作废重解）。 */
        final WeakReference<Object> ownerRef;
        final double slope;

        CachedCell(Field field, WeakReference<Object> ownerRef, double slope) {
            this.field = field;
            this.ownerRef = ownerRef;
            this.slope = slope;
        }
    }

    private static final Map<LivingEntity, CachedSolution> SOLUTION_CACHE =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 级联失效入口（BloodWriteEngine 统一调）：全部求解缓存作废。 */
    static void invalidate() {
        SOLUTION_CACHE.clear();
    }

    // ==================== 主入口 ====================

    /**
     * L5 黑盒求解：让读数到达 writeValue（磨血/处决双语义均适用——求解的是"读数=目标"
     * 的存储配置，与语义无关）。
     *
     * @return true = 求解成功（读数终验达标）；false = 失败（已全量回滚，走 exhausted）
     */
    static boolean invert(LivingEntity target, float writeValue) {
        if (target instanceof net.minecraft.world.entity.player.Player) return false;
        long budgetMs = ModConfig.QUENCH_INVERSION_BUDGET_MS.get();
        long deadline = System.nanoTime() + Math.max(1L, budgetMs) * 1_000_000L;
        try {
            return invertInner(target, writeValue, deadline);
        } catch (Exception e) {
            // 层内全吞（与库内其他层风格一致）：L5 任何意外按失败处理，走 exhausted
            DebugLog.probe("[L5] 反演异常（按失败处理）: {}", e.toString());
            return false;
        }
    }

    private static boolean invertInner(LivingEntity target, float writeValue, long deadline) {

        float reading = TrustedRead.value(target);
        float eps = ProbeScales.epsilon(Math.max(Math.abs(reading), 1.0F));
        float driftTol = ProbeScales.driftTolerance(eps);
        if (Math.abs(reading - writeValue) <= driftTol) return true;  // 已达标零动作

        // ⓪ 缓存快路径：直接用缓存斜率走一步割线 + 写前漂移验证（O(1)）
        CachedSolution cached = SOLUTION_CACHE.get(target);
        if (cached != null) {
            if (applyCached(target, cached, writeValue, driftTol)) {
                DebugLog.probe("[L5] 缓存快路径命中（斜率复用）");
                return true;
            }
            SOLUTION_CACHE.remove(target);
            BloodWriteEngine.onPositiveCacheDrift();
        }

        // ① 收集 Cell（实体边界剪枝 + 位型/判据字段排除；P2-5 修复：收集阶段同样受
        // 时间预算约束——大对象图（VISIT_HARD_LIMIT 20 万级）walk 本身可耗数百毫秒，
        // 无预算检查会单 tick 卡顿 4~5 倍于配置承诺）
        Set<Field> excluded = collectExcluded(target);
        List<Cell> cells = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Field> staticSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        int maxCells = ModConfig.QUENCH_INVERSION_MAX_CELLS.get();
        collectCells(target, target, 0, visited, cells, excluded, staticSeen, maxCells,
            Math.abs(reading), deadline);
        if (cells.isEmpty()) return false;

        // 全量快照（任何失败路径 finally 逆序回滚）
        boolean[] snapshotOk = {true};
        for (Cell c : cells) {
            try {
                c.snapshot = c.read();
            } catch (Exception e) {
                snapshotOk[0] = false;
            }
        }
        if (!snapshotOk[0]) return false;

        boolean success = false;
        try {
            // ② 斜率筛：每 Cell 首针 −δ（读不出响应再 +δ——不变量③ 探测方向恒朝血量下降）
            List<Cell> relevant = new ArrayList<>();
            for (Cell c : cells) {
                Double slope = probeSlope(target, c, eps);
                if (slope != null && Math.abs(slope) > SLOPE_TOLERANCE) {
                    c.slope = slope;
                    relevant.add(c);
                }
                if (System.nanoTime() > deadline) return false;  // 预算截止（finally 回滚）
            }
            if (relevant.isEmpty()) {
                DebugLog.probe("[L5] 斜率筛无 relevant Cell（tick 延迟耦合型？），退 exhausted");
                return false;
            }

            // ③ 逐坐标割线下降（≤64 轮 × 预算截止）
            for (int round = 0; round < MAX_ROUNDS; round++) {
                if (System.nanoTime() > deadline) break;
                float cur = TrustedRead.value(target);
                double err = cur - writeValue;
                if (Math.abs(err) <= driftTol) break;
                if (!descendStep(target, relevant, err, writeValue, driftTol, deadline)) {
                    // 一步全失败（已还原该步）——继续下一轮无新信息，整体失败
                    return false;
                }
            }

            // 写后终验：TrustedRead 读数匹配 → SUCCESS；否则全量回滚
            float finalReading = TrustedRead.value(target);
            if (Math.abs(finalReading - writeValue) <= driftTol) {
                cacheSolution(target, relevant);
                DebugLog.probe("[L5] 数值反演命中：读数 {} → {}（relevant={} Cell）",
                    reading, finalReading, relevant.size());
                success = true;
                return true;
            }
            return false;
        } finally {
            if (!success) {
                rollback(cells);
            }
        }
    }

    // ==================== ⓪ 缓存快路径 ====================

    /** 缓存斜率一步割线 + 写前漂移验证；失败返回 false（调用方作废缓存重新全量求解）。 */
    private static boolean applyCached(LivingEntity target, CachedSolution cached, float writeValue, float driftTol) {
        List<Cell> live = new ArrayList<>(cached.cells.size());
        try {
            float cur = TrustedRead.value(target);
            double err = cur - writeValue;
            if (Math.abs(err) <= driftTol) return true;
            for (CachedCell cc : cached.cells) {
                Object owner = cc.ownerRef != null ? cc.ownerRef.get() : null;
                // 静态字段（ownerRef==null）owner 保持 null；实例宿主过期 → 缓存失效
                if (cc.ownerRef != null && owner == null) return false;
                Cell c = new Cell(cc.field, owner);
                c.slope = cc.slope;
                c.snapshot = c.read();
                live.add(c);
            }
            for (Cell c : live) {
                c.write(c.doubleValue() + err / c.slope);
            }
            float after = TrustedRead.value(target);
            if (Math.abs(after - writeValue) <= driftTol) return true;
            // 漂移判定（计划 4.2）：快路径的预期是"一步割线读数到位"——只部分移动 = 与
            // 预期不符（斜率随区间变化/路径失效），作废缓存，本刀交回全量求解续推。
            // 不能把"方向对但未达标"当成功（磨血刀磨少了，上层 execute 会误判写入完成）
            DebugLog.probe("[L5] 缓存快路径漂移（读数 {} 未按斜率预期到位 {}），作废重解", after, writeValue);
            restoreCells(live);
            return false;
        } catch (Exception e) {
            restoreCells(live);
            return false;
        }
    }

    /** 快路径失败还原：把已写入的 Cell 按各自 snapshot 逆序还原（全量求解快照的应是命中前原值）。 */
    private static void restoreCells(List<Cell> live) {
        for (int i = live.size() - 1; i >= 0; i--) {
            Cell c = live.get(i);
            try {
                if (c.snapshot != null) c.field.set(c.owner, c.snapshot);
            } catch (Exception ignored) {
            }
        }
    }

    private static void cacheSolution(LivingEntity target, List<Cell> relevant) {
        List<CachedCell> out = new ArrayList<>(relevant.size());
        for (Cell c : relevant) {
            out.add(new CachedCell(c.field,
                c.owner == null ? null : new WeakReference<>(c.owner), c.slope));
        }
        SOLUTION_CACHE.put(target, new CachedSolution(out));
    }

    // ==================== ① Cell 收集（对象图 walk） ====================

    /** 排除集：E2 已识别位打包形态字段（启发式判定）+ DeathKeyRecord 判据字段（七轮跨联）。 */
    private static Set<Field> collectExcluded(LivingEntity target) {
        Set<Field> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            var key = com.ayin90723.adventure_power.util.probe.gate.GateAnalyzer.analyzeDeathGate(target.getClass());
            if (key != null) excluded.add(key.field);
        } catch (Exception ignored) {
        }
        return excluded;
    }

    /**
     * 对象图 walk 收集数值原始类型字段（复用 probeGraph 遍历纪律 + visited 预算）。
     * <ul>
     *   <li>isSkippable 白名单式排除（Class/ClassLoader/Thread/字符串/Level/Registry）</li>
     *   <li><b>实体边界显式剪枝（硬性纪律）</b>：遇任何 {@code Entity != target} 即剪；
     *       根域静态 Map 条目 key 为其他实体时 value 不进收集</li>
     *   <li>位型字段排除：int/long 值按位解码是"与读数同量级的有限正值"→ 疑似位打包，
     *       −δ 扰它=纯噪声（E2 值闸正途已覆盖该形态）</li>
     * </ul>
     */
    private static void collectCells(LivingEntity target, Object obj, int depth, Set<Object> visited,
                                     List<Cell> out, Set<Field> excluded, Set<Field> staticSeen,
                                     int maxCells, float absReading, long deadline) {
        if (obj == null || depth > WALK_DEPTH_LIMIT) return;
        // P2-5 修复：walk 全程受时间预算约束（每层入口检查；超时中止——已收集部分
        // 可能不足求解，斜率筛/终验自然裁决，失败走 exhausted）
        if (System.nanoTime() > deadline) return;
        if (obj instanceof Class<?> || obj instanceof Thread || obj instanceof ClassLoader) return;
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) return;
        if (obj instanceof net.minecraft.world.level.Level) return;
        if (obj instanceof net.minecraft.core.Registry) return;
        // 实体边界显式剪枝（四轮评审硬性纪律）：非 target 实体一步不进（含弹射物/盔甲架）
        if (obj instanceof Entity e && e != target) return;
        if (visited.size() > VISIT_HARD_LIMIT) return;
        if (!visited.add(obj)) return;

        Class<?> cls = obj.getClass();
        // 数值原始类型字段收集（含 static——E1 纪律：static 数值字段开放，per-run staticSeen 去重）
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                Class<?> ft = f.getType();
                boolean numeric = ft == float.class || ft == double.class || ft == int.class || ft == long.class
                    || ft == Float.class || ft == Double.class || ft == Integer.class || ft == Long.class;
                if (!numeric) continue;
                if (excluded.contains(f)) continue;
                boolean isStatic = Modifier.isStatic(f.getModifiers());
                if (isStatic && !staticSeen.add(f)) continue;
                if (out.size() >= maxCells) return;
                try {
                    f.setAccessible(true);
                    Object owner = isStatic ? null : obj;
                    Object cur = f.get(owner);
                    if (!(cur instanceof Number n)) continue;
                    double v = n.doubleValue();
                    if (!Double.isFinite(v)) continue;
                    // 位型字段排除：int/long 按位解码为"与读数同量级的有限正值"→ 疑似位打包
                    if ((ft == int.class || ft == long.class || ft == Integer.class || ft == Long.class)
                        && looksBitPacked(cur, absReading)) {
                        continue;
                    }
                    out.add(new Cell(f, owner));
                } catch (Exception ignored) {
                }
            }
        }
        if (out.size() >= maxCells) return;

        // 递归引用字段（E1 纪律：静态 Map 仅根域开放 + 条目数闸；集合/自定义对象引用正常递归）
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                boolean fStatic = Modifier.isStatic(f.getModifiers());
                if (fStatic && (depth > 0 || !Map.class.isAssignableFrom(f.getType()))) continue;
                if (fStatic && !staticSeen.add(f)) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class || ft.isEnum() || ft.isArray()) continue;
                try {
                    f.setAccessible(true);
                    Object child = f.get(obj);
                    if (child == null) continue;
                    if (child instanceof Map<?, ?> m) {
                        if (fStatic && m.size() > STATIC_MAP_ENTRY_LIMIT) continue;
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            // 归属过滤（四轮评审）：静态 Map 条目 key 为其他实体时其 value
                            // 整体不进收集——"静态容器中归属 target 的条目"之外一步不碰
                            if (e.getKey() instanceof Entity ke && ke != target) continue;
                            collectCells(target, e.getValue(), depth + 1, visited, out, excluded,
                                staticSeen, maxCells, absReading, deadline);
                            if (out.size() >= maxCells) return;
                        }
                    } else if (child instanceof java.util.Collection<?> col) {
                        for (Object v : col) {
                            collectCells(target, v, depth + 1, visited, out, excluded,
                                staticSeen, maxCells, absReading, deadline);
                            if (out.size() >= maxCells) return;
                        }
                    } else {
                        collectCells(target, child, depth + 1, visited, out, excluded,
                            staticSeen, maxCells, absReading, deadline);
                        if (out.size() >= maxCells) return;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 位打包启发式：int/long 值按 float 位解码得"与读数同量级（1%~100 倍）的有限正值"。 */
    private static boolean looksBitPacked(Object raw, float absReading) {
        long bits;
        if (raw instanceof Integer i) bits = i.intValue() & 0xFFFFFFFFL;
        else if (raw instanceof Long l) bits = l.longValue();
        else return false;
        float decoded = Float.intBitsToFloat((int) bits);
        if (!Float.isFinite(decoded) || decoded <= 0.0F || absReading <= 0.0F) return false;
        float ratio = decoded / absReading;
        return ratio > 0.01F && ratio < 100.0F;
    }

    // ==================== ② 斜率筛 ====================

    /**
     * 单 Cell 斜率探测：−δ 首针（读不出响应再 +δ）→ 读数差分 → finally 还原。
     * 返回 slope（读数变化/字段值变化）；无响应返回 null。
     */
    private static Double probeSlope(LivingEntity target, Cell c, float eps) {
        try {
            double orig = c.doubleValue();
            if (!Double.isFinite(orig)) return null;
            // 首针 −δ（不变量③ 探测方向恒朝血量下降）
            Double slope = probeSlopeOneWay(target, c, orig, -eps);
            if (slope != null) return slope;
            // 读不出响应再 +δ
            return probeSlopeOneWay(target, c, orig, eps);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double probeSlopeOneWay(LivingEntity target, Cell c, double orig, double delta) {
        try {
            float before = TrustedRead.value(target);
            c.write(orig + delta);
            float after;
            try {
                after = TrustedRead.value(target);
            } finally {
                c.write(orig);
            }
            float verifyTh = ProbeScales.verifyThreshold(ProbeScales.epsilon(Math.max(before, 1.0F)));
            if (Math.abs(after - before) < verifyTh) return null;
            return (after - before) / delta;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== ③ 割线下降 ====================

    /**
     * 一步割线：对每个 relevant Cell 按 delta = err/slope 调整；步内试探（写后读数误差
     * 未减小则 scale 减半重试 ≤12 次），全失败还原该步返回 false。
     */
    private static boolean descendStep(LivingEntity target, List<Cell> relevant, double err,
                                       float writeValue, float driftTol, long deadline) {
        // 各 Cell 步前值（步还原用）
        double[] before = new double[relevant.size()];
        try {
            for (int i = 0; i < relevant.size(); i++) {
                before[i] = relevant.get(i).doubleValue();
            }
        } catch (Exception e) {
            return false;
        }
        double scale = 1.0;
        for (int retry = 0; retry < MAX_STEP_RETRIES; retry++) {
            if (System.nanoTime() > deadline) break;
            try {
                for (int i = 0; i < relevant.size(); i++) {
                    Cell c = relevant.get(i);
                    c.write(before[i] + (err / c.slope) * scale);
                }
                float after = TrustedRead.value(target);
                if (Math.abs(after - writeValue) <= driftTol) return true;  // 达标即收
                // 误差减小即收（下一轮继续）；未减小 → scale 减半重试
                if (Math.abs(after - writeValue) < Math.abs(err)) return true;
                scale *= 0.5;
            } catch (Exception e) {
                scale *= 0.5;
            }
        }
        // 全失败：还原该步（防半步残留污染下一步的斜率假设）
        try {
            for (int i = 0; i < relevant.size(); i++) {
                relevant.get(i).write(before[i]);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ==================== 回滚 ====================

    /** 全量回滚（逆序）：回滚失败的 Cell 记 ERROR（防字段永久污染）。 */
    private static void rollback(List<Cell> cells) {
        for (int i = cells.size() - 1; i >= 0; i--) {
            Cell c = cells.get(i);
            try {
                c.field.set(c.owner, c.snapshot);
            } catch (Exception e) {
                LOGGER.error("[L5] 回滚失败：{}#{}（字段可能残留求解中间值）",
                    c.field.getDeclaringClass().getSimpleName(), c.field.getName(), e);
            }
        }
    }
}
