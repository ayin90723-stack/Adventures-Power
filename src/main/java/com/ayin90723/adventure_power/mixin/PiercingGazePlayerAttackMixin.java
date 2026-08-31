package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 破敌之眼 Layer 0 - 穿透通过重写 {@code hurt()} 且不调用 {@code super.hurt()} 实现的自定义无敌。
 * <p>
 * 第一层 {@link PiercingGazeMixin} 拦截 {@code isInvulnerableTo()}、第二层
 * {@link PiercingGazeLivingEntityMixin} 拦截 {@code LivingEntity.hurt()} 的 RETURN，
 * 但部分 Boss（如暮色森林巫妖、Mowzie 钢铁守护者）完全重写 {@code hurt()} 方法，在自身护盾/
 * vulnerable 检查失败后直接 {@code return false}，从不调用 {@code super.hurt()}。
 * 这导致 {@code LivingEntity.hurt()} 内的所有注入点（含 Layer 2 / Layer 2.5）永远不会触发。
 * <p>
 * 本 Mixin 在 {@link Player#attack(Entity)} 中拦截 {@code target.hurt()} 调用：
 * <ul>
 *   <li>先正常调用 {@code target.hurt(source, amount)}</li>
 *   <li>若返回 false 且攻击者持有破敌之眼 -> 手动触发 {@code LivingHurtEvent}
 *       （让淬魂等附魔正常追加伤害），再通过 {@code actuallyHurt} + 血量直写兜底穿透</li>
 *   <li>返回 true，确保原版击退/火焰附加等附魔效果正常执行</li>
 * </ul>
 * <p>
 * 与 Layer 2 配合：若 {@code hurt()} 内部调用了 {@code super.hurt()}，Layer 2
 * 已通过 {@code cir.setReturnValue(true)} 将返回值改为 true，本 Mixin 看到 true
 * 时直接放行，不会重复处理。
 *
 * <h3>与战斗优化模组的兼容性</h3>
 * 本层注入 {@code Player.attack}，属"攻击发起侧"。BetterCombat / Epic Fight 等
 * 战斗优化模组虽在客户端接管攻击流程（各自动画系统，cancel 原版 doAttack），
 * 但服务端最终仍调用原版 {@code Player.attack}（BetterCombat 经 ServerNetwork 收包后调用，
 * Epic Fight 经 PlayerPatch.attack 调用），故本层 {@code @Redirect} 正常触发。
 * 两个模组均未 {@code @Redirect target.hurt}，无 Mixin 冲突，默认 priority 无需调整。
 * <p>
 * 穿透判定（血量比对）：真成功 = target.hurt 返回 true 且实际扣血（healthAfter < healthBefore）。
 * 覆盖两类 Boss：① 重写 hurt return false 不调 super；② 重写 hurt return true 假成功未扣血。
 * 两种都走穿透三连（postHurtEvent + actuallyHurt + 血量直写兜底）。
 *
 * <h3>调用点竞争与让位定案（2026-08-28 两轮实测）</h3>
 * 整合包核心 chapter_of_yuusha_3_core 的 minecraft.PlayerMixin 对同一调用点
 * （Player.attack 内 target.hurt）挂了同优先级（1000）@Redirect（透传原版 + 主手
 * 无限剑时 hurtEnemy 补刀）。Mixin 的 @Redirect 独占调用点：同优先级时后注册者整体
 * Skipped——本层在该整合包中失效（WARN，因本层 require=0 不致命）。
 * <p>
 * 曾尝试 priority 1100 抢先应用（对方让位），实测**启动即崩**：对方 mixin 配置
 * {@code "injectors": {"defaultRequire": 1}}，其 @Redirect 被顶掉后 0/1 注入成功，
 * Mixin 判定 Critical injection failure 抛 MixinTransformerError（日志实锤）。
 * 对方未写 require=0，抢占路线在对方不配合的前提下不可行——定案保持默认优先级
 * 让位：本层在该整合包中不生效（Layer 1/2 仍完整覆盖其余穿透场景），其他无冲突
 * 环境不受影响。
 *
 * @see PiercingGazeMixin
 * @see PiercingGazeLivingEntityMixin
 * @see PiercingGazeUtil
 */
@Mixin(Player.class)
public class PiercingGazePlayerAttackMixin {

    /**
     * 替换 {@code Player.attack()} 内部的 {@code target.hurt(source, amount)} 调用。
     * <p>
     * 目标 {@code Entity.hurt} 为实例方法，SRG 名 {@code m_6469_}。
     * 使用 {@code require = 0} 避免因字节码结构变化导致注入失败；
     * {@code expect = 1} 让注入点缺失时在日志输出 WARN（v1.4.0 起，替代纯静默失效，
     * 便于启动后排查 Layer 0 是否被其它模组改写）。
     */
    @Redirect(
        method = "m_5706_",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z"
        ),
        require = 0,
        expect = 1
    )
    private boolean redirectAttackHurt(Entity target, DamageSource source, float amount) {
        // v1.4.8：主体公共化至 PiercingGazeUtil.interceptAttackHurt（与弹射物侧
        // PiercingGazeArrowMixin 共用），本类只保留调用点锚定
        return PiercingGazeUtil.interceptAttackHurt((Player)(Object)this, target, source, amount);
    }
}
