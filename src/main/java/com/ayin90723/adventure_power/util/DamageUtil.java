package com.ayin90723.adventure_power.util;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
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
 * <p>
 * {@link DamageType} registry 是全局的（{@code RegistryAccess} 跨维度共享），
 * 因此 {@code soul_strike} 和 {@code judgment} 的 {@code Holder} 可安全地
 * 缓存为 static 字段，避免在淬魂/审判热路径上重复查 registry。
 */
public class DamageUtil {

    /** 缓存 soul_strike DamageType 的 Holder，首次调用时 lazy 初始化。 */
    private static volatile Holder<DamageType> soulStrikeHolder;
    /** 缓存 judgment DamageType 的 Holder，首次调用时 lazy 初始化。 */
    private static volatile Holder<DamageType> judgmentHolder;

    /** soul_strike 的 ResourceKey，用于 lazy 初始化时查 registry。 */
    private static final ResourceKey<DamageType> SOUL_STRIKE_KEY = ResourceKey.create(
        Registries.DAMAGE_TYPE, new ResourceLocation("adventure_power", "soul_strike"));
    /** judgment 的 ResourceKey，用于 lazy 初始化时查 registry。 */
    private static final ResourceKey<DamageType> JUDGMENT_KEY = ResourceKey.create(
        Registries.DAMAGE_TYPE, new ResourceLocation("adventure_power", "judgment"));

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
     * <p>
     * {@code DamageType} registry 是全局的，首次调用时从 {@code level} 获取
     * {@code registryAccess} 缓存 {@code Holder}，后续调用直接复用。
     *
     * @param level  目标所在维度
     * @param source 造成伤害的实体（攻击者）
     * @return 构造好的 DamageSource 实例
     */
    public static DamageSource createSoulStrike(Level level, Entity source) {
        if (soulStrikeHolder == null) {
            var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
            soulStrikeHolder = registry.getHolderOrThrow(SOUL_STRIKE_KEY);
        }
        return new DamageSource(soulStrikeHolder, null, source);
    }

    /**
     * 构造 judgment 伤害源（旅者审判使用）。
     * <p>
     * 绕过护甲/无敌/附魔保护/攻击冷却，造成范围真实伤害。
     * 直接实体为 null，间接实体（攻击者）为传入的 source 参数。
     * <p>
     * {@code DamageType} registry 是全局的，首次调用时从 {@code level} 获取
     * {@code registryAccess} 缓存 {@code Holder}，后续调用直接复用。
     *
     * @param level  目标所在维度
     * @param source 造成伤害的实体（攻击者）
     * @return 构造好的 DamageSource 实例
     */
    public static DamageSource createJudgment(Level level, Entity source) {
        if (judgmentHolder == null) {
            var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
            judgmentHolder = registry.getHolderOrThrow(JUDGMENT_KEY);
        }
        return new DamageSource(judgmentHolder, null, source);
    }
}