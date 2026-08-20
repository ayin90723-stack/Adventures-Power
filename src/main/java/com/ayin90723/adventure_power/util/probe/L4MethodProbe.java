package com.ayin90723.adventure_power.util.probe;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.DebugLog;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L4 广义写路径探针（设计文档 {@code docs/quench-upgrade-proposal.md} §2/§5）。
 * <p>
 * 定位：L1 的行为学广义化——不按名字/位置找存储，而是<b>按行为找写路径</b>：
 * 扫描"实体类 ∪ 图可达 holder（限深 3）"上签名吃一个数值参数的方法，
 * 调用探针后验证 getHealth 联动。覆盖 L1/L2/L3 摸不到的形态：加密存血
 * （写入方法即加密入口，选择密文——让它自己加密斩杀值）、双字段校验型
 * （官方写方法同时更新 health+checksum）、不变量维护型。
 * <p>
 * 方法探针协议（不变量③⑥，文档 §5）：
 * <ul>
 *   <li><b>探针值规则</b>：名字含 set → setter 假设传 {@code m(reading−ε)}
 *       （若真是 setter 则扰动小）；damage/deal → delta 假设传 {@code m(ε)}；
 *       名字无假设 → 一律 {@code m(ε)} 按 delta 假设（四象限钳位：最坏抬升 ≤ ε，
 *       heal 型残余 +ε 有界无害）。</li>
 *   <li><b>形状分类</b>：调用 m(x) 后 health≈x → setter 语义；health≈reading−x →
 *       delta 语义（攻击=平凡的 {@code m(当前读数)}）；都不匹配 → 拒绝（顺手挡掉
 *       单位歧义型），失败分类不浪费。</li>
 *   <li><b>setter 二次确认</b>：防 setMaxHealth 型误分类（m(ε) 后血量被钳到 ε，
 *       形状恰似 setter；攻击 m(0) 会把 holder 自定义 max 打 0 喂 NaN）——第二探针
 *       验证"血量跟踪新值而非钳位不动"。</li>
 *   <li><b>有界瞬时扰动窗口</b>：setter 型 m(ε) 瞬设为 ε 是与字段探针的本质差异、
 *       非 bug——主线程同调用栈（不变量④），目标 tick 相位检测无观测窗口，
 *       仅写路径方法内部逻辑可能观测（明示接受的残余风险）。</li>
 *   <li><b>探测残余</b>：setter 单向锁还原失败=残血 ε、delta 扣 ε，均朝血量下降
 *       方向，无害。</li>
 * </ul>
 * 签名闸（不变量⑥副作用闸）：单参数 float|double → 返回 void|boolean|数值；
 * int 参数不进（单位歧义放大器）；getter（get/is 前缀）与语义黑名单
 * （heal/restore/regen/phase/stage/level/tier/mode/state——攻击无意义或副作用型）跳过。
 * 作用域：实体模组类链（net.minecraft 层级原版单参方法不扫）+ 图可达非
 * net.minecraft holder。
 * <p>
 * L4-B 已知明文恢复（加密容器定位+密钥推算）<b>未实施</b>：密文容器定位依赖
 * 真实对手样本指纹，无样本即盲写（误判字段会破坏对方非血量数据）——按
 * 真实对手驱动原则，等第一个加密存血 Boss 再建。
 */
final class L4MethodProbe {

    private L4MethodProbe() {
    }

    // ==================== 缓存 ====================

    /** L4 正缓存（per-entity 弱 key）：命中方法 + owner 路径链 + 语义。 */
    private static final Map<LivingEntity, CachedPath> PATH_CACHE =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** L4 负缓存（per-class）：本类全图无可用写路径，跳过整个扫描；级联失效时清空。 */
    private static final Set<Class<?>> NO_PATH = ConcurrentHashMap.newKeySet();

    /** L4 超预算退避（per-class，子代理审查修）：巨图实体中止后退避封存，级联失效时清空重试（同 L2 GRAPH_OVERWHELMED）。 */
    private static final Set<Class<?>> OVERWHELMED = ConcurrentHashMap.newKeySet();

