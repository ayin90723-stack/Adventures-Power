package com.ayin90723.adventure_power.util;

import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射清除目标实体上的自定义无敌状态。
 * <p>
 * 遍历实体继承链上所有类声明的字段，根据命名模式匹配并清除无敌相关字段：
 * <ul>
 *   <li>{@code int *invul*} → 置零（清除无敌计时器，如 Goety Apostle 的
 *       {@code moddedInvul} / {@code obsidianInvul}）</li>
 *   <li>{@code boolean *invul*} → 置 {@code false}（关闭无敌标记）</li>
 *   <li>{@code boolean *vulnerable*} → 置 {@code true}（打开脆弱标记，如
 *       Mowzie 钢铁守护者的 {@code vulnerable}）</li>
 * </ul>
 * 使用 try-catch 包裹，实体无此类字段时静默跳过。
 * <p>
 * 字段列表按 {@code entity.getClass()} 缓存，首次扫描后直接走缓存。
 * 缓存参考 {@link HealthUtil#CUSTOM_HEALTH_KEYS_CACHE} 的模式。
 * <p>
 * 典型场景：
 * <ul>
 *   <li>Goety Apostle — {@code moddedInvul} / {@code obsidianInvul} int 计时器
 *       在 {@code actuallyHurt()} 中被设置，导致后续 {@code hurt()} 提前返回
 *       false 且不调用 {@code super.hurt()}</li>
 *   <li>Mowzie 钢铁守护者 — 未激活时 {@code vulnerable = false}，{@code hurt()}
 *       检测后直接返回 false 且不调用 {@code super.hurt()}，完全绕过破敌之眼两
 *       层 Mixin 注入点</li>
 * </ul>
 * <p>
 * 独立工具类（非 Mixin），可被 {@code PiercingGazeLivingEntityMixin} 共用。
 *
 * @see com.ayin90723.adventure_power.mixin.PiercingGazeLivingEntityMixin
 * @see HealthUtil#CUSTOM_HEALTH_KEYS_CACHE
 */
public class InvulClearUtil {

    /**
     * 按实体类型缓存扫描到的无敌相关字段列表。
     * key: entity.getClass(); value: 该类型继承链上所有匹配的 Field（已 setAccessible）。
     * 缓存后 clearCustomInvulTimers 只需遍历列表调用 set/setBoolean，无需再反射扫描。
     */
    private static final Map<Class<?>, List<Field>> INVUL_FIELDS_CACHE = new ConcurrentHashMap<>();

    /**
     * 扫描指定实体类继承链中所有匹配无敌模式的字段，并设置 accessible。
     * 匹配规则与 {@link #clearCustomInvulTimers} 中的逻辑完全一致：
     * <ul>
     *   <li>int 型字段名包含 "invul"/"Invul"</li>
     *   <li>boolean 型字段名包含 "vulnerable"/"Vulnerable" 且不包含 "invul"/"Invul"</li>
     *   <li>boolean 型字段名包含 "invul"/"Invul"</li>
     * </ul>
     *
     * @param clazz 实体类
     * @return 匹配的字段列表
     */
    private static List<Field> scanInvulFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                String name = field.getName();
                boolean matches;
                if (field.getType() == int.class) {
                    matches = name.contains("invul") || name.contains("Invul");
                } else if (field.getType() == boolean.class) {
                    boolean matchesVulnerable = (name.contains("vulnerable") || name.contains("Vulnerable"))
                        && !name.contains("invul") && !name.contains("Invul");
                    boolean matchesInvul = name.contains("invul") || name.contains("Invul");
                    matches = matchesVulnerable || matchesInvul;
                } else {
                    matches = false;
                }
                if (matches) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * 反射清除目标实体继承链上所有无敌相关字段。
     * int 型计时器置零，boolean 型无敌标记置 false，boolean 型脆弱标记置 true。
     * <p>
     * 字段列表按 {@code target.getClass()} 缓存，首次扫描后直接走缓存，
     * 避免在破敌之眼攻击热路径上重复反射遍历。
     *
     * @param target 目标实体
     */
    public static void clearCustomInvulTimers(LivingEntity target) {
        List<Field> fields = INVUL_FIELDS_CACHE.computeIfAbsent(target.getClass(), InvulClearUtil::scanInvulFields);
        for (Field field : fields) {
            try {
                if (field.getType() == int.class) {
                    // 清零 int 型无敌计时器（如 moddedInvul / obsidianInvul）
                    field.setInt(target, 0);
                } else if (field.getType() == boolean.class) {
                    String name = field.getName();
                    // 打开 boolean 型脆弱标记（如 Wroughtnaut 的 vulnerable）
                    // 必须排除 "invulnerable" 这类同时包含两边子串的字段名，
                    // 否则会在 invul→false 之后被 vulnerable→true 错误覆盖
                    if ((name.contains("vulnerable") || name.contains("Vulnerable"))
                        && !name.contains("invul") && !name.contains("Invul")) {
                        field.setBoolean(target, true);
                    }
                    // 关闭 boolean 型无敌标记（如 isInvulnerable）
                    if (name.contains("invul") || name.contains("Invul")) {
                        field.setBoolean(target, false);
                    }
                }
            } catch (IllegalAccessException ignored) {
                // 反射失败静默跳过
            }
        }
    }
}
