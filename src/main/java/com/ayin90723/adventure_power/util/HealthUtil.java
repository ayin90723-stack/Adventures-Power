package com.ayin90723.adventure_power.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
                // 直写 DataItem.value（绕过 SynchedEntityData.set），避免触发 onSyncedDataUpdated：
                // 某些实体重写 onSyncedDataUpdated 在血量变化时调 move 等重操作导致卡死
                writeValueDirect(target.getEntityData(), DATA_HEALTH_ID, health);
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
    /** DataItem.setValue 方法反射（直写 value + setDirty，绕过 onSyncedDataUpdated） */
    private static java.lang.reflect.Method DATA_ITEM_SET_VALUE_METHOD;

    /**
     * 直写 EntityDataAccessor 对应 DataItem 的 value（调用 DataItem.setValue），
     * 绕过 SynchedEntityData.set，避免触发 onSyncedDataUpdated。
     *
     * <p>某些实体（如天境史维特）重写 onSyncedDataUpdated，在特定 accessor 变化时
     * 调 move() 触发重碰撞计算。若通过 data.set() 写入会触发该回调导致卡死。
     * 本方法直接写 DataItem.value + setDirty（保证客户端同步），不触发任何 onSyncedDataUpdated。</p>
     */
    @SuppressWarnings("unchecked")
    private static <T> void writeValueDirect(SynchedEntityData data, EntityDataAccessor<T> key, T value) {
        if (ENTITY_DATA_ITEMS_FIELD == null) {
            data.set(key, value);  // 反射初始化失败，降级为标准路径
            return;
        }
        try {
            Map<Integer, Object> items = (Map<Integer, Object>) ENTITY_DATA_ITEMS_FIELD.get(data);
            if (items == null) { data.set(key, value); return; }
            Object dataItem = items.get(key.getId());
            if (dataItem == null) { data.set(key, value); return; }
            if (DATA_ITEM_SET_VALUE_METHOD == null) {
                DATA_ITEM_SET_VALUE_METHOD = dataItem.getClass().getDeclaredMethod("setValue", Object.class);
                DATA_ITEM_SET_VALUE_METHOD.setAccessible(true);
            }
            DATA_ITEM_SET_VALUE_METHOD.invoke(dataItem, value);
        } catch (Exception e) {
            data.set(key, value);  // 反射失败，降级
        }
    }

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
                writeValueDirect(data, key, health);
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
        } catch (IllegalAccessException | ClassCastException e) {
            e.printStackTrace();
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
        // 扫描时的当前血量：用于区分血量条目与非血量 Float 条目（如水伤害比例）
        float currentHealth = getHealthDirect(target);

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
            e.printStackTrace();
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

    // ==================== 世界内部结构抹除（饱和式秒杀最终手段） ====================

    // --- ServerLevel fields ---
    private static final Field SL_ENTITY_MANAGER = reflectField(
        ServerLevel.class, "f_143244_", "entityManager");
    private static final Field SL_ENTITY_TICK_LIST = reflectField(
        ServerLevel.class, "f_143243_", "entityTickList");

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
            e.printStackTrace();
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

    /** 反射获取字段，先试 SRG 名再试 MCP 名 */
    private static Field reflectField(Class<?> clz, String srg, String mcp) {
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

    /** 反射获取方法，先试 SRG 名再试 MCP 名 */
    private static Method reflectMethod(Class<?> clz, String srg, String mcp, Class<?>... params) {
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
