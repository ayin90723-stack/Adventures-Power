package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.mixin.PiercingGazeLivingEntityAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * 破敌之眼穿透结算公共工具。
 * <p>
 * 将原本散落在 {@link com.ayin90723.adventure_power.mixin.PiercingGazeMixin}、
 * {@link com.ayin90723.adventure_power.mixin.PiercingGazePlayerAttackMixin}、
 * {@link com.ayin90723.adventure_power.mixin.PiercingGazeLivingEntityMixin} 三处的
 * 重复逻辑（攻击者追溯 / 门禁检查 / 发事件 / 直写伤害 / 血量兜底 / 清无敌字段）
 * 统一收口于此，保证各层穿透行为一致。
 *
 * <h3>穿透结算两段式</h3>
 * <ul>
 *   <li>{@link #invokeActuallyHurt} - 直写伤害，绕过 hurt() 内的护甲/无敌判定</li>
 *   <li>{@link #afterPierceFallback} - 血量直写兜底（防 Boss 注入 setHealth 恢复血量）
 *       + 清自定义无敌计时器（防下次 hurt 提前 return false 锁死影杀 NBT）</li>
 * </ul>
 * 调用方按场景组合：actuallyHurt 未执行时（Layer 0 / Layer 2 情况 A/C）两段都调；
 * actuallyHurt 已由原版管线执行时（Layer 2 情况 B）只调兜底段，避免重复扣血。
 *
 * @see com.ayin90723.adventure_power.mixin.PiercingGazeMixin
 * @see com.ayin90723.adventure_power.mixin.PiercingGazePlayerAttackMixin
 * @see com.ayin90723.adventure_power.mixin.PiercingGazeLivingEntityMixin
 */
public final class PiercingGazeUtil {

    private PiercingGazeUtil() {
    }

    /**
     * 追溯真正的攻击者：直接实体 -> 间接实体 -> 弹射物发射者。
     * <p>
     * 弹射物（箭/弩箭/火球等）的 {@code getEntity()} 通常是弹射物本身，
     * 需进一步取 {@code getOwner()} 才是持破敌之眼的发射者。
     */
    public static Entity resolveAttacker(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        if (attacker instanceof Projectile projectile) {
            attacker = projectile.getOwner();
        }
        return attacker;
    }

    /**
     * 检查 LivingEntity 是否持有破敌之眼附魔且满足能力门禁。
     * <p>
     * 玩家需同时满足"冒险饰品激活 + piercing_gaze 能力可用"；
     * 非玩家实体（理论上不会持有，但保留兼容）只检查附魔。
     */
    public static boolean hasPiercingGaze(LivingEntity entity) {
        return AdventurePower.hasPiercingGaze(entity)
            && (!(entity instanceof Player player)
                || AdventureProgressCapability.isAbilityAvailable(player, "piercing_gaze"));
    }

    /**
     * 检查伤害源是否来自破敌之眼持有者，且非自身/友好火力/重入调用。
     *
     * @param source 伤害源
     * @param target 受击目标（用于友好火力判定）
     * @return true 表示这是一次应当穿透的破敌之眼攻击
     */
    public static boolean isPiercingGazeAttack(DamageSource source, LivingEntity target) {
        Entity attacker = resolveAttacker(source);
        if (!(attacker instanceof LivingEntity living)) {
            return false;
        }
        if (!hasPiercingGaze(living)) {
            return false;
        }
        // 友好火力保护：不穿透自己驯服生物
        if (FriendlyFireProtection.isOwnerTarget(living, target)) {
            return false;
        }
        return true;
    }

    /**
     * 手动 post {@link LivingHurtEvent}，返回取 {@code max(原值, 事件值)} 后的有效伤害。
     * <p>
     * 破敌之眼下伤害只能涨不能降--淬魂/影杀 等追加的伤害保留，Boss 限伤被忽略。
     * 即使事件被其他 mod 取消，破敌之眼仍穿透（万能钥匙不受外部取消影响），
     * 故不检查 {@code event.isCanceled()}。
     *
     * @param target 受击目标
     * @param source 伤害源
     * @param amount 原始伤害量
     * @return 经事件处理、取 max 后的有效伤害量
     */
    public static float postHurtEvent(LivingEntity target, DamageSource source, float amount) {
        LivingHurtEvent event = new LivingHurtEvent(target, source, amount);
        MinecraftForge.EVENT_BUS.post(event);
        return Math.max(amount, event.getAmount());
    }

    /**
     * 直写伤害 - 调用 {@link LivingEntity#actuallyHurt} 绕过 hurt() 内的护甲/无敌判定。
     * <p>
     * 通过 {@link PiercingGazeLivingEntityAccessor} 的 {@code @Invoker} 调用，
     * Java 虚分派会一路走到 {@code LivingEntity.actuallyHurt}（绝大多数 Boss 未重写此方法），
     * 完全绕过 Boss 在 hurt() 中设置的 vulnerable/护盾/角度等防御关卡。
     *
     * @param target 目标实体
     * @param source 伤害源
     * @param amount 伤害量（建议为 {@link #postHurtEvent} 取 max 后的值）
     */
    public static void invokeActuallyHurt(LivingEntity target, DamageSource source, float amount) {
        ((PiercingGazeLivingEntityAccessor) target).invokeActuallyHurt(source, amount);
    }

    /**
     * 穿透后兜底 - 血量直写 + 清自定义无敌字段。
     * <p>
     * <b>血量直写兜底</b>：部分 Boss（如亚波伦 RevelationFix）通过注入 {@code setHealth()}
     * 在 {@code actuallyHurt()} 后将血量恢复至损伤前水平。若检测到血量未实际扣除，
     * 强制直写 {@code DataItem.value} 字段绕过 {@code SynchedEntityData.set()} 及一切
     * {@code setHealth()} 覆写拦截。
     * <p>
     * <b>清自定义无敌字段</b>：部分 Boss（如 Goety Apostle）在 {@code actuallyHurt()} 中设置
     * {@code moddedInvul} 等字段，不清除会导致下次 hurt() 检测到 >0 直接 return false 且不调
     * super.hurt()，锁死影杀 NBT 影子血量更新。
     *
     * @param target 目标实体
     * @param effectiveAmount 实际生效的伤害量
     * @param healthBefore actuallyHurt 执行前的血量（用于检测血量是否被恢复）
     */
    public static void afterPierceFallback(LivingEntity target, float effectiveAmount, float healthBefore) {
        if (effectiveAmount > 0.0F && target.getHealth() >= healthBefore && target.isAlive()) {
            HealthUtil.setAllHealthLikeRaw(target, Math.max(0.0F, healthBefore - effectiveAmount));
        }
        InvulClearUtil.clearCustomInvulTimers(target);
    }
}
