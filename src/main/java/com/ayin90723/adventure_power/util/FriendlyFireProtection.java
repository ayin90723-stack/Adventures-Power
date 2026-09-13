package com.ayin90723.adventure_power.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;

/**
 * 友好火力保护 — 防止超模附魔误伤玩家自己驯服的生物。
 * <p>
 * 覆盖范围：所有继承 {@link TamableAnimal} 的生物，包括：
 * <ul>
 *   <li>原版：狼、猫、鹦鹉</li>
 *   <li>模组：车万女仆 (EntityMaid) 等</li>
 * </ul>
 * <p>
 * 调用点（审查修 2026-09：原清单只列 3 处已过期，改为按"统一入口 + 目标集 filter"两级口径）：
 * <ul>
 *   <li><b>事件入口</b>：{@code CombatAbilityHandler.onLivingHurt()}（含内部伤害源早退之后的
 *       全部攻击事件路径）</li>
 *   <li><b>穿透统一入口</b>：{@code PiercingGazeUtil.shouldPierce()}（Layer 0 / 0.5 / 2 三层
 *       共用，兼容 Mixin 直调 {@code PiercingGazeMixin} / {@code PiercingGazeLivingEntityMixin}）</li>
 *   <li><b>各能力的目标集 filter</b>：{@code RecoveryHandler}（嗜血击杀回馈 / 过量护盾）×2、
 *       {@code ActiveSkillHandler.isHostileTarget()}、{@code LootAllHandler}</li>
 *   <li><b>觉醒 AOE 目标集</b>：{@code ShadowKillHelper.shadowKillAoe()}（链式处决）、
 *       {@code PlayerStateHandler}（净魂虚弱光环）、{@code SwiftHandler}（疾跑推开）——
 *       这三处走内部伤害源/直接施效，绕不过事件层，必须各自 filter</li>
 * </ul>
 */
public class FriendlyFireProtection {

    /**
     * 检查目标生物是否属于攻击者（驯服且主人是攻击者本人）。
     *
     * @param attacker 攻击者
     * @param target   被攻击的实体
     * @return true 表示目标是攻击者驯服的生物，应阻止超模附魔效果
     */
    public static boolean isOwnerTarget(LivingEntity attacker, LivingEntity target) {
        if (target instanceof TamableAnimal tamable) {
            java.util.UUID ownerUUID = tamable.getOwnerUUID();
            return ownerUUID != null && ownerUUID.equals(attacker.getUUID());
        }
        return false;
    }
}
