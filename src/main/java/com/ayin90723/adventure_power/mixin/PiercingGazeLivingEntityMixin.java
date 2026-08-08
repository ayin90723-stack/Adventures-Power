package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.DamageUtil;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 破敌之眼 Mixin（第二层 + 兜底） - 穿透通过重写 {@code hurt()} 实现的自定义无敌。
 * <p>
 * priority = 2000（高于默认 1000）：本类注入 {@code hurt()} RETURN（穿透结算）必须
 * 先于 {@code RejectHealthManipMixin.onHurtExit}（同一注入点的 HURT_DEPTH 递减）执行——
 * 否则穿透路径 {@code invokeActuallyHurt → setHealth} 时 HURT_DEPTH 已归零，
 * 会被拒绝篡改按"外部篡改"拦截/反弹，伤害走完全不同的结算分支。
 * <p>
 * 第一层 {@link PiercingGazeMixin} 拦截 {@code isInvulnerableTo()} 检查，
 * 处理原版及大多数基于该方法实现的模组无敌。但部分 Boss（如钢铁守护者）直接重写
 * {@code hurt()} 方法，在其内部返回 false 来实现无敌，完全绕过了
 * {@code isInvulnerableTo()}。
 * <p>
 * 本 Mixin 在 {@code hurt()} RETURN 点（{@link #onHurtReturn}）做穿透结算：
 * 若伤害被阻止则通过 {@code actuallyHurt()} 穿透自定义无敌逻辑。
 *
 * <h3>穿透结算段</h3>
 * 实际的伤害直写 / 血量兜底 / 清无敌字段统一由 {@link PiercingGazeUtil} 提供，
 * 与 {@link PiercingGazePlayerAttackMixin}(Layer 0) 共用同一套逻辑，保证一致。
 *
 * <h3>防 LivingHurtEvent 重复 post（核心）</h3>
 * "事件已发"判定统一走 {@link PiercingGazeUtil#peekVanillaHurtEventPosted()}（非消费式）：
 * 单一来源 = {@code CombatAbilityHandler.onLivingHurt} 监听器（任何 post 都触发）。
 * Forge 1.20.1 把 {@code ForgeHooks.onLivingHurt} 的调用点放在 {@code actuallyHurt}
 * （m_6475_）而非 {@code hurt}（m_6469_）——旧 Layer 2.5 @Redirect 在该位置是死靶
 * （require=0 静默失效），已移除。按原版管线是否已结算分两种情况：
 * <ul>
 *   <li><b>已 post（Boss 调 super 走到 actuallyHurt）</b>：事件已结算、伤害可能已扣，
 *       Boss 仅把返回值改为 false（fdbosses 类）-> 放行 + 血量兜底，不重复结算</li>
 *   <li><b>未 post（Boss 完全拦截，未走到 actuallyHurt）</b>：补 post（让淬魂等监听器
 *       正常处理）+ actuallyHurt 直写穿透</li>
 * </ul>
 *
 * <h3>防事件风暴（核心）</h3>
 * 第三方模组若在 LivingHurtEvent 监听器里再调 {@code target.hurt()}（破敌之眼源），
 * 会形成递归事件风暴。{@link #IN_PIERCING} 风暴守卫检测"是否已在外层破敌之眼穿透内"，
 * 手动 post 前置 true（post 期间递归 hurt 不再穿透），阻断递归。
 *
 * <h3>栈式隔离</h3>
 * 两个 ThreadLocal 代表"本层"状态。{@link #onHurtEnter}（HEAD）将外层状态压栈并重置本层，
 * {@link #onHurtReturn}（RETURN）finally 弹栈恢复外层。这样淬魂在 LivingHurtEvent 里
 * 调 {@code soul_strike hurt}（递归）不会污染外层标记。递归 hurt 走原版不设本层标记，
 * 外层标记完整保留。
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class PiercingGazeLivingEntityMixin {

    // ===== actuallyHurt Invoker 已迁移至 PiercingGazeLivingEntityAccessor =====
    // 通过 PiercingGazeUtil.invokeActuallyHurt(...) 间接调用

    // ===== ThreadLocal：本层破敌之眼穿透状态（栈式隔离递归调用） =====

    // 风暴守卫 IN_PIERCING 已迁移至 PiercingGazeUtil（Layer 0/2 共用）：
    // @Mixin 类禁止非 private static 方法，且 Layer 0（PlayerAttackMixin）需要读写同一标记
    /** 本层伤害结算前的真实血量（onHurtEnter 缓存）——RETURN 注入时 actuallyHurt 已执行，
     *  此时 getHealth() 是扣血后值，若用作 afterPierceFallback 的参照会被判定"血量未恢复"
     *  而对普通命中重复直写（双重扣血） */
    private static final ThreadLocal<Float> PIERCING_HEALTH_BEFORE = new ThreadLocal<>();
    // 注：事件已 post 标记（VANILLA_HURT_EVENT_POSTED）与消费方法已移至 PiercingGazeUtil——
    // @Mixin 类禁止非 private static 方法（Mixin Applicator 会尝试混入目标类导致 InvalidMixinException）。
    // 事件已发判定统一走 PiercingGazeUtil.peek/consumeVanillaHurtEventPosted（单一来源 = CombatAbilityHandler 监听器）：
    // Forge 1.20.1 把 ForgeHooks.onLivingHurt 的调用点放在 actuallyHurt（m_6475_）而非 hurt（m_6469_），
    // 旧 Layer 2.5 @Redirect(method="m_6469_", target=onLivingHurt) 是死靶（require=0 静默失效），
    // 导致 PIERCING_EVENT_POSTED 恒 false、对"super 扣血但 return false"的 Boss 重复结算——已移除该 redirect。

    /** 递归调用栈帧：保存外层 (inPiercing, healthBefore) 两态，healthBefore 可为 null */
    private record PiercingStackFrame(boolean inPiercing, Float healthBefore, long postedBase) {}

    /** 递归调用栈：保存外层 (IN_PIERCING, HEALTH_BEFORE) 两态，hurt HEAD 压栈、RETURN 弹栈 */
    private static final ThreadLocal<Deque<PiercingStackFrame>> PIERCING_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "m_6469_", at = @At("HEAD"))
    private void onHurtEnter(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        // 泄漏哨兵：外部模组对 hurt() HEAD cancellable cancel 时 RETURN 不执行、栈帧残留，
        // 残留帧（inPiercing=true）会使风暴守卫永久跳过穿透。递归穿透深度正常远小于 64，
        // 栈深异常必是泄漏——清空恢复，穿透链在下次攻击时自愈。
        Deque<PiercingStackFrame> stack = PIERCING_STACK.get();
        if (stack.size() > 64) {
            stack.clear();
            PiercingGazeUtil.IN_PIERCING.set(false);
            PIERCING_HEALTH_BEFORE.remove();
        }
        // 压栈保存外层状态，重置本层（递归 hurt 不污染外层）。
        // postedBase = 本层开始时的 post 计数：本层"是否已 post" = 当前计数 > postedBase，
        // 嵌套 hurt 的新增 post 只影响其自身基准，外层判定不受污染（计数方案天然栈式隔离，
        // 无需清除/恢复布尔——旧布尔方案嵌套未 post 的 hurt 会清掉外层标记导致外层误判）
        stack.push(new PiercingStackFrame(
            PiercingGazeUtil.IN_PIERCING.get(), PIERCING_HEALTH_BEFORE.get(),
            PiercingGazeUtil.getVanillaHurtEventPostCount()
        ));
        PiercingGazeUtil.IN_PIERCING.set(false);
        // 缓存伤害结算前的真实血量（架空参照读数：自定义血条 Boss 原版槽被架空，
        // getHealthDirect 读到不动值会导致兜底检测永远误判"血量未下降"而双重扣血；
        // 防 getHealth 被 ASM/TrueHealth 篡改则靠架空参照的差值判定回退到 DataItem）
        LivingEntity self = (LivingEntity) (Object) this;
        PIERCING_HEALTH_BEFORE.set(HealthUtil.getEffectiveHealth(self));
    }

    /**
     * 在 {@code hurt()} 返回 false 时做兜底检查：
     * 若攻击者持有破敌之眼，按情况 A/C 决定是否补 post LivingHurtEvent，再调用
     * {@code actuallyHurt()} 穿透自定义无敌逻辑。
     *
     * <h3>手动发事件的副作用</h3>
     * <ul>
     *   <li><b>其他模组的 LivingHurtEvent 监听器</b>：也会收到此事件，可能做出意料之外的
     *       响应（如记录伤害统计、触发额外效果）。但由于攻击确实发生了，这属于合理的副作用。</li>
     *   <li><b>淬魂递归保护</b>：淬魂内部调用 {@code target.hurt(soul_strike_source)}
     *       时会再次触发本 Mixin，但 {@code isMmeInternalSource} 会直接 return，
     *       且栈式隔离保证递归不清外层标记，不会无限递归。</li>
     *   <li><b>事件取消</b>：即使其他 mod 在监听器中取消了事件，破敌之眼仍会穿透 -
     *       作为万能钥匙，不受外部事件取消的影响。</li>
     * </ul>
     */
    @Inject(
        method = "m_6469_",
        at = @At("RETURN"),
        cancellable = true
    )
    private void onHurtReturn(DamageSource source, float amount,
                              CallbackInfoReturnable<Boolean> cir) {
        try {
            // 只在服务端侧处理：BetterCombat 等模组会在客户端侧调用 player.attack()
            // 做攻击预测，导致 hurt() 在 ClientLevel 上执行。若手动 post LivingHurtEvent
            // 会触发其他模组（如 ElementalCombat）的 ClientLevel->ServerLevel 强转崩溃。
            // 真正的伤害穿透由服务端侧的同一次 Mixin 触发完成，客户端侧跳过即可。
            LivingEntity self = (LivingEntity)(Object)this;
            if (self.level().isClientSide()) {
                return;
            }

            // 风暴守卫：外层已在破敌之眼穿透内（栈顶 IN_PIERCING=true）-> 本层是递归 hurt，跳过穿透逻辑防风暴。
            // redirect 已不 post 事件；此处跳过补 post / actuallyHurt / 血量检测 / 清无敌，
            // 否则情况 A 补 post 会再次触发监听器递归
            Deque<PiercingStackFrame> stackCheck = PIERCING_STACK.get();
            PiercingStackFrame outerCheck = stackCheck.peek();
            if (outerCheck != null && outerCheck.inPiercing()) {
                return;
            }

            // 反重入：MME 内部穿透伤害（soul_strike / vengeance）走原版管线，
            // 外层破敌之眼的 hurt() 已处理过穿透+清除自定义无敌计时器，内层无需重复。
            // 使用精确 msgId 匹配而非 BYPASSES_INVULNERABILITY 标签检查，
            // 避免将 RevelationFix fe_power 误判为 MME 内部调用。
            if (DamageUtil.isInternalSource(source)) {
                return;
            }

            // 穿透门禁统一入口：攻击者持破敌之眼 + 非友伤 + 非玩家目标（PVP 禁用）
            if (PiercingGazeUtil.shouldPierce(source, self)) {

                boolean wasBlocked = !cir.getReturnValue();
                // 本层是否已 post LivingHurtEvent（当前计数 > 本层基准）。
                // 单一来源 = CombatAbilityHandler 监听器 mark；Forge 1.20.1 的
                // ForgeHooks.onLivingHurt 在 actuallyHurt（m_6475_）内调用——
                // hurt() 正常走到 actuallyHurt 即已 post。计数方案天然栈式隔离：
                // 嵌套 hurt 的 post 只影响其自身基准，本层基准 = 栈帧压栈时记录的值
                PiercingStackFrame frame = PIERCING_STACK.get().peek();
                // per-entity 判定：只认本实体自身的 post 增量（v1.3.6）——
                // 纯计数会被嵌套实体（Boss 在 hurt 内对另一实体 AOE）的事件增量污染，
                // 导致本层误判"已 post"而跳过补 post + actuallyHurt（淬魂等丢失结算机会）
                boolean posted = PiercingGazeUtil.peekVanillaHurtEventPosted(
                    frame != null ? frame.postedBase() : 0L, self);
                // 伤害结算前的真实血量（onHurtEnter 缓存）——RETURN 时 actuallyHurt 已执行，
                // self.getHealth() 是扣血后值，若用它作参照，普通命中会被判定"血量未恢复"
                // 而触发 afterPierceFallback 的兜底直写，造成双重扣血。
                // 用于检测 Boss 是否通过注入 setHealth() 在伤害后恢复血量
                // （如亚波伦 RevelationFix 的 redirectSetHealth）。
                float healthBefore = PIERCING_HEALTH_BEFORE.get() != null
                    ? PIERCING_HEALTH_BEFORE.get() : self.getHealth();

                if (wasBlocked) {
                    if (posted) {
                        // 原版管线已完整结算（actuallyHurt → onLivingHurt → 扣血），Boss 仅把
                        // 返回值改为 false（"调 super 扣血但 return false"类，如 fdbosses）——
                        // 放行 + 血量兜底即可，重复 post/actuallyHurt 会双倍结算
                        //（旧 2.5 redirect 死靶时 posted 恒 false，此分支从不命中导致双倍）。
                        cir.setReturnValue(true);
                        PiercingGazeUtil.afterPierceFallback(self, amount, healthBefore);
                        // 穿透反馈（posted+blocked = Boss 拦截后原版管线已结算的穿透）
                        PiercingGazeUtil.pierceFeedback(self);
                    } else {
                        // 情况 A：Boss 完全拦截 hurt()（未走到 actuallyHurt，事件未 post）
                        // -> 补 post LivingHurtEvent（让淬魂等监听器正常处理）+ actuallyHurt 直写。
                        // 事件已发标记由 CombatAbilityHandler.onLivingHurt 监听器统一负责
                        //（任何 post 都触发该监听器），此处不再显式 mark
                        // 风暴守卫：post 期间第三方监听器用同一源递归 target.hurt() 时，
                        // 递归层 HEAD 压栈捕获到本层 IN_PIERCING=true 即可跳过穿透阻断递归
                        PiercingGazeUtil.IN_PIERCING.set(true);
                        float effectiveAmount = PiercingGazeUtil.postHurtEvent(self, source, amount);
                        // actuallyHurt 直写 + 血量兜底 + 清无敌字段
                        PiercingGazeUtil.invokeActuallyHurt(self, source, effectiveAmount);
                        cir.setReturnValue(true);
                        PiercingGazeUtil.afterPierceFallback(self, effectiveAmount, healthBefore);
                        // 穿透反馈（情况 A = Boss 完全拦截后的补 post + actuallyHurt 穿透）
                        PiercingGazeUtil.pierceFeedback(self);
                    }
                } else {
                    // 情况 B：正常流程，actuallyHurt 已由原版管线执行（事件已按其结算），
                    // 只做兜底（不重复 actuallyHurt / post）
                    PiercingGazeUtil.afterPierceFallback(self, amount, healthBefore);
                }
            }
        } finally {
            // 弹栈恢复外层状态（递归 hurt 不污染外层）
            Deque<PiercingStackFrame> stack = PIERCING_STACK.get();
            PiercingStackFrame outer = stack.poll();
            if (outer != null) {
                PiercingGazeUtil.IN_PIERCING.set(outer.inPiercing());
                if (outer.healthBefore() != null) {
                    PIERCING_HEALTH_BEFORE.set(outer.healthBefore());
                } else {
                    PIERCING_HEALTH_BEFORE.remove();
                }
            } else {
                // 栈空（最外层 hurt 退出）-> 彻底清理，防 ThreadLocal 泄漏
                PiercingGazeUtil.IN_PIERCING.remove();
                PIERCING_HEALTH_BEFORE.remove();
            }
        }
    }
}
