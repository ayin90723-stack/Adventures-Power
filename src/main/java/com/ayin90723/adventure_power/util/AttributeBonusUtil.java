package com.ayin90723.adventure_power.util;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.UUID;

/**
 * 属性加成（AttributeModifier）公共工具 —— v1.4.3-fix 属性承载方式迁移核心。
 * <p>
 * v1.4.3 及之前，不动如山/无形之手/坚韧之躯/鸿运当头的加成直接写属性 baseValue
 * （setBaseValue(默认+加成)）。base 是独占资源：其他模组也以 baseValue 持久化
 * 同名属性（体质/耐力/移速/幸运类）时双方每 tick 对账互踩，后写者覆盖先写者，
 * 且本模组写入前记录的"原值"随时间推移已非对方最新值，登出/关闭恢复会把对方
 * 的新值覆盖掉。modifier 是叠加资源：最终值 = 任意模组 base + 任意模组 modifier，
 * 互不干扰，卸载时按固定 UUID remove 即干净，无需"记录原值→恢复"对账。
 * <p>
 * 本类提供两种承载方式共用的能力：
 * <ul>
 *   <li>{@link #syncTransientModifier} —— 按固定 UUID 对账挂/更新/移除 transient
 *       modifier（与加速 SWIFT 既有模式一致；transient 不落盘，登出随实体销毁消失）</li>
 *   <li>{@link #migrateLegacyBaseBonus} —— 旧版 base 加成残留迁移：v1.4.3-fix 升级
 *       前崩溃/强杀等场景登出恢复未执行时，旧版写入 base 的加成残留进 player.dat；
 *       升级后若 base 仍 ≈ 默认+旧公式加成（当前有效里程碑可复算），判定为旧残留
 *       还原为默认——防止"旧 base 加成 + 新 modifier 加成"双份叠加。</li>
 * </ul>
 */
public final class AttributeBonusUtil {

    /** 值变化容差（与各 handler 既有 0.001 判定一致，避免浮点抖动反复重挂） */
    private static final double EPSILON = 0.001;

    private AttributeBonusUtil() {}

    /**
     * 按固定 UUID 对账 transient modifier：加成变化才重挂，加成归零即移除。
     *
     * @return true = 实际发生了挂载/更新/移除（modifier 或属性值发生变化），
     *         调用方可用于"值变化后才裁剪血量"等联动
     */
    public static boolean syncTransientModifier(AttributeInstance inst, UUID uuid,
                                                String name, double bonus,
                                                AttributeModifier.Operation op) {
        AttributeModifier existing = inst.getModifier(uuid);
        if (Math.abs(bonus) > EPSILON) {
            if (existing != null && Math.abs(existing.getAmount() - bonus) <= EPSILON) {
                return false;
            }
            if (existing != null) {
                inst.removeModifier(uuid);
            }
            inst.addTransientModifier(new AttributeModifier(uuid, name, bonus, op));
            return true;
        }
        if (existing != null) {
            inst.removeModifier(uuid);
            return true;
        }
        return false;
    }

    /**
     * 旧版（v1.4.3 及之前）base 加成残留迁移。
     * <p>
     * 判定：当前 base ≠ 默认 且 base ≈ 默认 + activeBonus（旧版启用时会写入的公式值，
     * 用当前有效里程碑复算）→ 判定为本模组旧残留，还原为默认。
     * <p>
     * 天然幂等：还原后 base == 默认，后续调用不再触发；activeBonus ≤ 0 时
     * （能力未解锁/公式为 0）条件自相矛盾（默认+0 == 默认），恒不触发——
     * 其他模组的合法 base 修改（≠ 默认+本模组公式值）不受影响。
     * 还原只写 base 不碰 modifier，必须在挂 modifier 前调用。
     */
    public static void migrateLegacyBaseBonus(AttributeInstance inst, double activeBonus) {
        if (activeBonus <= EPSILON) return;
        double base = inst.getBaseValue();
        double def = inst.getAttribute().getDefaultValue();
        if (Math.abs(base - def) > EPSILON
            && Math.abs(base - (def + activeBonus)) <= EPSILON) {
            inst.setBaseValue(def);
        }
    }
}