    private static final class CachedPath {
        /** owner 路径链（Field=对象字段；其余=Map key / Collection index，同 HealthUtil.WritePath 语义）。 */
        final List<Object> steps;
        final Method method;
        /** 1=setter；2=delta；3=反向承伤 setter（见 {@link #probeOne}）。 */
        final int semantics;

        CachedPath(List<Object> steps, Method method, int semantics) {
            this.steps = steps;
            this.method = method;
            this.semantics = semantics;
        }
    }

    /** L4 负缓存级联清空入口（BloodWriteEngine 调用）。 */
    static void invalidateNegativeCache() {
        NO_PATH.clear();
        OVERWHELMED.clear();
    }

    // ==================== 主入口 ====================

    /**
     * L4 攻击入口：缓存命中直接按语义攻击；未命中全图扫描候选方法并走探针协议。
     *
     * @param writeValue 攻击目标血量：淬魂传"读数−本次伤害"（磨血）；影杀处决传 0（归零）
     * @return true 表示已按确认语义写入攻击值
     */
    static boolean strike(LivingEntity target, float writeValue) {
        // 正缓存快路径：解析 owner → 按语义攻击 + 写后联动验证（子代理审查修：方法调用
        // 副作用面大，无验证的缓存命中在目标换存储/方法加守卫后静默空转且无失效信号——
        // 与 L1 写后验证对齐）；解析/调用/验证失败视为漂移作废重扫
        CachedPath cached = PATH_CACHE.get(target);
        if (cached != null) {
            Object owner = resolveOwner(target, cached.steps);
            if (owner != null && attack(target, owner, cached.method, cached.semantics, writeValue)) {
                return true;
            }
            PATH_CACHE.remove(target);
            BloodWriteEngine.onPositiveCacheDrift();
        }
        if (NO_PATH.contains(target.getClass())) return false;

        // 超预算退避 tombstone（子代理审查修）：巨图实体每次命中重付 20 万对象遍历无封存，
        // 与 L2 GRAPH_OVERWHELMED 同生命周期（级联失效清空重试）
        if (OVERWHELMED.contains(target.getClass())) {
            DebugLog.probe("[L4] {} 对象图超预算退避中，跳过本轮流扫", target.getClass().getSimpleName());
            return false;
        }

        try {
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Object> path = new ArrayList<>();
            int result = probeOwners(target, target, 0, visited, path, writeValue);
            if (result == OWNER_HIT) {
                return true;
            }
            if (result == OWNER_ABORTED) {
                OVERWHELMED.add(target.getClass());
                DebugLog.probe("[L4] {} 对象图超预算（{} 对象），退避封存本类（级联失效时清空重试）",
                    target.getClass().getSimpleName(), visited.size());
                return false;
            }
        } catch (Exception e) {
            DebugLog.probe("[L4] 扫描异常: {}", e.toString());
        }
        NO_PATH.add(target.getClass());
        return false;
    }

