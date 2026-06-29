package com.ayin90723.adventure_power.util;

import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
 * 独立工具类，可被 {@code EnchantmentHandler}、{@code UndyingSlashEffect}、
 * 以及任何需要直接操作血量的 Mixin 或事件处理器共用。
 *
 * @see com.main.mymoreenchantments.buff.UndyingSlashEffect.EventHandler#onLivingTick
 * @see com.main.mymoreenchantments.EnchantmentHandler#handleSoulStrikePlus
 */
public class HealthUtil {

    /**
     * hurt() 调用嵌套深度计数器。
     * 由 {@code RejectHealthManipMixin} 在 hurt() HEAD/RETURN 维护，
     * 由 {@code TrueHealthMixin} 在 setHealth RETURN 读取以判断是否合法 hurt 路径。
     */
    public static final ThreadLocal<Integer> HURT_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static Field DATA_HEALTH_ID_FIELD;
    private static EntityDataAccessor<Float> DATA_HEALTH_ID;

    static {
        try {
            DATA_HEALTH_ID_FIELD = LivingEntity.class.getDeclaredField("f_20961_");
        } catch (NoSuchFieldException e) {
            try {
                DATA_HEALTH_ID_FIELD = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
            } catch (NoSuchFieldException ex) {
                System.err.println("[AdventurePower] HealthUtil: 无法反射获取 DATA_HEALTH_ID 字段，setHealthDirect 将不可用");
                ex.printStackTrace();
            }
        }
        if (DATA_HEALTH_ID_FIELD != null) {
            DATA_HEALTH_ID_FIELD.setAccessible(true);
        }
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
            e.printStackTrace();
        }
        return target.getHealth();
    }

    /**
     * 直接写入血量字段（绕过 setHealth() 所有覆写，包括硬上限/适应性减伤/免疫帧）。
     * <p>
     * 写入目标为 {@code SynchedEntityData} 中 {@code DATA_HEALTH_ID} 对应的条目，
     * 后续 {@code entity.getHealth()} 将返回写入值。
     *
     * @param target 目标实体
     * @param health 目标血量值
     */
    @SuppressWarnings("unchecked")
    public static void setHealthDirect(LivingEntity target, float health) {
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
            e.printStackTrace();
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
                ex.printStackTrace();
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
            e.printStackTrace();
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
    @SuppressWarnings("unchecked")
    public static void setAllHealthLikeDirect(LivingEntity target, float health) {
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
     * <h3>匹配策略</h3>
     * 遍历所有 DataItem，找到值等于目标当前血量的条目（匹配 Float/Double/Integer 三种类型），
     * 直接写入目标血量值。不依赖 EntityDataAccessor key — 无需事先知道
     * Boss 用哪个 key 存储血量。
     *
     * <h3>调用时机</h3>
     * 作为 {@code setAllHealthLikeDirect} 之后的深层兜底。
     * 需先调用 {@code setAllHealthLikeDirect} 写入已知 key，
     * 再调用本方法覆盖任何遗漏的、或被 Mixin 回滚的数据条目。
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
                        if (matched) DATA_ITEM_VALUE_FIELD.set(item, Float.valueOf(health));
                    } else if (rawValue instanceof Double d) {
                        matched = Math.abs(d - (double) healthBefore) < 0.01;
                        if (matched) DATA_ITEM_VALUE_FIELD.set(item, Double.valueOf((double) health));
                    } else if (rawValue instanceof Integer i) {
                        // Integer 型血量近似匹配（部分 mod 用 Integer 存百分比血量）
                        matched = Math.abs(i - (int) healthBefore) <= 1;
                        if (matched) DATA_ITEM_VALUE_FIELD.set(item, Integer.valueOf((int) health));
                    }
                } catch (IllegalAccessException ignored) {
                    // 单个 DataItem 写入失败，继续处理下一个
                }
            }
            // ④ 清除恶意 delta：遍历所有 DataItem，将值为负数的 Float 条目归零。
            //    部分外部 Boss（如终焉秩序维系者）通过独立的 EntityDataAccessor<Float>
            //    （不在实体 class hierarchy 中，scanCustomHealthKeys 无法发现）
            //    维护血量 delta/偏移值。这些负值 delta 会通过 ASM 篡改 getHealth() 返回值，
            //    且不受前面步骤的"值匹配"逻辑影响（因负值与正常血量差距远超匹配阈值）。
            //    本步骤作为终极兜底：任何 Float 型 DataItem 值为负数即判定为恶意 delta 并清零。
            for (Object item : items.values()) {
                try {
                    Object rawValue = DATA_ITEM_VALUE_FIELD.get(item);
                    if (rawValue instanceof Float f && f < -0.01F) {
                        DATA_ITEM_VALUE_FIELD.set(item, Float.valueOf(0.0F));
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        } catch (IllegalAccessException | ClassCastException e) {
            e.printStackTrace();
        }
    }

    /**
     * 扫描实体类层次中所有 Float 类型的自定义血量 accessor。
     * <p>
     * 不依赖字段名（无法预测 Boss 使用的混淆字段名），
     * 纯粹通过运行时类型检查定位自定义血量 key。
     */
    private static Set<EntityDataAccessor<Float>> scanCustomHealthKeys(LivingEntity target) {
        Set<EntityDataAccessor<Float>> keys = new LinkedHashSet<>();
        net.minecraft.network.syncher.SynchedEntityData data = target.getEntityData();

        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
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

                    // 验证实际存储值为 Float 类型
                    try {
                        Object value = data.get(accessor);
                        if (value instanceof Float) {
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
            e.printStackTrace();
        }
    }

    private static Method ENTITY_SET_REMOVED_METHOD;

    /**
     * 反射调用 {@code Entity.setRemoved(RemovalReason)} (SRG: {@code m_146918_})，
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
                    ENTITY_SET_REMOVED_METHOD = Entity.class.getDeclaredMethod("m_146918_", Entity.RemovalReason.class);
                } catch (NoSuchMethodException e) {
                    ENTITY_SET_REMOVED_METHOD = Entity.class.getDeclaredMethod("setRemoved", Entity.RemovalReason.class);
                }
                ENTITY_SET_REMOVED_METHOD.setAccessible(true);
            }
            ENTITY_SET_REMOVED_METHOD.invoke(target, reason);
        } catch (Exception e) {
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }
}
