package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
        Player self = (Player)(Object)this;
        // 仅在服务端处理，客户端侧走原版管线
        if (self.level().isClientSide()) {
            return target.hurt(source, amount);
        }

        // 穿透逻辑仅对 LivingEntity 有意义；非 LivingEntity 直接走原版
        if (!(target instanceof LivingEntity living)) {
            return target.hurt(source, amount);
        }

        // 架空参照读数：自定义血条 Boss（亚波伦）原版槽被架空，扣血检测必须用真实血量，
        // 否则普通命中也会被误判"未扣血"而恒走穿透三连（满额直写、数值错位）
        float healthBefore = HealthUtil.getEffectiveHealth(living);
        // 本次 attack 作用域隔离：记录 post 计数基线，consume 只反映"本次 hurt 期间"的新增
        //（环境噪声 hurt（怪物互殴等）在两次 attack 之间的 post 计入基线，不会被误消费）
        PiercingGazeUtil.beginVanillaHurtScope();
        boolean hurtResult = target.hurt(source, amount);

        // 实际扣血就放行（不管 hurtResult 真假）。用 getEffectiveHealth 直读真实血量，
        // 防 Boss 用 ASM/Mixin 改写 getHealth() 返回假值（Fantasy Ending delta 式）。
        // 覆盖：① 普攻原版怪 ② fdbosses 调 super 扣血但 return false ③ Boss 假成功/拦截
        if (HealthUtil.getEffectiveHealth(living) < healthBefore) {
            // 扣血了，放行。仅当原版管线未 post 事件时补发 LivingHurtEvent——
            // 正常环境 hurt() 内 ForgeHooks.onLivingHurt（或 Layer 2.5 手动 post）已发过，
            // 重复补发会让淬魂/嗜血/禁疗等监听器同 tick 双倍结算（影杀已有 SHADOW_KILL_TICKED 去重）。
            // 消费式读取：标记只反映本次 hurt；ASM 跳过 ForgeHooks 的环境（fantasy_ending 等）标记为 false 仍需补发
            if (!PiercingGazeUtil.consumeVanillaHurtEventPosted(living)) {
                PiercingGazeUtil.postHurtEvent(living, source, amount);
            }
            return true;
        }

        // 否则（返回 false / return true 假成功未扣血）-> 穿透门禁统一入口：
        // 攻击者持破敌之眼 + 非友伤 + 非玩家目标（PVP 禁用）
        if (!PiercingGazeUtil.shouldPierce(source, living)) {
            // 非破敌之眼/友伤/PVP：穿透不适用，但消费残留标记（本次 hurt 未走原版管线时置 false），
            // 防止下次扣血攻击在 ASM 跳过 ForgeHooks 的环境误判"已 post"而不补发
            PiercingGazeUtil.consumeVanillaHurtEventPosted(living);
            return hurtResult;
        }

        // 穿透结算三连（与 Layer 2 情况 A 完全一致）：
        // 1. post LivingHurtEvent（取 max 防限伤，让淬魂/影杀 正常追加伤害）
        // 2. actuallyHurt 直写（绕过 hurt 内护甲/无敌判定）
        // 3. 血量直写兜底 + 清自定义无敌字段（防 Boss 注入 setHealth 恢复 / 锁死影杀 NBT）
        // 风暴守卫：post 期间第三方监听器递归 target.hurt() 时，递归层 HEAD 压栈
        // 捕获到本层 IN_PIERCING=true 即可跳过穿透阻断递归（与 Layer 2 情况 A 同款，
        // 覆盖 Layer 0 手动 post 的补发路径）。
        // finally 恢复旧值而非硬置 false：本层 post 若发生在外层 Layer 2 情况 A 的
        // 监听器链内（监听器调 player.attack），硬置 false 会清掉外层风暴守卫
        // 直到外层弹栈——恢复旧值保持守卫连续
        boolean prevInPiercing = PiercingGazeUtil.IN_PIERCING.get();
        PiercingGazeUtil.IN_PIERCING.set(true);
        try {
            float effective = PiercingGazeUtil.postHurtEvent(living, source, amount);
            PiercingGazeUtil.invokeActuallyHurt(living, source, effective);
            PiercingGazeUtil.afterPierceFallback(living, effective, healthBefore);
            // 穿透反馈（穿透三连 = 真穿透；同目标同 tick 节流防三连刷屏）
            PiercingGazeUtil.pierceFeedback(living);
        } finally {
            PiercingGazeUtil.IN_PIERCING.set(prevInPiercing);
        }

        return true; // 返回 true 让击退/火焰附加等附魔正常执行
    }
}