    /**
     * 按语义落攻击值（v1.4.2 三态）：
     * setter（1）→ m(writeValue)；
     * delta（2）→ m(读数 − writeValue)；
     * 反向承伤 setter（3）→ m(maxHealth − writeValue)（累计写满=处决 writeValue 0 → m(maxHealth)；
     * 磨血 writeValue=读数−伤害 → m(maxHealth−读数+伤害)=m(累计+伤害)）。
     * 读数口径与探针一致（裸 getHealth/getMaxHealth）。
     * <p>
     * 写后联动验证（子代理审查修）：调用后按语义校验 getHealth 接近期望值
     * （容差 driftTol，覆盖目标换存储/方法内部守卫的漂移），失败返回 false 触发缓存作废重扫。
     */
    private static boolean attack(LivingEntity target, Object owner, Method method,
                                  int semantics, float writeValue) {
        try {
            float arg;
            float expected;
            switch (semantics) {
                case 1:
                    arg = writeValue;
                    expected = writeValue;
                    break;
                case 3:
                    arg = target.getMaxHealth() - writeValue;
                    expected = writeValue;
                    break;
                default:
                    float damage = target.getHealth() - writeValue;
                    arg = damage;
                    expected = writeValue;
                    break;
            }
            method.invoke(owner, boxArg(method, arg));
            float after = target.getHealth();
            float eps = ProbeScales.epsilon(Math.max(after, 0.0F));
            return Math.abs(after - expected) <= ProbeScales.driftTolerance(eps);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 对象图遍历 + 方法探针 ====================

    /** L4 holder 遍历深度上限（实体→字段→holder 约 2~3 跳，文档 §2 作用域闸）。 */
    private static final int HOLDER_DEPTH_LIMIT = 3;

    /** probeOwners 结果：0=扫完未命中；1=命中；2=超预算中止（不记负缓存）。 */
    private static final int OWNER_HIT = 1;
    private static final int OWNER_ABORTED = 2;

    /**
     * 递归遍历：对每个可达对象（含实体自身）扫描其模组类链上的候选方法并走探针协议；
     * 未命中则递归引用字段（同 HealthUtil.probeGraph 的边界与防环，深度更浅）。
     * 访问对象数超过 {@code quench_graph_budget} 时中止（浅层巨图防卡顿，同 L2 预算）。
     */
    private static int probeOwners(LivingEntity target, Object obj, int depth,
                                   Set<Object> visited, List<Object> path, float writeValue) {
        if (obj == null || depth > HOLDER_DEPTH_LIMIT) return 0;
        if (obj instanceof Class<?> || obj instanceof Thread || obj instanceof ClassLoader) return 0;
        if (obj instanceof net.minecraft.world.level.Level) return 0;
        if (obj instanceof net.minecraft.core.Registry) return 0;
        if (!visited.add(obj)) return 0;
        if (visited.size() > ModConfig.QUENCH_GRAPH_BUDGET.get()) return OWNER_ABORTED;
        if (probeMethods(target, obj, path, writeValue)) {
            return OWNER_HIT;
        }
        // 递归引用字段（Map 值 / Collection 元素 / 普通对象字段）
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class || ft.isEnum() || ft.isArray()) continue;
                try {
                    f.setAccessible(true);
                    Object child = f.get(obj);
                    if (child == null) continue;
                    if (child instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            path.add(f);
                            path.add(e.getKey());
                            int r = probeOwners(target, e.getValue(), depth + 1, visited, path, writeValue);
                            path.remove(path.size() - 1);
                            path.remove(path.size() - 1);
                            if (r != 0) return r;
                        }
                    } else if (child instanceof java.util.Collection<?> col) {
                        int idx = 0;
                        for (Object v : col) {
                            path.add(f);
                            path.add(idx);
                            int r = probeOwners(target, v, depth + 1, visited, path, writeValue);
                            path.remove(path.size() - 1);
                            path.remove(path.size() - 1);
                            if (r != 0) return r;
                            idx++;
                        }
                    } else {
                        path.add(f);
                        int r = probeOwners(target, child, depth + 1, visited, path, writeValue);
                        path.remove(path.size() - 1);
                        if (r != 0) return r;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    /** 语义黑名单：攻击无意义（heal 系）或副作用型（phase/stage 等状态机）。 */
    private static final String[] NAME_BLACKLIST = {
        "heal", "restore", "regen", "phase", "stage", "level", "tier", "mode", "state"
    };

    /** 扫描对象模组类链上的候选方法并逐个走探针协议；命中写入攻击值并缓存。 */
    private static boolean probeMethods(LivingEntity target, Object owner, List<Object> path, float writeValue) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class && isModClass(c); c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!isCandidate(m)) continue;
                int semantics = probeOne(target, owner, m);
                if (semantics != 0) {
                    // 命中：按语义落攻击值并缓存（per-entity 弱 key，路径链克隆）
                    if (attack(target, owner, m, semantics, writeValue)) {
                        PATH_CACHE.put(target, new CachedPath(new ArrayList<>(path), m, semantics));
                        NO_PATH.remove(target.getClass());
                        DebugLog.probe("[L4] 写路径命中 {}#{}（语义={}, 写入={})",
                            c.getSimpleName(), m.getName(),
                            semantics == 1 ? "setter" : semantics == 3 ? "反向setter" : "delta", writeValue);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 签名闸 + 名字闸：单参数 float|double → void|boolean|数值；getter 与黑名单跳过。 */
    private static boolean isCandidate(Method m) {
        if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) return false;
        Class<?>[] p = m.getParameterTypes();
        if (p.length != 1 || (p[0] != float.class && p[0] != double.class)) return false;
        Class<?> ret = m.getReturnType();
        if (ret != void.class && ret != boolean.class && !Number.class.isAssignableFrom(ret)
            && ret != int.class && ret != long.class && ret != float.class && ret != double.class) {
            return false;
        }
        String name = m.getName().toLowerCase();
        if (name.startsWith("get") || name.startsWith("is")) return false;
        for (String bad : NAME_BLACKLIST) {
            if (name.contains(bad)) return false;
        }
        return true;
    }

    /**
     * 单方法探针协议（文档 §5）：
     * 探针值按名字假设（set→reading−ε；其余→ε）→ 调用 → 形状分类
     * （health≈x→setter / health≈reading−x→delta / 不匹配拒绝）→
     * setter 二次确认（第二探针验证跟踪而非钳位，防 setMaxHealth 型）→
     * 还原尝试（setter 还原 m(reading)，单向锁拒绝则残血 ε 无害；delta 无法还原，扣 ε 无害）。
     *
     * @return true=setter 语义；false=delta 语义；null=拒绝
     */
    /**
     * 单方法探针协议（文档 §5 + v1.4.2 泽林实证补全）：
     * 探针统一传 {@code m(ε)}（小值）→ 调用 → <b>三态形状分类</b>：
     * setter（health≈ε）/ <b>反向承伤 setter（health≈maxHealth−ε，参数=承伤累计值，
     * 如泽林 setExaltedAway）</b> / delta（health≈reading−ε）。setter 与反向 setter
     * 均做二次确认（第二探针验证跟踪而非钳位，防 setMaxHealth/setMax 型误分类）→
     * 还原尝试（setter 还原 m(reading)；反向 setter 还原 m(maxHealth−reading)=累计原值；
     * 单向锁拒绝则残余朝血量下降方向无害；delta 无法还原扣 ε 无害）。
     * <p>
     * 探针统一 m(ε) 的原因（v1.4.2 实测）：set 前缀特例传 m(reading−ε) 对反向承伤 setter
     * 是参数语义错位（血量值被当累计值写入=近乎致死扰动），且无法还原；m(ε) 下最坏抬升
     * 钳死在 ε（不变量③形式化不变），三态均在 ε 小扰动下可判。
     *
     * @return 1=setter；2=delta；3=反向承伤 setter；0=拒绝
     */
    private static int probeOne(LivingEntity target, Object owner, Method m) {
        try {
            m.setAccessible(true);
            // 验证读数统一用裸 getHealth()（实测修复）：getEffectiveHealth 的架空判定阈值 1.0
            // 会吞掉 eps=1.0 的探针扰动。联动验证关心"变化+指向"，与 probeGraph/门禁/L3 同口径。
            float reading = target.getHealth();
            float maxHealth = target.getMaxHealth();
            float eps = ProbeScales.epsilon(reading);
            float before = target.getHealth();
            m.invoke(owner, boxArg(m, eps));
            float after = target.getHealth();
            if (Math.abs(after - before) < ProbeScales.verifyThreshold(eps)) {
                return 0; // 无联动 → 拒绝（黑名单已滤 heal，残余副作用风险明示接受）
            }
            float driftTol = ProbeScales.driftTolerance(eps);
            // 二次确认容差收紧（子代理审查修）：tracks 判别距离恰为 eps 而 driftTol=eps，
            // 钳位型（setMaxHealth）会恒通过——用 verifyThreshold（eps/2）严格区分。
            float confirmTol = ProbeScales.verifyThreshold(eps);
            if (Math.abs(after - eps) <= driftTol) {
                // setter 形状 → 二次确认（跟踪新值而非钳位，防 setMaxHealth 型）
                float secondArg = after + eps;
                m.invoke(owner, boxArg(m, secondArg));
                float after2 = target.getHealth();
                boolean tracks = Math.abs(after2 - secondArg) <= confirmTol;
                if (tracks) {
                    // 还原只在确认后执行（拒绝路径不追加调用——delta 误入时还原=额外扣血，子代理审查修）
                    m.invoke(owner, boxArg(m, reading));
                    return 1;
                }
                return 0;
            }
            // 反向分支前置反向地板闸（子代理审查修）：满血/近满血时 reading≈maxHealth，
            // 首次探针 delta 与反向 setter 不可区分——若误入反向分支，二次确认探针
            // m(after+eps≈maxHealth) 对 delta 是叠加扣血（磨血变秒杀）、被拒后还原再扣
            // （被拒探针也击杀）。地板闸保证只有明显低于满血（max−reading≥1.0）才允许
            // 反向判定，近满血 delta 自然落入 delta 分支（其攻击用探针后读数自校正无残差）。
            if (ProbeScales.reverseFloorMet(target, reading)
                && Math.abs(after - (maxHealth - eps)) <= driftTol) {
                // 反向承伤 setter 形状（参数=承伤累计值，血量=maxHealth−累计）→ 二次确认
                float secondArg = after + eps; // 累计 +eps → 血量应降 eps
                m.invoke(owner, boxArg(m, secondArg));
                float after2 = target.getHealth();
                boolean tracks = Math.abs(after2 - (maxHealth - secondArg)) <= confirmTol;
                if (tracks) {
                    // 还原：累计原值 = maxHealth − reading（确认后执行）
                    m.invoke(owner, boxArg(m, maxHealth - reading));
                    return 3;
                }
                return 0;
            }
            if (Math.abs(after - (reading - eps)) <= driftTol) {
                // delta 形状：攻击= m(伤害量)；无法还原，残余 -eps 无害
                return 2;
            }
            return 0; // 都不匹配（单位歧义型等）→ 拒绝
        } catch (Exception e) {
            return 0;
        }
    }

    /** float/double 参数装箱（Method.invoke 需要包装类型）。 */
    private static Object boxArg(Method m, float value) {
        return m.getParameterTypes()[0] == double.class ? (Object) Double.valueOf(value) : (Object) Float.valueOf(value);
    }

    /** 沿路径链从实体根解析 owner（Field=对象字段；其余=Map key / Collection index）。 */
    private static Object resolveOwner(Object root, List<Object> steps) {
        Object cur = root;
        for (Object step : steps) {
            if (cur == null) return null;
            if (step instanceof Field f) {
                try {
                    cur = f.get(cur);
                } catch (Exception e) {
                    return null;
                }
            } else if (cur instanceof Map<?, ?> m) {
                cur = m.get(step);
            } else if (cur instanceof java.util.Collection<?> col) {
                int idx = (Integer) step;
                int j = 0;
                Object found = null;
                for (Object o : col) {
                    if (j++ == idx) {
                        found = o;
                        break;
                    }
                }
                cur = found;
            } else {
                return null;
            }
        }
        return cur;
    }

    /** net.minecraft / JDK 层级的类不扫（原版单参方法语义已知且无血量写路径价值）。 */
    private static boolean isModClass(Class<?> c) {
        String name = c.getName();
        return !name.startsWith("net.minecraft") && !name.startsWith("java.") && !name.startsWith("javax.")
            && !name.startsWith("sun.") && !name.startsWith("jdk.") && !name.startsWith("com.mojang.");
    }
}
