package com.ayin90723.adventure_power.util;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射工具 —— 直接写入 SynchedEntityData 血量字段，绕过一切 {@code setHealth()} 覆写。
 * <p>
 * 适用场景：
 * <ul>
 *   <li>Boss 重写 {@code setHealth()} 在无敌帧期间拒绝血量下降（如幻想乡的 combatProgress 系统）</li>
 *   <li>Boss 重写 {@code setHealth()} 实现双轨血量 / 硬上限 / 适应性减伤等防御机制</li>
 *   <li>任何需要绕过 setHealth 覆写、直接修改真实血量的场合</li>
 * </ul>
 * <p>
 * 实现原理：通过反射获取 {@link LivingEntity#DATA_HEALTH_ID} 静态字段，
 * 直接写入 {@code entity.getEntityData().set(DATA_HEALTH_ID, value)}，
 * 等价于原版内部的血量更新路径，但完全绕开 Java 方法覆写链。
 * <p>
 * 独立工具类，可被 {@code CombatAbilityHandler}、{@code HealingBlockEffect}、
 * 以及任何需要直接操作血量的 Mixin 或事件处理器共用。
 *
 * @see com.ayin90723.adventure_power.effect.HealingBlockEffect.EventHandler#onLivingTick
 * @see com.ayin90723.adventure_power.handler.CombatAbilityHandler
 */
public class HealthUtil {

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /**
     * hurt() 调用嵌套深度计数器。
     * 由 {@code RejectHealthManipMixin} 在 hurt() HEAD/RETURN 维护，
     * 由 {@code TrueHealthMixin} 在 setHealth RETURN 读取以判断是否合法 hurt 路径。
     */
    public static final ThreadLocal<Integer> HURT_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * 模组内部降血标记——仅在本模组主动维护玩家状态（如 vitality 关闭时
     * 把血量裁剪到新上限）的 setHealth 调用窗口内为 true。
     * 由 {@code RejectHealthManipMixin} 放行（reject_manip 防的是外部篡改，不拦模组自身状态维护），
     * 由 {@code TrueHealthMixin} 放行备份同步（否则裁剪后 backup 不更新，
     * 下次 getHealth 会把 DataItem 判定为"非法降血直写"而修复回旧值，裁剪被反向抵消）。
     * 窗口极窄（单行 setHealth，try/finally 包裹），无嵌套风险。
     */
    public static final ThreadLocal<Boolean> INTERNAL_HEALTH_WRITE = ThreadLocal.withInitial(() -> false);

    /**
     * 每 tick 末强制归零 HURT_DEPTH（由 ServerTickEnd 调用）。
     * <p>
     * 防御外部模组对 {@code hurt()} HEAD 做 cancellable cancel（低优先级 Mixin）：
     * 此时 RETURN 注入不执行、深度残留 +1，reject_manip 的 setHealth 降血保护会
     * 永久失效。hurt() 是同步调用且绝不跨 tick，故「tick 末深度必为 0」是稳定不变量，
     * 无条件归零安全；泄漏窗口从「永久」降为「cancel 发生的同 tick 内」。
     */
    public static void resetHurtDepthPerTick() {
        HURT_DEPTH.set(0);
    }

    private static Field DATA_HEALTH_ID_FIELD;
    /** volatile：初始化可能在客户端/服务端两条线程发生，保证可见性 */
    private static volatile EntityDataAccessor<Float> DATA_HEALTH_ID;

    static {
        try {
            DATA_HEALTH_ID_FIELD = LivingEntity.class.getDeclaredField("f_20961_");
        } catch (NoSuchFieldException e) {
            try {
                DATA_HEALTH_ID_FIELD = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
            } catch (NoSuchFieldException ex) {
                System.err.println("[AdventurePower] HealthUtil: 无法反射获取 DATA_HEALTH_ID 字段，setHealthDirect 将不可用");
                LOGGER.error("[HealthUtil] 反射/内部操作失败", ex);
            }
        }
        if (DATA_HEALTH_ID_FIELD != null) {
            DATA_HEALTH_ID_FIELD.setAccessible(true);
        }
    }

    /**
     * 架空参照血量 —— 统一的血量读数入口。
     * <p>
     * 部分 Boss（如启示录亚波伦）重写 {@code setHealth()/getHealth()} 走自定义
     * SynchedEntityData 槽，原版血条被架空（停在初始值不动）。此时
     * {@link #getHealthDirect} 读到的不是真实血量，回血/扣血检测会失真。
     * <p>
     * 判定规则与 {@link #scanCustomHealthKeys} 的参照逻辑一致：
     * {@code |getHealth() - getHealthDirect()| > 1.0} 即判定原版血条被架空，
     * 返回 {@code getHealth()}（重写返回的真实血量）；否则返回原版 DataItem 值
     * （防 {@code getHealth()} 被 ASM 篡改返回假值，如终焉秩序维系者的 delta 偏移）。
     * <p>
     * <b>已知边界</b>：二分规则无法区分"合法重写 getHealth 返回真实血量"与
     * "getHealth 被 delta 偏移篡改返回假值"（两者 |差值| 均 >1.0，规则都会取
     * {@code getHealth()}）。玩家侧无风险（玩家两读数恒 ≤1 差，回落 DataItem）；
     * 敌方目标侧的 delta 篡改场景（第三方把偏移注入 Boss 而非玩家）极罕见，
     * 若遇到可再按实体类做白名单修正。before/after 差值类检测（兜底补刀等）
     * 因差值抵消不受影响。
     *
     * @param target 目标实体
     * @return 真实血量
     */
    public static float getEffectiveHealth(LivingEntity target) {
        float direct = getHealthDirect(target);
        float reported = target.getHealth();
        return Math.abs(reported - direct) > 1.0F ? reported : direct;
    }

    /**
     * 直接读取 SynchedEntityData 中的真实血量字段，绕过一切 {@code getHealth()} 覆写和
     * ASM 字节码篡改（如终焉秩序维系者的 health delta 偏移）。
     * <p>
     * 读取目标为 {@code SynchedEntityData} 中 {@code DATA_HEALTH_ID} 对应的原始条目，
     * 任何对 {@code getHealth()} 的 Mixin / ASM 修改都不会影响此方法返回的值。
     *
     * @param target 目标实体
     * @return 真实血量值；反射失败时降级为 {@code target.getHealth()}
     */
    @SuppressWarnings("unchecked")
    public static float getHealthDirect(LivingEntity target) {
        if (DATA_HEALTH_ID_FIELD == null) {
            return target.getHealth();
        }
        try {
            if (DATA_HEALTH_ID == null) {
                Object rawId = DATA_HEALTH_ID_FIELD.get(null);
                if (rawId instanceof EntityDataAccessor<?> accessor) {
                    DATA_HEALTH_ID = (EntityDataAccessor<Float>) accessor;
                }
            }
            if (DATA_HEALTH_ID != null) {
                Float value = target.getEntityData().get(DATA_HEALTH_ID);
                if (value != null) return value;
            }
        } catch (IllegalAccessException | ClassCastException e) {
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
        return target.getHealth();
    }

    /**
     * 原版血量条目 accessor（{@code DATA_HEALTH_ID}，懒初始化缓存）。
     * <p>
     * 供 {@code RejectHealthManipDataMixin} 等数据同步层拦截做 key 引用比较——
     * 该字段在 {@code LivingEntity} 是 private，Mixin @Shadow 无法跨类解析
     * （@Shadow 只能在目标类及其父类内找字段），统一经本 getter 取缓存实例。
     *
     * @return 缓存的 {@code EntityDataAccessor<Float>}；反射初始化失败时返回 null
     */
    public static EntityDataAccessor<Float> getDataHealthId() {
        if (DATA_HEALTH_ID == null && DATA_HEALTH_ID_FIELD != null) {
            try {
                Object rawId = DATA_HEALTH_ID_FIELD.get(null);
                if (rawId instanceof EntityDataAccessor<?> accessor) {
                    DATA_HEALTH_ID = (EntityDataAccessor<Float>) accessor;
                }
            } catch (IllegalAccessException e) {
                LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
            }
        }
        return DATA_HEALTH_ID;
    }

    /**
     * 直接写入血量字段（绕过 setHealth() 所有覆写，包括硬上限/适应性减伤/免疫帧）。
     * <p>
     * 写入目标为 {@code SynchedEntityData} 中 {@code DATA_HEALTH_ID} 对应的条目，
     * 后续 {@code entity.getHealth()} 将返回写入值。
     * <p>
     * 本方法整体包 {@link #INTERNAL_HEALTH_WRITE} 标记：内部路径（真血修复、
     * 死亡抗拒恢复等）调用时会被 {@code RejectHealthManipDataMixin}（数据同步层
     * 降值拦截）与 {@code RejectHealthManipMixin}（setHealth 方法级拦截）放行，
     * 避免模组自愈/恢复与防御拦截互相死锁。外部对玩家的直写（未包标记）仍被拦截；
     * 攻击侧写 Boss（非玩家）不受数据层拦截影响（拦截只对玩家 owner 生效）。
     *
     * @param target 目标实体
     * @param health 目标血量值
     */
    @SuppressWarnings("unchecked")
    public static void setHealthDirect(LivingEntity target, float health) {
        boolean prevInternal = INTERNAL_HEALTH_WRITE.get();
        INTERNAL_HEALTH_WRITE.set(true);
        try {
            if (DATA_HEALTH_ID_FIELD == null) {
                return;
            }
            try {
                if (DATA_HEALTH_ID == null) {
                    Object rawId = DATA_HEALTH_ID_FIELD.get(null);
                    if (rawId instanceof EntityDataAccessor<?> accessor) {
                        DATA_HEALTH_ID = (EntityDataAccessor<Float>) accessor;
                    }
                }
                if (DATA_HEALTH_ID != null) {
                    target.getEntityData().set(DATA_HEALTH_ID, health);
                }
            } catch (IllegalAccessException | ClassCastException e) {
                LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
            }
        } finally {
            INTERNAL_HEALTH_WRITE.set(prevInternal);
        }
    }

    /**
     * 自定义血量 key 缓存 —— 按 entity class 缓存已扫描到的 Float 类型 EntityDataAccessor。
     * <p>
     * 部分 Boss（如亚波伦 RevelationFix 版）用独立的 {@code EntityDataAccessor<Float>}
     * 存储真实血量，与 {@code DATA_HEALTH_ID} 彻底脱钩。仅写原版血条无法影响其生存状态。
     * 此类缓存了扫描到的所有 "Float 型、类似血量" 的数据条目，供批量写入使用。
     * <p>
     * key: entity.getClass()；value: 除 DATA_HEALTH_ID 外所有存储 Float 值的 accessor
     */
    private static final Map<Class<?>, Set<EntityDataAccessor<Float>>> CUSTOM_HEALTH_KEYS_CACHE =
        new ConcurrentHashMap<>();

    /**
     * {@link SynchedEntityData#itemsById} 字段 (SRG: {@code f_135345_})，
     * 用于遍历实体数据中的所有 DataItem 进行原始值直写。
     */
    private static Field ENTITY_DATA_ITEMS_FIELD;

    /**
     * {@code SynchedEntityData.DataItem#value} 字段 (SRG: {@code f_135391_})，
     * DataItem 内部实际存储值的字段。直接写入此字段可完全绕过
     * {@code SynchedEntityData.set()} 的 dirty 标记、监听器、Mixin 注入等所有拦截。
     */
    private static Field DATA_ITEM_VALUE_FIELD;

    static {
        try {
            ENTITY_DATA_ITEMS_FIELD = SynchedEntityData.class.getDeclaredField("f_135345_");
        } catch (NoSuchFieldException e) {
            try {
                ENTITY_DATA_ITEMS_FIELD = SynchedEntityData.class.getDeclaredField("itemsById");
            } catch (NoSuchFieldException ex) {
                System.err.println("[AdventurePower] HealthUtil: 无法反射获取 SynchedEntityData.itemsById 字段");
                LOGGER.error("[HealthUtil] 反射/内部操作失败", ex);
            }
        }
        if (ENTITY_DATA_ITEMS_FIELD != null) {
            ENTITY_DATA_ITEMS_FIELD.setAccessible(true);
        }

        try {
            Class<?> dataItemClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataItem");
            try {
                DATA_ITEM_VALUE_FIELD = dataItemClass.getDeclaredField("f_135391_");
            } catch (NoSuchFieldException e) {
                DATA_ITEM_VALUE_FIELD = dataItemClass.getDeclaredField("value");
            }
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            System.err.println("[AdventurePower] HealthUtil: 无法反射获取 DataItem.value 字段");
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
        if (DATA_ITEM_VALUE_FIELD != null) {
            DATA_ITEM_VALUE_FIELD.setAccessible(true);
        }
    }

    /**
     * 向目标的<b>所有</b>血量数据条目写入指定值，含原版 {@code DATA_HEALTH_ID} 及
     * 所有通过反射扫描到的自定义血量 {@code EntityDataAccessor<Float>}。
     * <p>
     * 写入顺序：先写原版，再写自定义条目。每个自定义条目使用 try-catch 包裹，写入失败静默跳过。
     * <p>
     * 自定义条目扫描策略：
     * <ul>
     *   <li>遍历 entity.class → 所有父类（到 Object 为止）</li>
     *   <li>找出所有 static EntityDataAccessor 字段</li>
     *   <li>排除已知的 DATA_HEALTH_ID</li>
     *   <li>调用 {@code data.get(accessor)} 验证当前条目实际存储值为 Float 类型</li>
     *   <li>验证通过则加入缓存，后续同类型实体直接复用</li>
     * </ul>
     * 扫描在每类实体首次调用时完成，之后走缓存（约微秒级）。
     *
     * @param target 目标实体
     * @param health 目标血量值
     */
    public static void setAllHealthLikeDirect(LivingEntity target, float health) {
        // 整体包内部写入标记：内部路径（真血修复等）的 data.set 会被
        // RejectHealthManipDataMixin 放行（否则自愈与防御拦截死锁）；
        // 攻击侧写 Boss（非玩家）不受数据层拦截影响
        boolean prevInternal = INTERNAL_HEALTH_WRITE.get();
        INTERNAL_HEALTH_WRITE.set(true);
        try {
            // ① 原版血条
            setHealthDirect(target, health);

            // ② 自定义血条 — 首次命中该实体类型时扫描，之后走缓存
            Set<EntityDataAccessor<Float>> customKeys =
                CUSTOM_HEALTH_KEYS_CACHE.computeIfAbsent(target.getClass(), clz -> scanCustomHealthKeys(target));

            // ③ 全部写入
            net.minecraft.network.syncher.SynchedEntityData data = target.getEntityData();
            for (EntityDataAccessor<Float> key : customKeys) {
                try {
                    data.set(key, health);
                } catch (Exception ignored) {
                    // 极少见：entity 销毁后调用 / 类型不匹配 —— 静默跳过
                }
            }
        } finally {
            INTERNAL_HEALTH_WRITE.set(prevInternal);
        }
    }

    /**
     * 通用层直写：方法扫描 + 验证闭环。
     * <p>
     * 针对"覆写 getHealth/setHealth + 自建存储"的模组 Boss（血量不在 SynchedEntityData）：
     * 扫描目标类<b>自身声明</b>的 {@code (F)V} 方法（名含 Health，忽略大小写），逐个调用，
     * 调用后验证 {@link #getEffectiveHealth} 是否变为目标值——生效即命中。
     * 验证闭环天然避开诱饵方法（调用无效果的自然失败，如 RevelationFix 的 Idiot 空方法）。
     * 只扫自身声明方法，排除父类原版方法（不会命中原版 setHealth/setSpeed 等）。
     *
     * @return true 表示命中并写入成功
     */
    public static boolean setHealthLikeGeneric(LivingEntity target, float health) {
        for (java.lang.reflect.Method m : target.getClass().getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != float.class) continue;
            if (!m.getName().toLowerCase().contains("health")) continue;
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            try {
                m.setAccessible(true);
                m.invoke(target, health);
                if (Math.abs(getEffectiveHealth(target) - health) < 1.0F) {
                    DebugLog.probe("[通用直写] 命中 {}: {} → {}", m.getName(), target, health);
                    return true;
                }
            } catch (Exception ignored) {
                // 调用失败（访问控制/类型不符）→ 继续下一候选
            }
        }
        return false;
    }

    /** 插针命中缓存：实体类 → 真血字段写入通路（字段 + 从实体到宿主对象的引用路径链）。 */
    /**
     * 插针命中缓存：实体类 → 真血字段写入通路（字段 + 从实体到宿主对象的引用路径链）。
     * <p>
     * 路径链避免"每次直写都全对象图搜索宿主"（大对象图实体如弹幕 Boss 会卡）；
     * 写值前验证字段当前值仍接近真血读数，失效时缓存作废并回退全图插针重探测
     * （见 {@link #probeCapabilityHealth}）。
     */
    private static final java.util.Map<Class<?>, WritePath> CAP_WRITE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 真血字段写入通路：字段 + 从实体根对象到字段宿主的步骤链。 */
    private static final class WritePath {
        final java.lang.reflect.Field field;
        final java.util.List<Object> steps; // Field=对象字段；其余=Map key / Collection index（按当前节点类型解释）

        WritePath(java.lang.reflect.Field field, java.util.List<Object> steps) {
            this.field = field;
            this.steps = steps;
        }
    }

    /**
     * DataItem 联动探针：直写 DATA_HEALTH_ID 槽 value = 原值-1（绕过 set() 直改 DataItem.value 字段），
     * 观察 {@code getHealth()} 是否联动。
     * <p>
     * 联动 = {@code getHealth()} 真读该槽（正常实体）→ 返回 true，DataItem 直写足够；
     * 不联动 = {@code getHealth()} 被重定向/架空（如亚波伦 ASM 改写读 MegaCapability）→ 返回 false，
     * 必须进插针层找真血存储。扰动同 tick 内还原，客户端无感知。
     */
    private static boolean probeDataItemLinked(LivingEntity target) {
        if (ENTITY_DATA_ITEMS_FIELD == null || DATA_ITEM_VALUE_FIELD == null || DATA_HEALTH_ID == null) {
            DebugLog.probe("[插针] 门禁: 反射字段未初始化，视为联动跳过插针");
            return true; // 无法探测 → 视为正常，不扰动
        }
        try {
            Map<Integer, Object> items = (Map<Integer, Object>) ENTITY_DATA_ITEMS_FIELD.get(target.getEntityData());
            Object item = items.get(DATA_HEALTH_ID.getId());
            if (item == null) {
                DebugLog.probe("[插针] 门禁: 槽 {} 缺失，视为联动跳过", DATA_HEALTH_ID.getId());
                return true;
            }
            float orig = ((Float) DATA_ITEM_VALUE_FIELD.get(item)).floatValue();
            DATA_ITEM_VALUE_FIELD.set(item, orig - 1.0F);
            float after;
            try {
                after = target.getHealth();
            } finally {
                DATA_ITEM_VALUE_FIELD.set(item, orig); // 还原
            }
            boolean linked = Math.abs(after - (orig - 1.0F)) < 1.0F;
            DebugLog.probe("[插针] 门禁: 槽{} 原值={} 扰动后 getHealth={} 联动={}",
                DATA_HEALTH_ID.getId(), orig, after, linked);
            return linked;
        } catch (Exception e) {
            DebugLog.probe("[插针] 门禁异常: {}", e.toString());
            return true;
        }
    }

    /**
     * 对象图插针深度上限：Entity → capability → dispatcher → provider → DATA → Wrapped 约 6 层。
     */
    private static final int GRAPH_DEPTH_LIMIT = 10;

    /**
     * 通用对象图插针层：当 DataItem 直写无效（{@link #probeDataItemLinked} 判定
     * getHealth 被重定向）时，从实体自身出发<b>递归遍历全部可达对象</b>，
     * 对每个 float 字段写测试值并观察 {@code getHealth()} 是否联动 → 解明真血存储位置并完成写入。
     * <p>
     * 纯对象图遍历，无任何模组 API/包名耦合：真血容器（如 Capability provider 实例）
     * 必然在实体可达对象图内，任意层级都能被摸到。安全性由<b>验证闭环</b>保证——
     * 写 原值-1 小扰动 → {@code getHealth()} 不联动 → 立即还原，正常实体即使被插也无副作用；
     * 只有"写它 getHealth 就变"的真血字段才会落笔。对象图遍历自带 visited 防环 +
     * 深度上限 + 世界/注册表等全局巨对象跳过（通用性能边界，非模组耦合）。
     * <p>
     * 命中后按实体类缓存写入通路，后续直写零开销。
     *
     * @return true 表示插针命中并完成写入
     */
    public static boolean probeCapabilityHealth(LivingEntity target, float targetValue) {
        // 缓存命中：直接走已解明的通路（路径链直达宿主，零全图搜索）
        WritePath cached = CAP_WRITE_CACHE.get(target.getClass());
        if (cached != null) {
            try {
                Object owner = resolvePath(target, cached.steps, 0);
                if (owner == null) {
                    CAP_WRITE_CACHE.remove(target.getClass());
                    return probeFresh(target, targetValue);
                }
                // 写值前验证：对象图结构变化可能让路径链指向错误宿主（同类多实例/索引漂移），
                // 校验字段当前值仍接近真血读数（与 probeGraph 值域过滤同容差）；
                // 不匹配视为路径失效 → 缓存作废 + 全图重探测（有验证闭环，不盲写）
                float cur = cached.field.getFloat(owner);
                float ref = target.getHealth();
                if (Math.abs(cur - ref) > Math.max(1.0F, ref * 0.2F)) {
                    CAP_WRITE_CACHE.remove(target.getClass());
                    DebugLog.probe("[插针] 缓存路径失效（字段值 {} ≠ 真血读数 {}），回退全图重探测", cur, ref);
                    return probeFresh(target, targetValue);
                }
                cached.field.setFloat(owner, targetValue);
                return true;
            } catch (Exception e) {
                // 路径失效（对象图结构变化/字段不可访问）→ 缓存作废 + 全图重探测
                CAP_WRITE_CACHE.remove(target.getClass());
                return probeFresh(target, targetValue);
            }
        }
        return probeFresh(target, targetValue);
    }

    /**
     * 插针全量探测：门禁（DataItem 联动检查）→ 通用对象图插针。
     * 与 {@link #probeCapabilityHealth} 的区别：不做缓存读取，仅用于缓存缺失/失效后的
     * 首次探测——命中后自行写入并重建缓存。
     */
    private static boolean probeFresh(LivingEntity target, float targetValue) {
        // 门禁：DataItem 扰动后 getHealth 联动 = 正常实体（getHealth 真读槽 9），
        // DataItem 直写足够且更高效，不插针。门禁仅为性能优化，安全性仍由验证闭环保证。
        if (probeDataItemLinked(target)) {
            return false;
        }
        // 通用对象图插针：从实体自身递归全部可达对象
        try {
            java.util.Set<Object> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            float hit = probeGraph(target, target, 0, targetValue, visited, new java.util.ArrayList<>());
            if (hit >= 0.0F) {
                DebugLog.probe("[插针] 命中 {}: {} → {}", target, targetValue);
                return true;
            }
            DebugLog.probe("[插针] 对象图遍历完成: 访问 {} 个对象, 未命中", visited.size());
        } catch (Exception e) {
            DebugLog.probe("[插针] 异常: {}", e.toString());
        }
        return false;
    }

    /**
     * 沿路径链从实体根对象解析宿主：steps 中 Field=对象字段，其余元素按当前节点
     * 类型解释为 Map key（节点是 Map）或 Collection index（节点是 Collection）。
     * 路径失效返回 null。
     */
    private static Object resolvePath(Object cur, java.util.List<Object> steps, int i) {
        if (cur == null) return null;
        for (; i < steps.size(); i++) {
            Object step = steps.get(i);
            if (step instanceof java.lang.reflect.Field f) {
                try {
                    cur = f.get(cur);
                } catch (Exception e) {
                    return null;
                }
            } else if (cur instanceof java.util.Map<?, ?> m) {
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
            if (cur == null) return null;
        }
        return cur;
    }

    /**
     * 递归插针单个对象：扫描 float 字段（值≈当前真血）写测试值验证；
     * 命中写入目标值并缓存通路（字段 + 从实体到宿主的引用路径链），未命中还原并递归全部引用字段/容器。
     * {@code visited} 防环；深度上限 {@link #GRAPH_DEPTH_LIMIT}。
     * {@code path} 记录从实体根到当前对象的步骤链（Field=对象字段；Map 记录 key；Collection 记录 index）。
     * 返回命中字段原值，未命中返回 -1。
     */
    private static float probeGraph(LivingEntity target, Object obj, int depth, float targetValue,
                                    java.util.Set<Object> visited, java.util.List<Object> path) {
        if (obj == null || depth > GRAPH_DEPTH_LIMIT) return -1.0F;
        if (obj instanceof Class<?> || obj instanceof Thread || obj instanceof ClassLoader) return -1.0F;
        // 通用性能边界：世界/注册表等全局巨对象（不含实体血量，跳过防对象图爆炸）
        if (obj instanceof net.minecraft.world.level.Level) return -1.0F;
        if (obj instanceof net.minecraft.core.Registry) return -1.0F;
        if (!visited.add(obj)) return -1.0F; // 防环：同 tick 内同一对象只插一次
        // 参照 = getHealth()（真血读数：正常实体读槽9；重定向实体读真血源）
        float currentHealth = target.getHealth();
        Class<?> cls = obj.getClass();
        // ① float 字段插针
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != float.class && f.getType() != Float.class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    float orig = f.getFloat(obj);
                    // 值域过滤：候选必须接近当前真血（血条特征），避免扰动无关 float
                    if (Math.abs(orig - currentHealth) > Math.max(1.0F, currentHealth * 0.2F)) continue;
                    // 插针：快照 → 写 原值-1 小扰动 → 无条件还原
                    float before = target.getHealth();
                    f.setFloat(obj, orig - 1.0F);
                    float after;
                    try {
                        after = target.getHealth();
                    } finally {
                        f.setFloat(obj, orig); // 无条件还原（异常也不残留）
                    }
                    // 判据①：写入前后 getHealth 必须真实变化（防"恒定读数 + 碰巧≈血量"的无关字段误判）
                    if (Math.abs(after - before) < 0.5F) continue;
                    // 判据②：变化量必须指向测试值（真血字段特征）
                    if (Math.abs(after - (orig - 1.0F)) < 1.0F) {
                        // 命中：写入目标值并缓存通路（字段 + 实体→宿主路径链）
                        f.setFloat(obj, targetValue);
                        CAP_WRITE_CACHE.put(target.getClass(), new WritePath(f, new java.util.ArrayList<>(path)));
                        DebugLog.probe("[插针] 字段命中: {}#{} 原值={} → {}",
                            cls.getSimpleName(), f.getName(), orig, targetValue);
                        return orig;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        // ② 递归全部引用字段（含 Map/Collection/数组/自定义对象）
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class || ft.isEnum() || ft.isArray()) continue;
                try {
                    f.setAccessible(true);
                    Object child = f.get(obj);
                    if (child == null) continue;
                    if (child instanceof java.util.Map<?, ?> m) {
                        for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                            path.add(f);
                            path.add(e.getKey());
                            float r = probeGraph(target, e.getValue(), depth + 1, targetValue, visited, path);
                            path.remove(path.size() - 1);
                            path.remove(path.size() - 1);
                            if (r >= 0.0F) return r;
                        }
                    } else if (child instanceof java.util.Collection<?> col) {
                        int idx = 0;
                        for (Object v : col) {
                            path.add(f);
                            path.add(idx);
                            float r = probeGraph(target, v, depth + 1, targetValue, visited, path);
                            path.remove(path.size() - 1);
                            path.remove(path.size() - 1);
                            if (r >= 0.0F) return r;
                            idx++;
                        }
                    } else if (!child.getClass().isPrimitive()) {
                        path.add(f);
                        float r = probeGraph(target, child, depth + 1, targetValue, visited, path);
                        path.remove(path.size() - 1);
                        if (r >= 0.0F) return r;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return -1.0F;
    }

    /**
     * 分级直写统一入口：通用层 → Capability 插针层 → DataItem 兜底。
     * <p>
     * ① 通用层：方法扫描 + 验证闭环（覆盖"自定义 setter 无保护"的模组 Boss）
     * ② 插针层：Capability 递归扫描（覆盖"血量藏在实体 Capability"的 Boss，如亚波伦）
     * ③ DataItem 直写：原版血条/自定义 DataItem 槽（覆盖正常实体与架空血条）
     */
    public static void setHealthLikeAny(LivingEntity target, float health) {
        if (setHealthLikeGeneric(target, health)) return;
        if (probeCapabilityHealth(target, health)) return;
        setAllHealthLikeRaw(target, health);
    }


    /**
     * 原始数据直写 — 在 {@link #setAllHealthLikeDirect} 基础上，
     * 直接遍历 {@link SynchedEntityData} 内部所有 {@code DataItem}，
     * 绕过 {@code SynchedEntityData.set()} 方法直接写入 DataItem 的 value 字段。
     *
     * <h3>与 {@code setAllHealthLikeDirect} 的区别</h3>
     * {@code setAllHealthLikeDirect} 通过 {@code data.set(key, value)} 写入，
     * 会经过 dirty 标记、变更监听器、以及 Boss 在 {@code set()} 上注入的 Mixin。
     * 本方法<b>直接修改 DataItem 内部存储的 value 字段</b>，完全不经过
     * SynchedEntityData 的任何方法调用。
     *
     * <h3>三步写入策略</h3>
     * <ol>
     *   <li><b>快照</b>：通过 {@link #getHealthDirect} 获取当前主血量值</li>
     *   <li><b>已知 key 写入</b>：调用 {@link #setAllHealthLikeDirect}
     *       写入原版 + 所有扫描到的自定义血量 key</li>
     *   <li><b>值匹配兜底</b>：遍历所有 DataItem，找到值约等于快照值
     *       的条目（即步骤② 未覆盖的遗漏血量条目），直接写入目标值。
     *       匹配阈值 0.01，支持 Float/Double/Integer 三种类型</li>
     * </ol>
     * <p>
     * 步骤② 写入后已知 key 的值已变为目标值，自然从步骤③ 的匹配池中消失。
     * 步骤③ 能匹配到的都是步骤② 遗漏的条目——无需事先知道哪些 key 是血量。
     *
     * @param target 目标实体
     * @param health 目标血量值
     */
    public static void setAllHealthLikeRaw(LivingEntity target, float health) {
        if (ENTITY_DATA_ITEMS_FIELD == null || DATA_ITEM_VALUE_FIELD == null) {
            // 反射初始化失败，降级为标准路径
            setAllHealthLikeDirect(target, health);
            return;
        }

        // ① 先快照当前血量（必须在任何修改之前获取）
        // 使用 getHealthDirect 而非 getHealth()：
        // TrueHealthMixin 激活时 getHealth() 返回 Capability 备份（非 DataItem 真实值），
        // 会导致第三步无法匹配被污染的条目。getHealthDirect 始终返回 SynchedEntityData 内的原始值。
        float healthBefore = getHealthDirect(target);

        // ② 先走标准路径 — 确保已知 key 全部写入
        setAllHealthLikeDirect(target, health);

        // 玩家实体在血量维度上只有 DATA_HEALTH_ID（步骤②已写入，匹配池自然消失），
        // 无自定义血量条目——若继续遍历玩家 DataItem 按值匹配，饱食度/吸收/等级等
        // 与血量同量级的同步副本会被单次误写（显示错乱）。跳过整个遍历。
        if (target instanceof Player) return;

        // ③ 原始路径：遍历所有 DataItem，找到值约等于原始血量的条目，
        //    直接写入目标血量值。不依赖 EntityDataAccessor key。
        //    匹配阈值 0.01 — 一个 tick 内血量不会被其他因素改动超过此值。
        SynchedEntityData data = target.getEntityData();
        try {
            @SuppressWarnings("unchecked")
            Map<Integer, Object> items = (Map<Integer, Object>) ENTITY_DATA_ITEMS_FIELD.get(data);
            if (items == null) return;

            for (Object item : items.values()) {
                try {
                    Object rawValue = DATA_ITEM_VALUE_FIELD.get(item);
                    if (rawValue == null) continue;

                    boolean matched;
                    if (rawValue instanceof Float f) {
                        matched = Math.abs(f - healthBefore) < 0.01F;
                        if (matched) DATA_ITEM_VALUE_FIELD.set(item, health);
                    } else if (rawValue instanceof Double d) {
                        matched = Math.abs(d - (double) healthBefore) < 0.01;
                        if (matched) DATA_ITEM_VALUE_FIELD.set(item, Double.valueOf((double) health));
                    } else if (rawValue instanceof Integer i) {
                        // Integer 型血量近似匹配（部分 mod 用 Integer 存百分比血量）
                        matched = Math.abs(i - (int) healthBefore) <= 1;
                        if (matched) DATA_ITEM_VALUE_FIELD.set(item, (int) health);
                    }
                } catch (IllegalAccessException ignored) {
                    // 单个 DataItem 写入失败，继续处理下一个
                }
            }
        } catch (IllegalAccessException | ClassCastException e) {
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
    }

    /**
     * 遍历实体 SynchedEntityData 中<b>所有</b> float 类型条目并归零
     * （砧板之刃[神]同款招式）。
     * <p>
     * 与 {@link #setAllHealthLikeRaw} 的值匹配策略不同，本方法不依赖血量快照、
     * 不依赖已知 key——无论 Boss 把血量/无敌值藏在哪个 float 同步条目
     * （animation/size 等旁路 DataItem），一律清零，从 synched data 通道
     * 彻底断掉隐藏血量。
     * <p>
     * 仅用于必杀场景（影杀饱和式秒杀）。副作用：目标的缩放/动画等 float
     * 同步数据一并归零——目标即将被删除，无实际影响。
     * <p>
     * 与 {@link #clearNegativeFloatDeltas} 的区别：后者只清负值 Float（防御侧，
     * 防止误伤 Boss 正常 float 数据）；本方法清全部 float（攻击侧必杀，无保留）。
     *
     * @param target 目标实体（即将被删除的必杀对象）
     */
    public static void zeroAllSynchedFloats(LivingEntity target) {
        // Player 守卫：与 setAllHealthLikeRaw 对称——玩家的饱食度/吸收等 float 同步条目
        // 会被误清（血量归零是必杀本意，饱食度/吸收是纯误伤）。调用方须自行排除玩家。
        if (target instanceof Player) return;
        if (ENTITY_DATA_ITEMS_FIELD == null || DATA_ITEM_VALUE_FIELD == null) return;
        try {
            SynchedEntityData data = target.getEntityData();
            @SuppressWarnings("unchecked")
            Map<Integer, Object> items = (Map<Integer, Object>) ENTITY_DATA_ITEMS_FIELD.get(data);
            if (items == null) return;
            for (Object item : items.values()) {
                try {
                    Object rawValue = DATA_ITEM_VALUE_FIELD.get(item);
                    if (rawValue instanceof Float) {
                        DATA_ITEM_VALUE_FIELD.set(item, 0.0F);
                    }
                } catch (IllegalAccessException ignored) {
                    // 单个 DataItem 写入失败，继续处理下一个
                }
            }
        } catch (IllegalAccessException | ClassCastException e) {
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
    }

    /**
     * 清除目标实体 SynchedEntityData 中所有负值 Float 型 DataItem（归零）。
     *
     * <h3>用途</h3>
     * 部分外部 Boss（如终焉秩序维系者）通过独立的 {@code EntityDataAccessor&lt;Float&gt;}
     * 维护血量 delta/偏移值——以负值 Float 形式注入到<b>玩家</b>的 SynchedEntityData 中，
     * 再通过 ASM 篡改 {@code getHealth()} 返回值来持续压制玩家血量。
     * <p>
     * 这些 delta 条目不在玩家类层次中（{@link #scanCustomHealthKeys} 无法发现），
     * 且值为负数，不受 {@link #setAllHealthLikeRaw} 的值匹配逻辑影响
     * （负值与正常血量差距远超过匹配阈值 0.01）。
     * <p>
     * 本方法遍历所有 DataItem，将值为负数的 Float 条目归零——
     * 在每次血量修复后调用，确保恶意 delta 被清除，不会累积压制。
     *
     * <h3>调用方</h3>
     * 仅供 TrueHealth 防御侧使用（目标为玩家自身）。
     * 攻击侧（淬魂/影杀/审判/破敌之眼等）<b>不应</b>调用此方法——
     * Boss 的负值 Float DataItem 通常与血量压制无关，清零反而可能误伤。
     *
     * @param target 目标实体（通常为玩家自身）
     */
    public static void clearNegativeFloatDeltas(LivingEntity target) {
        if (ENTITY_DATA_ITEMS_FIELD == null || DATA_ITEM_VALUE_FIELD == null) {
            return;
        }
        SynchedEntityData data = target.getEntityData();
        try {
            @SuppressWarnings("unchecked")
            Map<Integer, Object> items = (Map<Integer, Object>) ENTITY_DATA_ITEMS_FIELD.get(data);
            if (items == null) return;

            for (Object item : items.values()) {
                try {
                    Object rawValue = DATA_ITEM_VALUE_FIELD.get(item);
                    if (rawValue instanceof Float f && f < -0.01F) {
                        DATA_ITEM_VALUE_FIELD.set(item, 0.0F);
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        } catch (IllegalAccessException | ClassCastException e) {
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
    }

    /**
     * 扫描实体类层次中所有 Float 类型的自定义血量 accessor。
     * <p>
     * 不依赖字段名（无法预测 Boss 使用的混淆字段名），
     * 纯粹通过运行时类型检查定位自定义血量 key。
     * <p>
     * <b>参照血量选择（架空判断）</b>：默认用原版血条 {@link #getHealthDirect} 作参照，
     * 排除值不像血量的 Float（如史维特 {@code DATA_WATER_DAMAGE_SCALE} 值 0-1，误写入触发
     * onSyncedDataUpdated -> Slime.move -> 卡死）。但部分 Boss（如亚波伦）把真实血量存在
     * 自定义 EntityDataAccessor 里，重写 getHealth 返回该值、setHealth 不调 super --
     * 原版血条被架空（停在初始值不动），与自定义血量脱钩。此时若仍用原版血条作参照，
     * 自定义血量值（几百~几万）与架空的原版值差距巨大，10% 容差内无法命中，扫不到。
     * <p>
     * 因此先比较 {@code target.getHealth()} 与 {@code getHealthDirect(target)}：差值超过 1.0
     * 即判定原版血条被架空，改用 {@code target.getHealth()}（即被重写返回的真实血量）作参照。
     * 此时自定义血量 accessor 的值 ≈ 参照值（亚波伦的 getHealth 恰返回该 accessor 值），
     * 必然命中，且与血量数值大小无关。负值 delta（如终焉秩序维系者注入的负值 Float）
     * 远离正数参照，仍被容差自然排除。
     */
    private static Set<EntityDataAccessor<Float>> scanCustomHealthKeys(LivingEntity target) {
        Set<EntityDataAccessor<Float>> keys = new LinkedHashSet<>();
        net.minecraft.network.syncher.SynchedEntityData data = target.getEntityData();
        // 参照血量：统一走架空参照读数入口（原版血条被架空时取 getHealth() 真实血量，
        // 否则用原版 DataItem 值防 ASM 篡改）——与所有敌方目标侧读数判定保持一致，
        // 避免判定规则改一处漏一处
        float currentHealth = getEffectiveHealth(target);

        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            // Player 类声明的 EntityDataAccessor 全部是非血量同步字段
            // （饱食度 DATA_PLAYER_SATURATION / 吸收 DATA_PLAYER_ABSORPTION / 等级等，
            //  与血量同量级同范围——值域容差无法区分，只能按类身份排除）。
            // 血量 key 定义在 LivingEntity（DATA_HEALTH_ID），由上方单独排除；
            // 若不排除，血量≈饱食度/吸收时会把这些 key 永久缓存为"自定义血量"，
            // 之后所有对玩家的直写都会把饱食度/吸收写成血量值（显示错乱）。
            if (current == Player.class) {
                current = current.getSuperclass();
                continue;
            }
            for (Field field : current.getDeclaredFields()) {
                // 只关注 static EntityDataAccessor 字段
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;

                field.setAccessible(true);
                try {
                    Object raw = field.get(null);
                    if (!(raw instanceof EntityDataAccessor<?> accessor)) continue;
                    // 排除已知的原版血量 key（由 setHealthDirect 单独处理）
                    if (DATA_HEALTH_ID != null && accessor == DATA_HEALTH_ID) continue;

                    // 验证值为 Float 类型且约等于当前血量，排除非血量 Float
                    // （如天境史维特的 DATA_WATER_DAMAGE_SCALE 值 0-1，非血量，
                    //  误写入会触发 onSyncedDataUpdated -> Slime.move -> 碰撞计算卡死）
                    try {
                        Object value = data.get(accessor);
                        if (value instanceof Float f
                            && Math.abs(f - currentHealth) <= Math.max(1.0F, currentHealth * 0.1F)) {
                            keys.add((EntityDataAccessor<Float>) accessor);
                        }
                    } catch (Exception e) {
                        // 该 accessor 未在本实体上注册，跳过
                    }
                } catch (IllegalAccessException ignored) {
                    // 字段访问失败，跳过
                }
            }
            current = current.getSuperclass();
        }
        return keys;
    }

    private static Method ENTITY_REMOVE_METHOD;

    /**
     * 反射调用 {@code Entity.remove(RemovalReason)}，绕过一切覆写。
     * <p>
     * 用途：Boss 覆写 {@code remove()} 用 {@code isDeadOrDying()} 拦截移除
     * （如终焉秩序维系者尾杀期间、HertaEntity 的完全免疫）。
     * 此方法跳过所有覆写链，直达原版 {@code Entity} 的实现。
     * <p>
     * 注意：这会使实体直接进入待移除队列，不触发任何死亡动画或事件。
     * 调用方应在调用前自行处理掉落物、经验等。
     *
     * @param target 目标实体
     * @param reason 移除原因（通常为 {@code KILLED}）
     */
    public static void removeDirect(LivingEntity target, Entity.RemovalReason reason) {
        try {
            if (ENTITY_REMOVE_METHOD == null) {
                try {
                    ENTITY_REMOVE_METHOD = Entity.class.getDeclaredMethod("m_142687_", Entity.RemovalReason.class);
                } catch (NoSuchMethodException e) {
                    ENTITY_REMOVE_METHOD = Entity.class.getDeclaredMethod("remove", Entity.RemovalReason.class);
                }
                ENTITY_REMOVE_METHOD.setAccessible(true);
            }
            ENTITY_REMOVE_METHOD.invoke(target, reason);
        } catch (Exception e) {
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
    }

    private static Method ENTITY_SET_REMOVED_METHOD;

    /**
     * 反射调用 {@code Entity.setRemoved(RemovalReason)} (SRG: {@code m_142467_})，
     * 绕过一切覆写。
     * <p>
     * {@code setRemoved} 是 private 方法，不参与虚方法分派，
     * Boss 重写 {@code remove()} 无法拦截此调用。
     * 这是实体移除链路中最底层的一环——直接写入内部状态标记实体已移除。
     * <p>
     * <b>局限性</b>：若目标实体通过 Mixin 注入 {@code setRemoved()} 并 cancel（如终焉秩序维系者），
     * 此方法仍会被拦截。此时应使用 {@link #setRemovedFieldDirect(LivingEntity, Entity.RemovalReason)} 字段直写。
     * <p>
     * 调用方应在调用前自行处理掉落物、经验、死亡通告等。
     *
     * @param target 目标实体
     * @param reason 移除原因（通常为 {@code KILLED}）
     */
    public static void setRemovedDirect(LivingEntity target, Entity.RemovalReason reason) {
        try {
            if (ENTITY_SET_REMOVED_METHOD == null) {
                try {
                    ENTITY_SET_REMOVED_METHOD = Entity.class.getDeclaredMethod("m_142467_", Entity.RemovalReason.class);
                } catch (NoSuchMethodException e) {
                    ENTITY_SET_REMOVED_METHOD = Entity.class.getDeclaredMethod("setRemoved", Entity.RemovalReason.class);
                }
                ENTITY_SET_REMOVED_METHOD.setAccessible(true);
            }
            ENTITY_SET_REMOVED_METHOD.invoke(target, reason);
        } catch (Exception e) {
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
    }

    private static Field ENTITY_REMOVAL_REASON_FIELD;

    /**
     * 清除 {@code Entity.removalReason} 字段，将实体从"已移除"状态恢复到正常状态。
     * <p>
     * 适用场景：存活性自检（liveness check）发现实体被外部通过字段直写标记为已移除，
     * 但 Capability 备份表明玩家应存活时，调用此方法撤销移除标记。
     * <p>
     * 与 {@link #setRemovedFieldDirect} 的不同在于本方法写入 {@code null}，
     * 等价于"从未被移除"的初始状态。
     *
     * @param target 目标实体
     */
    public static void clearRemovedFlag(LivingEntity target) {
        try {
            if (ENTITY_REMOVAL_REASON_FIELD == null) {
                try {
                    ENTITY_REMOVAL_REASON_FIELD = Entity.class.getDeclaredField("f_146795_");
                } catch (NoSuchFieldException e) {
                    ENTITY_REMOVAL_REASON_FIELD = Entity.class.getDeclaredField("removalReason");
                }
                ENTITY_REMOVAL_REASON_FIELD.setAccessible(true);
            }
            ENTITY_REMOVAL_REASON_FIELD.set(target, null);
        } catch (Exception e) {
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
    }

    // ==================== 世界内部结构抹除（饱和式秒杀最终手段） ====================

    // --- ServerLevel fields ---
    private static final Field SL_ENTITY_MANAGER = reflectField(
        ServerLevel.class, "f_143244_", "entityManager");
    private static final Field SL_ENTITY_TICK_LIST = reflectField(
        ServerLevel.class, "f_143243_", "entityTickList");
    private static final Field SL_DRAGON_PARTS = reflectField(
        ServerLevel.class, "f_143247_", "dragonParts");
    private static final Field SL_PLAYERS_FIELD = reflectField(
        ServerLevel.class, "f_8546_", "players");

    // --- SectionPos.asLong(BlockPos) : long ---
    private static final Method SP_AS_LONG = reflectMethod(
        SectionPos.class, "m_175568_", "asLong", BlockPos.class);

    // --- ServerChunkCache.removeEntity(Entity) ---
    private static final Method SCC_REMOVE_ENTITY = reflectMethod(
        ServerChunkCache.class, "m_8443_", "removeEntity", Entity.class);

    // --- Internal classes (PersistentEntitySectionManager / EntityLookup / EntitySection / ClassInstanceMultiMap / EntitySectionStorage / EntityTickList) ---
    private static final Field ESM_VISIBLE_ENTITY_STORAGE;
    private static final Field ESM_KNOWN_UUIDS;
    private static final Field ESM_SECTION_STORAGE;
    private static final Field EL_BY_ID;
    private static final Field EL_BY_UUID;
    private static final Field ES_CLASS_INSTANCE_MULTIMAP;
    private static final Field CIMM_ALL_INSTANCES;
    private static final Field CIMM_BY_CLASS;
    private static final Method ESS_GET_SECTION;
    private static final Field ETL_ACTIVE;
    private static final Field ETL_PASSIVE;


    static {
        Field elu = null, ku = null, ss = null;
        Field bi = null, bu = null;
        Field cmm = null, ci = null, cb = null;
        Method gs = null;
        Field ea = null, ep = null;
        try {
            Class<?> esmClz = Class.forName("net.minecraft.world.level.entity.PersistentEntitySectionManager");
            elu = reflectField(esmClz, "f_157494_", "visibleEntityStorage");
            ku  = reflectField(esmClz, "f_157491_", "knownUuids");
            ss  = reflectField(esmClz, "f_157495_", "sectionStorage");

            Class<?> elClz = Class.forName("net.minecraft.world.level.entity.EntityLookup");
            bi = reflectField(elClz, "f_156807_", "byId");
            bu = reflectField(elClz, "f_156808_", "byUuid");

            Class<?> esClz = Class.forName("net.minecraft.world.level.entity.EntitySection");
            cmm = reflectField(esClz, "f_156827_", "storage");

            Class<?> cmmClz = Class.forName("net.minecraft.util.ClassInstanceMultiMap");
            ci = reflectField(cmmClz, "f_13529_", "allInstances");
            cb = reflectField(cmmClz, "f_13527_", "byClass");

            Class<?> essClz = Class.forName("net.minecraft.world.level.entity.EntitySectionStorage");
            gs = reflectMethod(essClz, "m_156895_", "getSection", long.class);

            Class<?> etlClz = Class.forName("net.minecraft.world.level.entity.EntityTickList");
            ea = reflectField(etlClz, "f_156903_", "active");
            ep = reflectField(etlClz, "f_156904_", "passive");

        } catch (ClassNotFoundException e) {
            System.err.println("[AdventurePower] HealthUtil: 内部类反射初始化失败，eradicateFromWorld 将不可用");
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
        ESM_VISIBLE_ENTITY_STORAGE = elu;
        ESM_KNOWN_UUIDS = ku;
        ESM_SECTION_STORAGE = ss;
        EL_BY_ID = bi;
        EL_BY_UUID = bu;
        ES_CLASS_INSTANCE_MULTIMAP = cmm;
        CIMM_ALL_INSTANCES = ci;
        CIMM_BY_CLASS = cb;
        ESS_GET_SECTION = gs;
        ETL_ACTIVE = ea;
        ETL_PASSIVE = ep;
    }

    /**
     * 反射获取字段，先试 SRG 名再试 MCP 名（已 setAccessible）。
     * <p>
     * 全模组统一的字段反射入口：CombatAbilityHandler（hurtTime/deathScore）、
     * ShadowKillHelper 等复用，避免各自内联 try-SRG-catch-MCP 重复模式。
     * 失败返回 null（不抛异常）。
     *
     * @param clz 目标类
     * @param srg SRG 名（生产环境）
     * @param mcp MCP 开发环境回退名
     * @return 已 setAccessible 的 Field；失败返回 null
     */
    public static Field reflectField(Class<?> clz, String srg, String mcp) {
        try {
            Field f = clz.getDeclaredField(srg);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            try {
                Field f = clz.getDeclaredField(mcp);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ex) {
                return null;
            }
        }
    }

    /**
     * 反射获取方法，先试 SRG 名再试 MCP 名（已 setAccessible）。
     * 与 {@link #reflectField} 同模式，失败返回 null。
     *
     * @param clz    目标类
     * @param srg    SRG 名（生产环境）
     * @param mcp    MCP 开发环境回退名
     * @param params 方法参数类型
     * @return 已 setAccessible 的 Method；失败返回 null
     */
    public static Method reflectMethod(Class<?> clz, String srg, String mcp, Class<?>... params) {
        try {
            Method m = clz.getDeclaredMethod(srg, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            try {
                Method m = clz.getDeclaredMethod(mcp, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }
    }

    /**
     * 从 Minecraft 世界内部数据结构中直接抹除实体 —— 饱和式秒杀的最终手段。
     *
     * <h3>原理</h3>
     * 不依赖 {@code remove()/setRemoved()/die()/hurt()} 等会被 Boss 拦截的方法，
     * 直接操作 Minecraft 内部的实体追踪结构：
     * <ol>
     *   <li><b>EntityLookup.byId</b> (Int2ObjectMap) —— 按 ID 移除</li>
     *   <li><b>EntityLookup.byUuid</b> (Map) —— 按 UUID 移除</li>
     *   <li><b>knownUuids</b> (Set) —— 移除 UUID 注册</li>
     *   <li><b>EntityTickList</b> —— 从 tick 队列中移除</li>
     *   <li><b>EntitySection.classInstanceMultiMap</b> —— 从 allInstances 及 per-class byClass 分列表中移除</li>
     *   <li><b>ServerChunkCache.removeEntity</b> —— 通知区块系统</li>
     * </ol>
     * <p>
     * 以上 6 步完成后，实体在服务端所有追踪结构中彻底消失——即使其
     * {@code isRemoved()} 返回 false，tick 系统也无法找到它。
     *
     * <h3>调用约定</h3>
     * 调用方应在调用前自行处理掉落物、经验、死亡事件（{@code LivingDeathEvent}）等。
     * 本方法是纯粹的"从世界中抹除"操作，不触发任何游戏逻辑。
     *
     * <h3>容错</h3>
     * 每个步骤独立 try-catch，单点失败不影响其他步骤。整体外层也捕获异常，
     * 确保反射失败不会中断主流程。
     *
     * @param target 需要从世界中抹除的实体
     */
    @SuppressWarnings("unchecked")
    public static void eradicateFromWorld(LivingEntity target) {
        if (!(target.level() instanceof ServerLevel sl)) return;
        if (SL_ENTITY_MANAGER == null) return;

        int entityId = target.getId();
        UUID entityUuid = target.getUUID();

        try {
            // ① 获取 PersistentEntitySectionManager
            Object esm = SL_ENTITY_MANAGER.get(sl);
            if (esm == null) return;

            // ② EntityLookup.byId / byUuid
            if (ESM_VISIBLE_ENTITY_STORAGE != null) {
                Object visibleEntityStorage = ESM_VISIBLE_ENTITY_STORAGE.get(esm);
                if (visibleEntityStorage != null) {
                    if (EL_BY_ID != null) {
                        try {
                            Object byId = EL_BY_ID.get(visibleEntityStorage);
                            if (byId instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap) {
                                ((it.unimi.dsi.fastutil.ints.Int2ObjectMap<Object>) byId).remove(entityId);
                            }
                        } catch (IllegalAccessException ignored) {}
                    }
                    if (EL_BY_UUID != null) {
                        try {
                            Object byUuid = EL_BY_UUID.get(visibleEntityStorage);
                            if (byUuid instanceof Map) {
                                ((Map<?, ?>) byUuid).remove(entityUuid);
                            }
                        } catch (IllegalAccessException ignored) {}
                    }
                }
            }

            // ③ knownUuids
            if (ESM_KNOWN_UUIDS != null) {
                try {
                    Object knownUuids = ESM_KNOWN_UUIDS.get(esm);
                    if (knownUuids instanceof Set) {
                        ((Set<?>) knownUuids).remove(entityUuid);
                    }
                } catch (IllegalAccessException ignored) {}
            }

            // ④ EntityTickList — 直拿内部 active(Int2ObjectMap)/passive(List)，绕过 Mixin 拦截
            if (SL_ENTITY_TICK_LIST != null) {
                try {
                    Object tickList = SL_ENTITY_TICK_LIST.get(sl);
                    if (tickList != null) {
                        if (ETL_ACTIVE != null) {
                            try {
                                Object active = ETL_ACTIVE.get(tickList);
                                // active 实际是 Int2ObjectMap<Entity>，不是 List
                                if (active instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap) {
                                    ((it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) active).remove(entityId);
                                } else if (active instanceof List) {
                                    ((List<?>) active).remove(target);
                                }
                            } catch (IllegalAccessException ignored) {}
                        }
                        if (ETL_PASSIVE != null) {
                            try {
                                Object passive = ETL_PASSIVE.get(tickList);
                                if (passive instanceof List) {
                                    ((List<?>) passive).remove(target);
                                }
                            } catch (IllegalAccessException ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }

            // ⑤ EntitySection.classInstanceMultiMap.allInstances
            if (ESM_SECTION_STORAGE != null && ESS_GET_SECTION != null
                && ES_CLASS_INSTANCE_MULTIMAP != null && CIMM_ALL_INSTANCES != null && SP_AS_LONG != null) {
                try {
                    Object sectionStorage = ESM_SECTION_STORAGE.get(esm);
                    if (sectionStorage != null) {
                        BlockPos pos = target.blockPosition();
                        long sectionKey = (long) SP_AS_LONG.invoke(null, pos);
                        Object section = ESS_GET_SECTION.invoke(sectionStorage, sectionKey);
                        if (section != null) {
                            Object cmm = ES_CLASS_INSTANCE_MULTIMAP.get(section);
                            if (cmm != null) {
                                Object allInstances = CIMM_ALL_INSTANCES.get(cmm);
                                if (allInstances instanceof List) {
                                    ((List<?>) allInstances).remove(target);
                                }
                                // 也清理 per-class 分列表（byClass），防止按类型查询时残留
                                if (CIMM_BY_CLASS != null) {
                                    try {
                                        Object byClass = CIMM_BY_CLASS.get(cmm);
                                        if (byClass instanceof Map) {
                                            for (Object list : ((Map<?, ?>) byClass).values()) {
                                                if (list instanceof List) {
                                                    ((List<?>) list).remove(target);
                                                }
                                            }
                                        }
                                    } catch (IllegalAccessException ignored) {}
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // ⑥ ServerChunkCache.removeEntity(entity)
            if (SCC_REMOVE_ENTITY != null) {
                try {
                    SCC_REMOVE_ENTITY.invoke(sl.getChunkSource(), target);
                } catch (Exception ignored) {}
            }

            // ⑦ 龙部件清理 — 末影龙被秒杀时 part 不走原版死亡流程，dragonParts
            //     （Int2ObjectMap<part.id, EnderDragonPart>）中的 part 会持续引用死龙并存活于世界。
            //     part 不注册进 ESM（不在 EntityLookup/EntitySection），按 part.id 从表摘除
            //     即完成清理（对应原版 ServerLevel$EntityCallbacks.onTickingEnd）；再写
            //     removalReason 作保险带（isRemoved() 为真），防止按 id 查询残留。
            if (SL_DRAGON_PARTS != null && target instanceof EnderDragon) {
                try {
                    Object parts = SL_DRAGON_PARTS.get(sl);
                    if (parts instanceof Map<?, ?> m) {
                        for (Object o : m.values().toArray()) {
                            if (o instanceof EnderDragonPart part && part.parentMob == target) {
                                m.remove(part.getId());
                                writeRemovalReasonDirect(part, Entity.RemovalReason.KILLED);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {
            // 静默处理 —— 这是最终兜底手段，不应因反射失败而中断主流程
        }
    }


    // ==================== 饱食度直写 ====================

    private static final Field FOOD_LEVEL_FIELD = reflectField(
        FoodData.class, "f_38696_", "foodLevel");
    private static final Field SATURATION_FIELD = reflectField(
        FoodData.class, "f_38697_", "saturationLevel");

    /**
     * 直写 FoodData 字段将饱食度和饱和度设满。
     * <p>
     * 不使用 {@code setFoodLevel()/setSaturation()} 等公共方法，
     * 因为理论上可被 Mixin 拦截。直接反射写入 {@code foodLevel} 和
     * {@code saturationLevel} 字段，无 Forge 事件、无方法覆写风险。
     */
    public static void restoreFoodData(Player player) {
        FoodData fd = player.getFoodData();
        if (FOOD_LEVEL_FIELD != null) {
            try {
                FOOD_LEVEL_FIELD.setInt(fd, 20);
            } catch (IllegalAccessException ignored) {}
        }
        if (SATURATION_FIELD != null) {
            try {
                SATURATION_FIELD.setFloat(fd, 20.0F);
            } catch (IllegalAccessException ignored) {}
        }
    }

    /**
     * 直接反射写入 {@code Entity.removalReason} 字段 (SRG: {@code f_146795_})，
     * 绕过<b>所有</b>方法调用——包括 Mixin 注入和子类覆写。
     * <p>
     * <b>为什么这能穿透一切拦截：</b>
     * <ul>
     *   <li>{@link #setRemovedDirect} 反射调用 {@code setRemoved()} 方法，但 Mixin 在类加载时
     *       修改了该方法的字节码，反射调用依然经过被修改的字节码，仍可被 {@code ci.cancel()} 拦截</li>
     *   <li>字段直写则完全不经过任何方法——JVM 通过 {@code Unsafe.putObject} 直接写入堆内存偏移量，
     *       没有任何 Mixin、Override、CoreMod 能拦截字段写入指令</li>
     * </ul>
     * <p>
     * <b>风险</b>：此操作极端暴力，跳过了所有游戏逻辑（死亡动画、事件触发、状态清理等）。
     * 调用方<b>必须</b>在调用前自行处理掉落物、经验、死亡通告 ({@code LivingDeathEvent})、
     * 骑乘解除等善后工作。
     * <p>
     * 建议仅在确认其他移除链全部失效时使用（如终焉秩序维系者等通过 Mixin 拦截
     * {@code setRemoved()} 的 Boss）。正常场景下应使用 {@link #setRemovedDirect}。
     *
     * @param target 目标实体
     * @param reason 移除原因（通常为 {@code KILLED}）
     */
    public static void setRemovedFieldDirect(LivingEntity target, Entity.RemovalReason reason) {
        writeRemovalReasonDirect(target, reason);
    }

    /**
     * 直接反射写入任意 {@code Entity} 的 {@code removalReason} 字段 (SRG: {@code f_146795_})，
     * 绕过<b>所有</b>方法调用——包括 Mixin 注入和子类覆写。
     * <p>
     * 与 {@link #setRemovedFieldDirect} 相同，但参数泛化为 {@code Entity}，
     * 供非 LivingEntity（如 {@code EnderDragonPart}）共用。
     *
     * @param target 目标实体
     * @param reason 移除原因（通常为 {@code KILLED}）
     */
    public static void writeRemovalReasonDirect(Entity target, Entity.RemovalReason reason) {
        try {
            if (ENTITY_REMOVAL_REASON_FIELD == null) {
                try {
                    ENTITY_REMOVAL_REASON_FIELD = Entity.class.getDeclaredField("f_146795_");
                } catch (NoSuchFieldException e) {
                    ENTITY_REMOVAL_REASON_FIELD = Entity.class.getDeclaredField("removalReason");
                }
                ENTITY_REMOVAL_REASON_FIELD.setAccessible(true);
            }
            ENTITY_REMOVAL_REASON_FIELD.set(target, reason);
        } catch (Exception e) {
            LOGGER.error("[HealthUtil] 反射/内部操作失败", e);
        }
    }
}
