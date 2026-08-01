package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.util.AbilityIds;
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
 * 本模组定义了三个内部伤害类型：
 * <ul>
 *   <li>{@code soul_strike} — 淬魂之力 (soul_quench) 的真实百分比伤害</li>
 *   <li>{@code judgment} — 旅者审判 (active_skill) 的范围伤害</li>
 *   <li>{@code shadow_kill} — 影杀 (shadow_kill) 的饱和式斩杀伤害</li>
 * </ul>
 * 这三个伤害类型绕过护甲/无敌/附魔保护/攻击冷却，用于模组内部结算。
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
    /** 缓存 shadow_kill DamageType 的 Holder，首次调用时 lazy 初始化。 */
    private static volatile Holder<DamageType> shadowKillHolder;

    /** soul_strike 的 ResourceKey，用于 lazy 初始化时查 registry。 */
    private static final ResourceKey<DamageType> SOUL_STRIKE_KEY = ResourceKey.create(
        Registries.DAMAGE_TYPE, new ResourceLocation("adventure_power", "soul_strike"));
    /** judgment 的 ResourceKey，用于 lazy 初始化时查 registry。 */
    private static final ResourceKey<DamageType> JUDGMENT_KEY = ResourceKey.create(
        Registries.DAMAGE_TYPE, new ResourceLocation("adventure_power", "judgment"));
    /** shadow_kill 的 ResourceKey，用于 lazy 初始化时查 registry。 */
    private static final ResourceKey<DamageType> SHADOW_KILL_KEY = ResourceKey.create(
        Registries.DAMAGE_TYPE, new ResourceLocation("adventure_power", "shadow_kill"));

    /**
     * 判断伤害源是否为本模组内部伤害类型（soul_strike / judgment / shadow_kill）。
     *
     * @param source 待判断的伤害源
     * @return true 表示是模组内部伤害
     */
    public static boolean isInternalSource(DamageSource source) {
        // 比较的是 damage_type 的 message_id，与能力 ID 同名但语义不同——保持字面量
        // （若改用 AbilityIds.SHADOW_KILL，重命名能力 ID 会静默破坏内部源识别）
        String msgId = source.getMsgId();
        return "soul_strike".equals(msgId) || "judgment".equals(msgId) || "shadow_kill".equals(msgId);
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

    /**
     * 构造 shadow_kill 伤害源（影杀饱和式秒杀使用）。
     * <p>
     * 绕过护甲/无敌/附魔保护/攻击冷却。作为内部伤害源，斩杀伤害不会再次
     * 触发嗜血吸血/淬魂追加/影杀影子血量削减等模组能力结算。
     * <p>
     * {@code DamageType} registry 是全局的，首次调用时从 {@code level} 获取
     * {@code registryAccess} 缓存 {@code Holder}，后续调用直接复用。
     *
     * @param level  目标所在维度
     * @param source 造成伤害的实体（攻击者）
     * @return 构造好的 DamageSource 实例
     */
    public static DamageSource createShadowKill(Level level, Entity source) {
        if (shadowKillHolder == null) {
            var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
            shadowKillHolder = registry.getHolderOrThrow(SHADOW_KILL_KEY);
        }
        return new DamageSource(shadowKillHolder, null, source);
    }
}
