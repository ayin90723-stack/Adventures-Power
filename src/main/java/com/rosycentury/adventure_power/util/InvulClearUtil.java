package com.rosycentury.adventure_power.util;

import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;

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
 * 典型场景：
 * <ul>
 *   <li>Goety Apostle — {@code moddedInvul} / {@code obsidianInvul} int 计时器
 *       在 {@code actuallyHurt()} 中被设置，导致后续 {@code hurt()} 提前返回
 *       false 且不调用 {@code super.hurt()}</li>
 *   <li>Mowzie 钢铁守护者 — 未激活时 {@code vulnerable = false}，{@code hurt()}
 *       检测后直接返回 false 且不调用 {@code super.hurt()}，完全绕过见既斩两
 *       层 Mixin 注入点</li>
 * </ul>
 * <p>
 * 独立工具类（非 Mixin），可被 {@code EnchantmentHandler.onAttackEntity}、
 * {@code EnchantmentHandler.onProjectileImpact} 和
 * {@code SeeAndSlashLivingEntityMixin} 共用。
 *
 * @see com.main.mymoreenchantments.mixin.SeeAndSlashLivingEntityMixin
 */
public class InvulClearUtil {

    /**
     * 反射清除目标实体继承链上所有无敌相关字段。
     * int 型计时器置零，boolean 型无敌标记置 false，boolean 型脆弱标记置 true。
     *
     * @param target 目标实体
     */
    public static void clearCustomInvulTimers(LivingEntity target) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                String name = field.getName();
                try {
                    field.setAccessible(true);
                    if (field.getType() == int.class) {
                        // 清零 int 型无敌计时器（如 moddedInvul / obsidianInvul）
                        if (name.contains("invul") || name.contains("Invul")) {
                            field.setInt(target, 0);
                        }
                    } else if (field.getType() == boolean.class) {
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
            clazz = clazz.getSuperclass();
        }
    }
}
