package com.rosycentury.adventure_power.util;

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
 * 调用点：
 * <ul>
 *   <li>{@code EnchantmentHandler.onLivingHurt()} — 事件入口统一拦截</li>
 *   <li>{@code SeeAndSlashMixin.onIsInvulnerableTo()} — 见既斩 Mixin Layer1</li>
 *   <li>{@code SeeAndSlashLivingEntityMixin.onHurtReturn()} — 见既斩 Mixin Layer2</li>
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
