package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.probe.BloodWriteEngine;
import com.ayin90723.adventure_power.util.probe.ProbeScales;
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

    /** 反射不可用降级的一次性告警标记（v1.4.0）：避免每次调用刷日志 */
    private static volatile boolean degradeWarned = false;

    /** 反射不可用降级告警（一次性；相关直读/直写降级为原版路径，防御能力受限） */
    private static void warnDegrade(String reason) {
        if (!degradeWarned) {
            degradeWarned = true;
            LOGGER.error("[HealthUtil] {}——直读/直写降级为原版路径，防御能力受限", reason);
        }
    }

    static {
        try {
            DATA_HEALTH_ID_FIELD = LivingEntity.class.getDeclaredField("f_20961_");
        } catch (NoSuchFieldException e) {
            try {
                DATA_HEALTH_ID_FIELD = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
            } catch (NoSuchFieldException ex) {
                LOGGER.error("[HealthUtil] 无法反射获取 DATA_HEALTH_ID 字段，setHealthDirect 将不可用", ex);
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
            warnDegrade("DATA_HEALTH_ID 反射不可用");
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
                warnDegrade("DATA_HEALTH_ID 反射不可用");
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

    /** 实体上的 {@code SynchedEntityData} 字段（声明在 Entity 类，SRG f_19804_——getDeclaredField 必须从声明类起查），DataItem 槽插针的路径起点。 */
    private static Field ENTITY_DATA_FIELD;

    static {
        try {
            ENTITY_DATA_ITEMS_FIELD = SynchedEntityData.class.getDeclaredField("f_135345_");
        } catch (NoSuchFieldException e) {
            try {
                ENTITY_DATA_ITEMS_FIELD = SynchedEntityData.class.getDeclaredField("itemsById");
            } catch (NoSuchFieldException ex) {
                LOGGER.error("[HealthUtil] 无法反射获取 SynchedEntityData.itemsById 字段", ex);
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
            LOGGER.error("[HealthUtil] 无法反射获取 DataItem.value 字段", e);
        }
        if (DATA_ITEM_VALUE_FIELD != null) {
            DATA_ITEM_VALUE_FIELD.setAccessible(true);
        }

        try {
            // entityData 声明在 Entity 类（非 LivingEntity）——getDeclaredField 不沿父类链，
            // 必须从 Entity 起找（v1.4.2 实测：用 LivingEntity.class 查 f_19804_/entityData 双失败，
            // 槽插针静默失效，泽林改血无效）
            Class<?> entityClazz = net.minecraft.world.entity.Entity.class;
            try {
                ENTITY_DATA_FIELD = entityClazz.getDeclaredField("f_19804_");
            } catch (NoSuchFieldException e) {
                ENTITY_DATA_FIELD = entityClazz.getDeclaredField("entityData");
            }
        } catch (NoSuchFieldException e) {
            LOGGER.error("[HealthUtil] 无法反射获取 Entity.entityData 字段", e);
        }
        if (ENTITY_DATA_FIELD != null) {
            ENTITY_DATA_FIELD.setAccessible(true);
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
        Class<?> targetClass = target.getClass();
        // L1 负缓存（v1.4.2）：本类无命中方法 → 跳过全扫（getDeclaredMethods 每次分配 Method[] 拷贝）。
        // 级联失效时由 BloodWriteEngine 统一清空（正缓存漂移=对方形态变化，负结论同步作废）
        if (GENERIC_NO_HIT.contains(targetClass)) return false;
        // v1.4.0 审查修复：按类缓存命中的 Method——原实现每次调用都
        // getDeclaredMethods()（分配 Method[] 拷贝）+ 逐个 invoke 验证，
        // 禁疗目标的每次回血尝试（regen 约每 20 tick）都全量扫描。
        // 缓存命中仍走验证闭环（不通过则清缓存回退全扫，行为等价）
        java.lang.reflect.Method cached = GENERIC_WRITE_CACHE.get(targetClass);
        // 量纲口径：ε 基于当前读数（子代理审查修：原用目标值 epsilon(health)，磨血时两者差 ≤ ulp 等价，
        // 但规范定义为读数口径，统一）
        float driftTol = ProbeScales.driftTolerance(ProbeScales.epsilon(target.getHealth()));
        if (cached != null) {
            try {
                cached.invoke(target, health);
                if (Math.abs(getEffectiveHealth(target) - health) < driftTol) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            GENERIC_WRITE_CACHE.remove(targetClass);
            BloodWriteEngine.onPositiveCacheDrift();
        }
        for (java.lang.reflect.Method m : targetClass.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != float.class) continue;
            if (!m.getName().toLowerCase().contains("health")) continue;
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            try {
                m.setAccessible(true);
                m.invoke(target, health);
                if (Math.abs(getEffectiveHealth(target) - health) < driftTol) {
                    DebugLog.probe("[通用直写] 命中 {}: {} → {}", m.getName(), target, health);
                    GENERIC_WRITE_CACHE.put(targetClass, m);
                    GENERIC_NO_HIT.remove(targetClass);
                    return true;
                }
            } catch (Exception ignored) {
                // 调用失败（访问控制/类型不符）→ 继续下一候选
            }
        }
        GENERIC_NO_HIT.add(targetClass);
        return false;
    }

    /** 通用层直写的方法缓存：实体类 → 命中的 (F)V Health 写入方法（验证闭环筛选）。 */
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> GENERIC_WRITE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 通用层负缓存（v1.4.2）：本类确认无命中的 Health 写方法，跳过全扫；级联失效时统一清空。 */
    private static final java.util.Set<Class<?>> GENERIC_NO_HIT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 级联失效入口（BloodWriteEngine 调用）：任意正缓存漂移 → 通用层负缓存 + 对象图超预算封存全部作废。 */
    public static void invalidateGenericNegativeCache() {
        GENERIC_NO_HIT.clear();
        GRAPH_OVERWHELMED.clear();
    }

    /**
     * 插针命中缓存：<b>按实例</b>（弱引用）缓存真血字段写入通路（字段 + 从实体到宿主对象的引用路径链）。
     * <p>
     * v1.4.0 审查修复：原先按实体类缓存，但路径链含实例相关的 Map key / Collection index——
     * 同类多实例（召唤物/分身群的 Boss）互相作废缓存（第二个实例解析失败 → 清缓存 →
     * 全图重探测 → 新缓存又被第三个实例作废），成群同类 Boss 的每次直写都退化为
     * 全对象图插针。改弱 key 按实例缓存：每实例首次各探测一次，之后零开销互不干扰；
     * 实体死亡/卸载后条目随弱引用自动清理。写值前的字段值验证保留（路径失效仍回退全图重探测）。
     */
    private static final java.util.Map<LivingEntity, WritePath> CAP_WRITE_CACHE =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 真血字段写入通路：字段 + 从实体根对象到字段宿主的步骤链。 */
    private static final class WritePath {
        final java.lang.reflect.Field field;
        final java.util.List<Object> steps; // Field=对象字段；其余=Map key / Collection index（按当前节点类型解释）
        /** true=反向存储（承伤累计：血量下降=字段值上升），写值需换算 orig+(reading−targetValue)。 */
        final boolean reverse;

        WritePath(java.lang.reflect.Field field, java.util.List<Object> steps, boolean reverse) {
            this.field = field;
            this.steps = steps;
            this.reverse = reverse;
        }
    }

    /**
     * DataItem 联动探针：直写 DATA_HEALTH_ID 槽 value = 原值−ε（绕过 set() 直改 DataItem.value 字段），
     * 观察 {@code getHealth()} 是否联动。
     * <p>
     * 联动 = {@code getHealth()} 真读该槽（正常实体）→ 返回 true，DataItem 直写足够；
     * 不联动 = {@code getHealth()} 被重定向/架空（如亚波伦 ASM 改写读 MegaCapability）→ 返回 false，
     * 必须进插针层找真血存储。扰动同 tick 内还原，客户端无感知。
     * <p>
     * v1.4.2：扰动量与判定容差量纲化（原裸 −1/±1.0 在 ≥16M HP 目标上低于 float ulp
     * 精度地板，探针静默失效）。
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
            float eps = ProbeScales.epsilon(orig);
            DATA_ITEM_VALUE_FIELD.set(item, orig - eps);
            float after;
            try {
                after = target.getHealth();
            } finally {
                DATA_ITEM_VALUE_FIELD.set(item, orig); // 还原
            }
            // 双判据（实测修复）：①读数必须真的变化（防"getHealth 恒定 + 扰动量被容差吞掉"的边界误判
            // ——49360 血改版 UomWither：eps=1.0、driftTol=1.0，未联动的 |after−(orig−eps)|=1.0 ≤ 1.0
            // 曾误判联动，DataItem 通道被误信导致永不降层）②变化指向测试值
            boolean changed = Math.abs(after - orig) >= ProbeScales.verifyThreshold(eps);
            boolean directed = Math.abs(after - (orig - eps)) <= ProbeScales.driftTolerance(eps);
            boolean linked = changed && directed;
            DebugLog.probe("[插针] 门禁: 槽{} 原值={} 扰动后 getHealth={} 变化={} 联动={}",
                DATA_HEALTH_ID.getId(), orig, after, changed, linked);
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
     * 对象图扫描预算中止标记值（v1.4.2 实测修复）：probeGraph 返回 -2 表示
     * 访问对象数超过预算、扫描中止——与 -1（完整扫描未命中）语义区分，
     * 递归调用链需立即传播中止（继续扫无意义）。
     */
    private static final float GRAPH_ABORTED = -2.0F;

    /**
     * 对象图超预算类封存表（v1.4.2 实测修复）：geckolib 动画类等实体的可达图
     * 可达数百万对象（实测泽林变体 597 万，单次全图 4.6 秒），无预算会卡顿数秒。
     * 超预算的类封存全图扫描（DataItem/WritePath 快路径不受影响），级联失效时清空重试。
     */
    private static final java.util.Set<Class<?>> GRAPH_OVERWHELMED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 分级直写结果（供 {@code BloodWriteEngine} 走梯判定）。
     */
    public enum LayerOutcome {
        /** L1 命中：通用 setter 写入成功。 */
        GENERIC_HIT,
        /** L2 命中：对象图插针写入成功。 */
        PROBE_HIT,
        /** DataItem 门禁通过：getHealth 真读原版槽，raw 直写即主通道（tryLayeredWrite 已执行写入）。 */
        DATA_GATE_PASS,
        /** L1+L2 均未命中（tryLayeredWrite 不执行 raw 兜底，由引擎决定兜底时机）。 */
        NO_HIT
    }

    public static LayerOutcome probeCapabilityHealth(LivingEntity target, float targetValue) {
        // 缓存命中：直接走已解明的通路（路径链直达宿主，零全图搜索）
        WritePath cached = CAP_WRITE_CACHE.get(target);
        if (cached != null) {
            try {
                Object owner = resolvePath(target, cached.steps, 0);
                if (owner == null) {
                    CAP_WRITE_CACHE.remove(target);
                    BloodWriteEngine.onPositiveCacheDrift();
                    return probeFresh(target, targetValue);
                }
                // 写值前验证：对象图结构变化可能让路径链指向错误宿主（索引漂移），
                // 校验字段当前值仍接近真血读数（与 probeGraph 值域过滤同容差）；
                // 不匹配视为路径失效 → 缓存作废 + 全图重探测（有验证闭环，不盲写）
                // v1.4.2：兼容 Object 型字段（DataItem.value）+ 反向存储换算
                // 子代理审查修：反向 WritePath 的门禁参照必须用 maxHealth−reading（承伤累计
                // 字段值≈max−reading），用正向参照会恒失效→每击重探+级联风暴
                float cur = cached.field.getType() == Object.class
                    ? ((Number) cached.field.get(owner)).floatValue()
                    : cached.field.getFloat(owner);
                float ref = cached.reverse
                    ? target.getMaxHealth() - target.getHealth()
                    : target.getHealth();
                if (Math.abs(cur - ref) > ProbeScales.gateTolerance(ref)) {
                    CAP_WRITE_CACHE.remove(target);
                    BloodWriteEngine.onPositiveCacheDrift();
                    DebugLog.probe("[插针] 缓存路径失效（字段值 {} ≠ 真血读数 {}），回退全图重探测", cur, ref);
                    return probeFresh(target, targetValue);
                }
                // 写值公式用当前血量 reading 而非门禁参照 ref（回归修复：严重②修复把 ref 改为
                // 反向语义 max−reading 后，写值沿用 ref 变成 away+(away−targetValue) 双倍偏移，
                // 泽林实测"一刀残两刀杀"）。反向语义正确公式：away_new = away + (reading − targetValue)。
                float reading = target.getHealth();
                if (cached.field.getType() == Object.class) {
                    float writeVal = cached.reverse ? cur + (reading - targetValue) : targetValue;
                    cached.field.set(owner, writeVal);
                } else if (cached.reverse) {
                    cached.field.setFloat(owner, cur + (reading - targetValue));
                } else {
                    cached.field.setFloat(owner, targetValue);
                }
                return LayerOutcome.PROBE_HIT;
            } catch (Exception e) {
                // 路径失效（对象图结构变化/字段不可访问）→ 缓存作废 + 全图重探测
                CAP_WRITE_CACHE.remove(target);
                BloodWriteEngine.onPositiveCacheDrift();
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
    private static LayerOutcome probeFresh(LivingEntity target, float targetValue) {
        // 门禁：DataItem 扰动后 getHealth 联动 = 正常实体（getHealth 真读槽 9），
        // DataItem 直写足够且更高效，不插针。门禁仅为性能优化，安全性仍由验证闭环保证。
        if (probeDataItemLinked(target)) {
            setAllHealthLikeRaw(target, targetValue);
            // 写后验证（v1.4.2-fix 补）：门禁"部分联动"型 Boss（getHealth = 原版槽 + 自定义槽
            // 合成——原版槽只是分量，如双槽合成血）直通道只写原版槽分量，合成读数 ≠ 目标值；
            // 验证不过则直通道不可信，回落槽插针/对象图（防"血越打越多"的错误成功）
            float after = getEffectiveHealth(target);
            if (Math.abs(after - targetValue) <= ProbeScales.driftTolerance(ProbeScales.epsilon(targetValue))) {
                return LayerOutcome.DATA_GATE_PASS;
            }
            DebugLog.probe("[插针] 门禁直通道写后验证失败（getHealth={} 目标={}，合成血部分联动？），回落槽插针/对象图",
                after, targetValue);
        }
        // DataItem 自定义槽插针（v1.4.2，泽林变体实证）：真血在 SynchedEntityData 自定义
        // Float 槽（如泽林 EXALTED_NORMAL−EXALTED_AWAY 双槽差、承伤累计反向语义）时，
        // 门禁（DATA_HEALTH_ID）联动=false、对象图字段插针摸不到（DataItem.value 是 Object 字段）。
        if (dataItemSlotProbe(target, targetValue)) {
            return LayerOutcome.PROBE_HIT;
        }
        if (probeGraphFull(target, targetValue, false)) {
            return LayerOutcome.PROBE_HIT;
        }
        return LayerOutcome.NO_HIT;
    }

    /**
     * DataItem 自定义槽插针（v1.4.2，泽林变体 VariantZsieinEntity 实证）：
     * 遍历目标 SynchedEntityData 的全部 Float DataItem（除 DATA_HEALTH_ID 主槽），
     * 值闸双向形态分类（正向 value≈reading / 反向 value≈(max−reading) 承伤累计，地板≥1）
     * → 写 ±ε 探针（血量恒下降方向）→ getHealth 联动验证 → 命中写入目标值并缓存
     * WritePath（steps=[entityData 字段, itemsById key]，field=DataItem.value，reverse 标记）。
     * <p>
     * 覆盖形态：getHealth 覆写读自定义槽差（泽林 EXALTED_NORMAL−EXALTED_AWAY）、
     * 槽内反向承伤累计（EXALTED_AWAY）、以及 setAllHealthLikeRaw 按值匹配写不中的
     * 一切自定义 Float 槽血量。验证闭环与 probeGraph 同判据，扰动同栈还原。
     *
     * @return true 表示命中并完成写入
     */
    private static boolean dataItemSlotProbe(LivingEntity target, float targetValue) {
        if (ENTITY_DATA_ITEMS_FIELD == null || DATA_ITEM_VALUE_FIELD == null || ENTITY_DATA_FIELD == null
            || DATA_HEALTH_ID == null) {
            return false;
        }
        try {
            Map<Integer, Object> items = (Map<Integer, Object>) ENTITY_DATA_ITEMS_FIELD.get(target.getEntityData());
            if (items == null) {
                return false;
            }
            float reading = target.getHealth();
            float maxHealth = target.getMaxHealth();
            boolean reverseAllowed = ProbeScales.reverseFloorMet(target, reading);
            float eps = ProbeScales.epsilon(reading);
            float gateTol = ProbeScales.gateTolerance(reading);
            float verifyTh = ProbeScales.verifyThreshold(eps);
            float driftTol = ProbeScales.driftTolerance(eps);
            float reverseRef = maxHealth - reading;
            for (Map.Entry<Integer, Object> e : items.entrySet()) {
                int key = e.getKey();
                if (key == DATA_HEALTH_ID.getId()) continue; // 主槽门禁已判不联动，跳过
                Object item = e.getValue();
                if (item == null) continue;
                Object raw = DATA_ITEM_VALUE_FIELD.get(item);
                if (!(raw instanceof Float orig)) continue;
                // 值闸双向形态分类（正向优先降噪）
                boolean forward = Math.abs(orig - reading) <= gateTol;
                boolean reverse = !forward && reverseAllowed && Math.abs(orig - reverseRef) <= gateTol;
                if (!forward && !reverse) continue;
                boolean isReverse = reverse;
                // 探针：±ε 小扰动（血量恒下降方向）→ 无条件还原
                float probeVal = isReverse ? orig + eps : orig - eps;
                float before = target.getHealth();
                DATA_ITEM_VALUE_FIELD.set(item, probeVal);
                float after;
                try {
                    after = target.getHealth();
                } finally {
                    DATA_ITEM_VALUE_FIELD.set(item, orig);
                }
                if (Math.abs(after - before) < verifyTh) continue;
                float expected = isReverse ? reading - eps : orig - eps;
                if (Math.abs(after - expected) <= driftTol) {
                    // 命中：写入目标值并缓存通路（entityData 字段 → itemsById key → DataItem.value）
                    float writeVal = isReverse ? orig + (reading - targetValue) : targetValue;
                    DATA_ITEM_VALUE_FIELD.set(item, writeVal);
                    java.util.List<Object> steps = new java.util.ArrayList<>();
                    steps.add(ENTITY_DATA_FIELD);
                    steps.add(key);
                    CAP_WRITE_CACHE.put(target, new WritePath(DATA_ITEM_VALUE_FIELD, steps, isReverse));
                    DebugLog.probe("[插针] DataItem槽命中(形态={}) key={} 原值={} → {}",
                        isReverse ? "反向承伤" : "正向血量", key, orig, writeVal);
                    return true;
                }
            }
        } catch (Exception e) {
            DebugLog.probe("[插针] DataItem 槽插针异常: {}", e.toString());
        }
        return false;
    }

    /**
     * 全图对象图插针公共入口（v1.4.2）。
     *
     * @param relaxed true 时放宽反向形态闸地板（封存前补探专用，满血时承伤累计型
     *                候选的 max−reading&lt;1.0 不受降噪闸拦截）；false 为常规探测
     */
    public static boolean probeGraphFull(LivingEntity target, float targetValue, boolean relaxed) {
        // 超预算封存类：直接跳过全图（WritePath 快路径不受影响；级联失效时清空重试）。
        // 中等项修复（子代理审查）：relaxed（封存前放宽补探）不受封存拦截——补探语义本就是
        // 放宽约束的最后一轮尝试，若仍超预算中止则封存已存在，语义自洽
        if (!relaxed && GRAPH_OVERWHELMED.contains(target.getClass())) {
            DebugLog.probe("[插针] {} 对象图超预算封存，跳过全图扫描", target.getClass().getSimpleName());
            return false;
        }
        try {
            java.util.Set<Object> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            float hit = probeGraph(target, target, 0, targetValue, visited, new java.util.ArrayList<>(), relaxed);
            if (hit >= 0.0F) {
                DebugLog.probe("[插针] 命中 {}: {} → {}", target, targetValue);
                return true;
            }
            if (hit == GRAPH_ABORTED) {
                GRAPH_OVERWHELMED.add(target.getClass());
                DebugLog.probe("[插针] {} 对象图超预算（{} 对象 > {}），中止并封存该类全图扫描",
                    target.getClass().getSimpleName(), visited.size(),
                    ModConfig.QUENCH_GRAPH_BUDGET.get());
                return false;
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
     * 递归插针单个对象：扫描 float 字段做<b>形态分类值闸</b>（正向血量镜像 /
     * 反向承伤累计）后写测试值验证；命中写入目标值并缓存通路（字段 + 从实体到
     * 宿主的引用路径链），未命中还原并递归全部引用字段/容器。
     * {@code visited} 防环；深度上限 {@link #GRAPH_DEPTH_LIMIT}。
     * {@code path} 记录从实体根到当前对象的步骤链（Field=对象字段；Map 记录 key；Collection 记录 index）。
     * 返回命中字段原值，未命中返回 -1。
     * <p>
     * v1.4.2（设计文档 quench-upgrade-proposal.md）：
     * <ul>
     *   <li>值闸双向形态分类（不变量①）——正向 {@code field≈reading}（血量镜像，写降）；
     *       反向 {@code field≈(maxHealth−reading)}（承伤累计型，血量下降=字段值上升）。
     *       两形态都匹配时正向优先（满血附近 max−reading≈0 时 0 值冷却/计数字段的假阳性降噪）。
     *       反向候选受 {@code max−reading≥1.0} 降噪闸（relaxed=true 时放宽，封存前补探专用）。</li>
     *   <li>量纲缩放（不变量⑦）——探针步长 ε=max(基数, ulp(reading)×4)、验证门槛与指向
     *       容差随 ε 派生（原裸 −1/0.5/±1.0 在 ≥16M HP 目标上低于 float ulp 地板）。</li>
     *   <li>探测方向恒朝血量下降（不变量③）——正向写 orig−ε、反向写 orig+ε。</li>
     * </ul>
     */
    private static float probeGraph(LivingEntity target, Object obj, int depth, float targetValue,
                                    java.util.Set<Object> visited, java.util.List<Object> path, boolean relaxed) {
        if (obj == null || depth > GRAPH_DEPTH_LIMIT) return -1.0F;
        if (obj instanceof Class<?> || obj instanceof Thread || obj instanceof ClassLoader) return -1.0F;
        // 通用性能边界：世界/注册表等全局巨对象（不含实体血量，跳过防对象图爆炸）
        if (obj instanceof net.minecraft.world.level.Level) return -1.0F;
        if (obj instanceof net.minecraft.core.Registry) return -1.0F;
        if (!visited.add(obj)) return -1.0F; // 防环：同 tick 内同一对象只插一次
        // 扫描预算（v1.4.2 实测修复）：geckolib 动画类可达图数百万对象，超预算立即中止
        if (visited.size() > ModConfig.QUENCH_GRAPH_BUDGET.get()) return GRAPH_ABORTED;
        // 参照 = getHealth()（真血读数：正常实体读槽9；重定向实体读真血源）
        float currentHealth = target.getHealth();
        float reverseRef = target.getMaxHealth() - currentHealth; // 反向形态参照（承伤累计）
        boolean reverseAllowed = relaxed || ProbeScales.reverseFloorMet(target, currentHealth);
        float eps = ProbeScales.epsilon(currentHealth);
        float gateTol = ProbeScales.gateTolerance(currentHealth);
        float verifyTh = ProbeScales.verifyThreshold(eps);
        float driftTol = ProbeScales.driftTolerance(eps);
        Class<?> cls = obj.getClass();
        // ① float 字段插针
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != float.class && f.getType() != Float.class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    float orig = f.getFloat(obj);
                    // 值闸形态分类：正向候选（血量镜像）或反向候选（承伤累计），都不匹配跳过不探
                    boolean forward = Math.abs(orig - currentHealth) <= gateTol;
                    boolean reverse = !forward && reverseAllowed
                        && Math.abs(orig - reverseRef) <= gateTol;
                    if (!forward && !reverse) continue;
                    boolean isReverse = reverse;
                    // 插针：快照 → 写 ±ε 小扰动（血量恒下降方向）→ 无条件还原
                    float probeVal = isReverse ? orig + eps : orig - eps;
                    float before = target.getHealth();
                    f.setFloat(obj, probeVal);
                    float after;
                    try {
                        after = target.getHealth();
                    } finally {
                        f.setFloat(obj, orig); // 无条件还原（异常也不残留）
                    }
                    // 判据①：写入前后 getHealth 必须真实变化（防"恒定读数 + 碰巧≈血量"的无关字段误判）
                    if (Math.abs(after - before) < verifyTh) continue;
                    // 判据②：变化量必须指向测试值（真血字段特征）
                    //   正向：after ≈ orig−ε；反向：血量=reading−（新增累计 ε）→ after ≈ reading−ε
                    float expected = isReverse ? currentHealth - eps : orig - eps;
                    if (Math.abs(after - expected) <= driftTol) {
                        // 命中：写入目标值并缓存通路（字段 + 实体→宿主路径链，按实例弱 key）
                        //   反向字段的目标值换算：累计 = 原累计 +（读数 − 目标血量）
                        float writeVal = isReverse ? orig + (currentHealth - targetValue) : targetValue;
                        f.setFloat(obj, writeVal);
                        CAP_WRITE_CACHE.put(target, new WritePath(f, new java.util.ArrayList<>(path), isReverse));
                        DebugLog.probe("[插针] 字段命中(形态={}): {}#{} 原值={} → {}",
                            isReverse ? "反向承伤" : "正向血量", cls.getSimpleName(), f.getName(), orig, writeVal);
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
                            float r = probeGraph(target, e.getValue(), depth + 1, targetValue, visited, path, relaxed);
                            path.remove(path.size() - 1);
                            path.remove(path.size() - 1);
                            if (r >= 0.0F || r == GRAPH_ABORTED) return r;
                        }
                    } else if (child instanceof java.util.Collection<?> col) {
                        int idx = 0;
                        for (Object v : col) {
                            path.add(f);
                            path.add(idx);
                            float r = probeGraph(target, v, depth + 1, targetValue, visited, path, relaxed);
                            path.remove(path.size() - 1);
                            path.remove(path.size() - 1);
                            if (r >= 0.0F || r == GRAPH_ABORTED) return r;
                            idx++;
                        }
                    } else if (!child.getClass().isPrimitive()) {
                        path.add(f);
                        float r = probeGraph(target, child, depth + 1, targetValue, visited, path, relaxed);
                        path.remove(path.size() - 1);
                        if (r >= 0.0F || r == GRAPH_ABORTED) return r;
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
        if (probeCapabilityHealth(target, health) == LayerOutcome.PROBE_HIT) return;
        setAllHealthLikeRaw(target, health);
    }

    /**
     * 分级直写（引擎版，v1.4.2）：通用层 → 门禁 → 插针层，返回分层结果。
     * 与 {@link #setHealthLikeAny} 的区别：NO_HIT 时<b>不</b>执行 raw 兜底——
     * {@code BloodWriteEngine} 需要在 L2 失败后先走 L3 类静态容器层与封存前补探，
     * raw（显示层盲兜底）放梯子末端统一执行。
     */
    public static LayerOutcome tryLayeredWrite(LivingEntity target, float health) {
        if (setHealthLikeGeneric(target, health)) return LayerOutcome.GENERIC_HIT;
        // 门禁直通道的写入与写后验证已内聚在 probeFresh（v1.4.2-fix：验证失败回落槽插针/对象图）
        return probeCapabilityHealth(target, health);
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
            LOGGER.error("[HealthUtil] 内部类反射初始化失败，eradicateFromWorld 将不可用", e);
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
                        } catch (IllegalAccessException e) {
                            LOGGER.warn("[HealthUtil] eradicateFromWorld ② byId 清理失败", e);
                        }
                    }
                    if (EL_BY_UUID != null) {
                        try {
                            Object byUuid = EL_BY_UUID.get(visibleEntityStorage);
                            if (byUuid instanceof Map) {
                                ((Map<?, ?>) byUuid).remove(entityUuid);
                            }
                        } catch (IllegalAccessException e) {
                            LOGGER.warn("[HealthUtil] eradicateFromWorld ② byUuid 清理失败", e);
                        }
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
                } catch (IllegalAccessException e) {
                    LOGGER.warn("[HealthUtil] eradicateFromWorld ③ knownUuids 清理失败", e);
                }
            }

            // ④ EntityTickList — 直拿内部 active(Int2ObjectMap)/passive(List)，绕过 Mixin 拦截
            // v1.4.0 时序约束注释：从 EntityTickList 移除须避开 ServerLevel.tick 的
            // entityTickList.forEach 迭代窗口（迭代中修改抛 CME）。当前调用链（影杀斩杀经
            // 玩家 doTick/connection 阶段触发）恰好避开 levels 阶段的实体迭代；若未来调用
            // 时机改变（如从 tick 事件内触发），此处会 CME——catch 已兜底降级为"该容器未清理"，
            // 由 ⑤⑥ 与 tick 自检兜底，不会崩溃
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
                } catch (Exception e) {
                    // v1.4.0：④ 失败（含迭代窗口 CME）降级为"该容器未清理"，由 ⑤⑥ 与 tick 自检兜底
                    LOGGER.warn("[HealthUtil] eradicateFromWorld ④ EntityTickList 清理失败（时序冲突？），"
                        + "由后续容器清理兜底", e);
                }
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
                } catch (Exception e) {
                    // v1.4.0：⑤ 失败（目标移动/区块卸载导致 section 定位不到）→ 实体残留于
                    // EntitySection/EntityLookup 的半抹除状态，getEntities 仍可找到——记日志便于排查
                    LOGGER.warn("[HealthUtil] eradicateFromWorld ⑤ EntitySection 清理失败（目标移动/区块卸载？），"
                        + "实体可能残留于区块索引（半抹除）", e);
                }
            }

            // ⑥ ServerChunkCache.removeEntity(entity)
            if (SCC_REMOVE_ENTITY != null) {
                try {
                    SCC_REMOVE_ENTITY.invoke(sl.getChunkSource(), target);
                } catch (Exception e) {
                    LOGGER.warn("[HealthUtil] eradicateFromWorld ⑥ ServerChunkCache 清理失败", e);
                }
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
                } catch (Exception e) {
                    LOGGER.warn("[HealthUtil] eradicateFromWorld ⑦ 龙部件清理失败", e);
                }
            }

        } catch (Exception e) {
            // 整体兜底 —— 这是最终兜底手段，不应因反射失败而中断主流程；
            // 记日志便于排查半抹除状态（与 ④⑤ 分层日志对称）
            LOGGER.warn("[HealthUtil] eradicateFromWorld 整体异常（部分容器可能未清理）", e);
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
