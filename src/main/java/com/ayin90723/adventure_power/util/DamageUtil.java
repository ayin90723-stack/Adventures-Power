package com.ayin90723.adventure_power.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 伤害工具类 — 统一本模组内部伤害源的判断与构造。
 * <p>
 * 本模组定义了两个内部伤害类型：
 * <ul>
 *   <li>{@code soul_strike} — 淬魂之力 (soul_quench) 的真实百分比伤害</li>
 *   <li>{@code judgment} — 旅者审判 (active_skill) 的范围伤害</li>
 * </ul>
 * 这两个伤害类型绕过护甲/无敌/附魔保护/攻击冷却，用于模组内部结算。
 * {@link #isInternalSource(DamageSource)} 用于在伤害事件中识别这些内部伤害，
 * 防止递归重入（如淬魂的内部 hurt() 不再触发嗜血吸血/影杀影子血量削减）。
 */
public class DamageUtil {

    /**
     * 判断伤害源是否为本模组内部伤害类型（soul_strike 或 judgment）。
     *
     * @param source 待判断的伤害源
     * @return true 表示是模组内部伤害
     */
    public static boolean isInternalSource(DamageSource source) {
        String msgId = source.getMsgId();
        return "soul_strike".equals(msgId) || "judgment".equals(msgId);
    }

    /**
     * 构造 soul_strike 伤害源（淬魂之力使用）。
     * <p>
     * 绕过护甲/无敌/附魔保护/攻击冷却，造成真实百分比伤害。
     * 直接实体为 null，间接实体（攻击者）为传入的 source 参数。
     *
     * @param level  目标所在维度
     * @param source 造成伤害的实体（攻击者）
     * @return 构造好的 DamageSource 实例
     */
    public static DamageSource createSoulStrike(Level level, Entity source) {
        var key = ResourceKey.create(Registries.DAMAGE_TYPE,
            new ResourceLocation("adventure_power", "soul_strike"));
        var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        var holder = registry.getHolderOrThrow(key);
        return new DamageSource(holder, null, source);
    }

    /**
     * 构造 judgment 伤害源（旅者审判使用）。
     * <p>
     * 绕过护甲/无敌/附魔保护/攻击冷却，造成范围真实伤害。
     * 直接实体为 null，间接实体（攻击者）为传入的 source 参数。
     *
     * @param level  目标所在维度
     * @param source 造成伤害的实体（攻击者）
     * @return 构造好的 DamageSource 实例
     */
    public static DamageSource createJudgment(Level level, Entity source) {
        var key = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("adventure_power", "judgment"));
        var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        var holder = registry.getHolderOrThrow(key);
        return new DamageSource(holder, null, source);
    }
}