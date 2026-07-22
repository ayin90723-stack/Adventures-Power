package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.util.FriendlyFireProtection;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.InvulClearUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 破敌之眼 Mixin（第二层 + 兜底） - 穿透通过重写 {@code hurt()} 实现的自定义无敌。
 * <p>
 * 第一层 {@link PiercingGazeMixin} 拦截 {@code isInvulnerableTo()} 检查，
 * 处理原版及大多数基于该方法实现的模组无敌。但部分 Boss（如钢铁守护者）直接重写
 * {@code hurt()} 方法，在其内部返回 false 来实现无敌，完全绕过了
 * {@code isInvulnerableTo()}。
 * <p>
 * 本 Mixin 包含两个注入点：
 * <ul>
 *   <li><b>Layer 2.5</b> - {@link #redirectOnLivingHurt}：{@code @Redirect} 替换
 *        {@code ForgeHooks.onLivingHurt()} 调用，手动 post {@link LivingHurtEvent}
 *        （让淬魂等附魔正常追加伤害），但强制返回值不低于原始伤害量 -
 *        Boss 限伤只能让伤害涨不能降。</li>
 *   <li><b>Layer 2</b> - {@link #onHurtReturn}：{@code @Inject} 注入
 *        {@code hurt()} 的 RETURN 点，若伤害被阻止则通过 {@code actuallyHurt()}
 *        穿透自定义无敌逻辑。</li>
 * </ul>
 *
 * <h3>防 LivingHurtEvent 重复 post（核心）</h3>
 * 两个注入点都可能 post LivingHurtEvent，必须避免同一次 hurt() 调用内重复 post
 * （否则淬魂追加伤害 / 嗜血吸血 / 影杀影子血量等所有监听器触发两次）。
 * 按 hurt() 被 Boss 拦截的位置分三种情况：
 * <ul>
 *   <li><b>A 完全拦截</b>（Boss 不调 super return false）：redirect 未执行 ->
 *       onHurtReturn 补 post 一次</li>
 *   <li><b>B 正常流程</b>（super 走完，actuallyHurt 执行，return true）：
 *       redirect post 一次，onHurtReturn 不再 post</li>
 *   <li><b>C 中途拦截</b>（走到 onLivingHurt 后 return false）：redirect post 过 ->
 *       onHurtReturn 不再 post，直接用 redirect 的 max amount 补 actuallyHurt</li>
 * </ul>
 * 通过 {@link #PIERCING_EVENT_POSTED} 标记区分 A 与 C。
 *
 * <h3>防事件风暴（核心）</h3>
 * 第三方模组若在 LivingHurtEvent 监听器里再调 {@code target.hurt()}（破敌之眼源），
 * 会形成递归事件风暴。{@link #IN_PIERCING} 风暴守卫检测"是否已在外层破敌之眼穿透内"，
 * 若是则走原版 ForgeHooks.onLivingHurt（不手动 post），阻断递归。
 *
 * <h3>栈式隔离</h3>
 * 三个 ThreadLocal 代表"本层"状态。{@link #onHurtEnter}（HEAD）将外层状态压栈并重置本层，
 * {@link #onHurtReturn}（RETURN）finally 弹栈恢复外层。这样淬魂在 LivingHurtEvent 里
 * 调 {@code soul_strike hurt}（递归）不会污染外层标记。递归 hurt 走原版不设本层标记，
 * 外层标记完整保留。
 */
@Mixin(LivingEntity.class)
public abstract class PiercingGazeLivingEntityMixin {

    // ===== actuallyHurt Invoker 已迁移至 PiercingGazeLivingEntityAccessor =====
    // 通过 ((PiercingGazeLivingEntityAccessor)this).invokeActuallyHurt(...) 调用

    // ===== ThreadLocal：本层破敌之眼穿透状态（栈式隔离递归调用） =====

    /** 风暴守卫：本层是否已在破敌之眼穿透内（redirect 手动 post 后置 true） */
    private static final ThreadLocal<Boolean> IN_PIERCING = ThreadLocal.withInitial(() -> false);
    /** 本层 LivingHurtEvent 是否已被 redirect 手动 post 过（区分情况 A 与 C） */
    private static final ThreadLocal<Boolean> PIERCING_EVENT_POSTED = ThreadLocal.withInitial(() -> false);
    /** redirect 取 max 后的有效伤害量，供情况 C 补 actuallyHurt 时使用 */
    private static final ThreadLocal<Float> PIERCING_EFFECTIVE_AMOUNT = new ThreadLocal<>();

    /** 递归调用栈：保存外层 (IN_PIERCING, POSTED, AMOUNT) 三态，hurt HEAD 压栈、RETURN 弹栈 */
    private static final ThreadLocal<Deque<Object[]>> PIERCING_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "m_6469_", at = @At("HEAD"))
    private void onHurtEnter(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        // 压栈保存外层状态，重置本层（递归 hurt 不污染外层）
        PIERCING_STACK.get().push(new Object[]{
            IN_PIERCING.get(), PIERCING_EVENT_POSTED.get(), PIERCING_EFFECTIVE_AMOUNT.get()
        });
        IN_PIERCING.set(false);
        PIERCING_EVENT_POSTED.set(false);
        PIERCING_EFFECTIVE_AMOUNT.remove();
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
            Deque<Object[]> stackCheck = PIERCING_STACK.get();
            Object[] outerCheck = stackCheck.peek();
            if (outerCheck != null && (Boolean) outerCheck[0]) {
                return;
            }

            // 追溯攻击者：直接实体 -> 间接实体 -> 弹射物发射者
            Entity attacker = source.getEntity();
            if (attacker == null) {
                attacker = source.getDirectEntity();
            }
            if (attacker instanceof Projectile projectile) {
                attacker = projectile.getOwner();
            }

            // 反重入：MME 内部穿透伤害（soul_strike / vengeance）走原版管线，
            // 外层破敌之眼的 hurt() 已处理过穿透+清除自定义无敌计时器，内层无需重复。
            // 使用精确 msgId 匹配而非 BYPASSES_INVULNERABILITY 标签检查，
            // 避免将 RevelationFix fe_power 误判为 MME 内部调用。
            if (isMmeInternalSource(source)) {
                return;
            }

            // 攻击者持有破敌之眼 -> 穿透该实体的一切无敌手段
            if (attacker instanceof LivingEntity living && hasPiercingGaze(living)) {
                // 友好火力保护：不穿透自己驯服生物的无敌
                if (FriendlyFireProtection.isOwnerTarget(living, self)) {
                    return;
                }

                boolean wasBlocked = !cir.getReturnValue();
                boolean posted = PIERCING_EVENT_POSTED.get();
                float effectiveAmount;
                // 在 actuallyHurt 执行前捕捉血量，用于检测 Boss 是否通过注入
                // setHealth() 在伤害后恢复了血量（如亚波伦 RevelationFix 的 redirectSetHealth）
                float healthBefore = self.getHealth();

                if (wasBlocked) {
                    if (posted) {
                        // 情况 C：redirect 已 post 过 LivingHurtEvent，用其取 max 后的有效伤害量补 actuallyHurt
                        effectiveAmount = PIERCING_EFFECTIVE_AMOUNT.get();
                    } else {
                        // 情况 A：Boss 完全拦截 hurt()（未走到 ForgeHooks.onLivingHurt），redirect 未触发
                        // -> 补 post LivingHurtEvent 让淬魂等附魔正常处理
                        LivingHurtEvent hurtEvent = new LivingHurtEvent(self, source, amount);
                        MinecraftForge.EVENT_BUS.post(hurtEvent);
                        effectiveAmount = hurtEvent.getAmount();
                    }
                    // 不检查事件是否被取消 - 破敌之眼作为万能钥匙，不受其他 mod 取消事件的影响
                    ((PiercingGazeLivingEntityAccessor)this).invokeActuallyHurt(source, effectiveAmount);
                    cir.setReturnValue(true);
                } else {
                    // 情况 B：正常流程，actuallyHurt 已由原版管线执行
                    // effectiveAmount 取 redirect 后的有效值（含淬魂追加），用于血量检测基准
                    effectiveAmount = posted ? PIERCING_EFFECTIVE_AMOUNT.get() : amount;
                }

                // 无论 hurt() 返回 true 还是 false，部分 Boss（如亚波伦 RevelationFix）
                // 通过注入 setHealth() 在 actuallyHurt() 后将血量恢复至损伤前水平。
                // 若检测到血量未被实际扣除，强制直写 DataItem.value 字段
                // 绕过 SynchedEntityData.set() 及一切 setHealth() 覆写拦截。
                if (effectiveAmount > 0.0F && self.getHealth() >= healthBefore && self.isAlive()) {
                    HealthUtil.setAllHealthLikeRaw(self, Math.max(0.0F, healthBefore - effectiveAmount));
                }

                // 无论伤害是否被阻止，只要攻击者持有破敌之眼就清除目标的自定义无敌计时器。
                // 部分 Boss（如 Goety Apostle）在 actuallyHurt() 中设置 moddedInvul 等字段，
                // 若不清除，下一次 hurt() 检测到 >0 会直接 return false 且不调用
                // super.hurt()，导致 LivingEntity.hurt() 不被调用、本 Mixin 不再触发、
                // 影杀 的 NBT 影子血量也无法更新。
                InvulClearUtil.clearCustomInvulTimers(self);
            }
        } finally {
            // 弹栈恢复外层状态（递归 hurt 不污染外层）
            Deque<Object[]> stack = PIERCING_STACK.get();
            Object[] outer = stack.poll();
            if (outer != null) {
                IN_PIERCING.set((Boolean) outer[0]);
                PIERCING_EVENT_POSTED.set((Boolean) outer[1]);
                Float outerAmt = (Float) outer[2];
                if (outerAmt != null) {
                    PIERCING_EFFECTIVE_AMOUNT.set(outerAmt);
                } else {
                    PIERCING_EFFECTIVE_AMOUNT.remove();
                }
            } else {
                // 栈空（最外层 hurt 退出）-> 彻底清理，防 ThreadLocal 泄漏
                IN_PIERCING.remove();
                PIERCING_EVENT_POSTED.remove();
                PIERCING_EFFECTIVE_AMOUNT.remove();
            }
        }
    }

    /**
     * Layer 2.5 - 绕过 LivingHurtEvent 中的 Boss 限伤。
     * <p>
     * 正常流程中 {@code ForgeHooks.onLivingHurt()} 会 post {@link LivingHurtEvent}，
     * Boss 限伤模组在此事件中通过 {@code setAmount()} 压低伤害值。
     * 本 Redirect 在破敌之眼攻击下替换该调用：
     * <ul>
     *   <li>手动 post LivingHurtEvent（让淬魂/影杀 正常追加百分比伤害）</li>
     *   <li>返回值取 {@code Math.max(原始值, 事件值)} - 破敌之眼下伤害只能涨不能降</li>
     *   <li>设 {@link #PIERCING_EVENT_POSTED} 标记，告知 onHurtReturn 已 post 过</li>
     *   <li>风暴守卫：检测栈顶外层 IN_PIERCING，若外层已在破敌之眼穿透内则走原版，防事件风暴</li>
     * </ul>
     * 不取消事件是因为淬魂需在 LivingHurtEvent 中正常计算百分比伤害和写入 NBT。
     *
     * <p>目标 {@code ForgeHooks.onLivingHurt} 为 Forge 自有静态方法，
     * 不受原版字节码混淆影响，无需 refmap 条目。</p>
     */
    @Redirect(
        method = "m_6469_",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/common/ForgeHooks;onLivingHurt(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)F"
        ),
        require = 0
    )
    private float redirectOnLivingHurt(LivingEntity entity, DamageSource source, float amount) {
        // 反重入：MME 内部穿透伤害（soul_strike / vengeance）走原版管线。
        // 使用精确 msgId 匹配而非 BYPASSES_INVULNERABILITY 标签检查，
        // 避免将 RevelationFix fe_power 误判为 MME 内部调用。
        if (isMmeInternalSource(source)) {
            return ForgeHooks.onLivingHurt(entity, source, amount);
        }

        // 非破敌之眼攻击 -> 走原版管线
        if (!isPiercingGazeAttack(source, entity)) {
            return ForgeHooks.onLivingHurt(entity, source, amount);
        }

        // 风暴守卫：外层已在破敌之眼穿透内（栈顶 IN_PIERCING=true）-> 不 post 事件，直接返回原 amount。
        // 走原版 ForgeHooks.onLivingHurt 仍会 post 事件触发监听器导致递归，无法防风暴；
        // 返回 amount 跳过 post，递归 hurt 不触发 LivingHurtEvent 监听器，打破递归链
        Deque<Object[]> stack = PIERCING_STACK.get();
        Object[] outer = stack.peek();
        if (outer != null && (Boolean) outer[0]) {
            return amount;
        }

        // 手动 post LivingHurtEvent - 让淬魂等 MME 效果正常处理
        LivingHurtEvent event = new LivingHurtEvent(entity, source, amount);
        MinecraftForge.EVENT_BUS.post(event);

        // 伤害只能涨不能降：淬魂追加的伤害保留，Boss 限伤被忽略
        float effective = Math.max(amount, event.getAmount());

        // 标记本层已 post，供 onHurtReturn 区分情况 A/C
        IN_PIERCING.set(true);
        PIERCING_EVENT_POSTED.set(true);
        PIERCING_EFFECTIVE_AMOUNT.set(effective);
        return effective;
    }

    /**
     * 检查攻击源是否来自破敌之眼持有者，且非自身/友好火力/重入调用。
     */
    @Unique
    private static boolean isPiercingGazeAttack(DamageSource source, LivingEntity target) {
        // 追溯到真正的攻击者
        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        if (attacker instanceof Projectile projectile) {
            attacker = projectile.getOwner();
        }
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
     * 检查 LivingEntity 主手或副手物品是否拥有破敌之眼附魔，
     * 且（对于玩家）是否佩戴了冒险的终点饰品。
     */
    @Unique
    private static boolean hasPiercingGaze(LivingEntity entity) {
        return AdventurePower.hasPiercingGaze(entity)
            && (!(entity instanceof Player player)
                || AdventureProgressCapability.isAbilityAvailable(player, "piercing_gaze"));
    }

    /**
     * 检查伤害源是否为 MME 内部穿透伤害类型，用于反重入保护。
     * <p>
     * MME 的淬魂/复仇等附魔在处理器内部构造 DamageSource 并调用
     * {@code target.hurt()}，这会再次触发本 Mixin。外层破敌之眼已处理过
     * 穿透与清除自定义无敌计时器，内层无需重复。
     * <p>
     * 使用精确的 DamageType message_id 匹配（{@code mme.soul_strike} /
     * {@code mme.vengeance}）而非 {@code BYPASSES_INVULNERABILITY} 标签，
     * 避免将其他模组加入该标签的伤害类型
     * （如 RevelationFix 的 {@code fe_power}）误判为 MME 内部调用。
     */
    @Unique
    private static boolean isMmeInternalSource(DamageSource source) {
        String msgId = source.getMsgId();
        return "soul_strike".equals(msgId) || "judgment".equals(msgId);
    }
}
