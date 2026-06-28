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
import net.minecraft.world.item.enchantment.EnchantmentHelper;
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

/**
 * 见既斩 Mixin（第二层 + 兜底） — 穿透通过重写 {@code hurt()} 实现的自定义无敌。
 * <p>
 * 第一层 {@link SeeAndSlashMixin} 拦截 {@code isInvulnerableTo()} 检查，
 * 处理原版及大多数基于该方法实现的模组无敌。但部分 Boss（如钢铁守护者）直接重写
 * {@code hurt()} 方法，在其内部返回 false 来实现无敌，完全绕过了
 * {@code isInvulnerableTo()}。
 * <p>
 * 本 Mixin 包含两个注入点：
 * <ul>
 *   <li><b>Layer 2.5</b> — {@link #redirectOnLivingHurt}：{@code @Redirect} 替换
 *        {@code ForgeHooks.onLivingHurt()} 调用，手动 post {@link LivingHurtEvent}
 *        （让淬魂等附魔正常追加伤害），但强制返回值不低于原始伤害量 —
 *        Boss 限伤只能让伤害涨不能降。</li>
 *   <li><b>Layer 2</b> — {@link #onHurtReturn}：{@code @Inject} 注入
 *        {@code hurt()} 的 RETURN 点，若伤害被阻止则通过 {@code actuallyHurt()}
 *        穿透自定义无敌逻辑。</li>
 * </ul>
 * <p>
 * 关于手动发事件的副作用说明见 {@link #onHurtReturn}。
 */
@Mixin(LivingEntity.class)
public abstract class SeeAndSlashLivingEntityMixin {

    // ===== actuallyHurt Invoker 已迁移至 SeeAndSlashLivingEntityAccessor =====
    // 通过 ((SeeAndSlashLivingEntityAccessor)this).invokeActuallyHurt(...) 调用

    /**
     * 在 {@code hurt()} 返回 false 时做兜底检查：
     * 若攻击者持有见既斩，先手动触发 {@link LivingHurtEvent} 让所有
     * 附魔效果（淬魂、淬魂Plus 等）正常计算伤害加成，再调用
     * {@code actuallyHurt()} 穿透自定义无敌逻辑。
     *
     * <h3>手动发事件的副作用</h3>
     * <ul>
     *   <li><b>其他模组的 LivingHurtEvent 监听器</b>：也会收到此事件，可能做出意料之外的
     *       响应（如记录伤害统计、触发额外效果）。但由于攻击确实发生了，这属于合理的副作用。</li>
     *   <li><b>淬魂递归保护</b>：淬魂内部调用 {@code target.hurt(soul_strike_source)}
     *       时也会再次触发本 Mixin 并 Post 第二次 LivingHurtEvent，但淬魂的
     *       {@code BYPASSES_INVULNERABILITY} 反重入守卫会跳过处理，不会无限递归。</li>
     *   <li><b>事件取消</b>：即使其他 mod 在监听器中取消了事件，见既斩仍会穿透 —
     *       作为万能钥匙，不受外部事件取消的影响。</li>
     *   <li><b>伤害倍率修改</b>：淬魂通过 {@code event.setAmount()} 追加的伤害
     *       会被传入 {@code actuallyHurt()}，确保淬魂加成生效。</li>
     * </ul>
     */
    @Inject(
        method = "m_6469_",
        at = @At("RETURN"),
        cancellable = true
    )
    private void onHurtReturn(DamageSource source, float amount,
                              CallbackInfoReturnable<Boolean> cir) {
        // 只在服务端侧处理：BetterCombat 等模组会在客户端侧调用 player.attack()
        // 做攻击预测，导致 hurt() 在 ClientLevel 上执行。若手动 post LivingHurtEvent
        // 会触发其他模组（如 ElementalCombat）的 ClientLevel→ServerLevel 强转崩溃。
        // 真正的伤害穿透由服务端侧的同一次 Mixin 触发完成，客户端侧跳过即可。
        LivingEntity self = (LivingEntity)(Object)this;
        if (self.level().isClientSide()) {
            return;
        }

        // 追溯攻击者：直接实体 → 间接实体 → 弹射物发射者
        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        if (attacker instanceof Projectile projectile) {
            attacker = projectile.getOwner();
        }

        // 反重入：MME 内部穿透伤害（soul_strike / vengeance）走原版管线，
        // 外层见既斩的 hurt() 已处理过穿透+清除自定义无敌计时器，内层无需重复。
        // 使用精确 msgId 匹配而非 BYPASSES_INVULNERABILITY 标签检查，
        // 避免将 RevelationFix fe_power 误判为 MME 内部调用。
        if (isMmeInternalSource(source)) {
            return;
        }

        // 攻击者持有见既斩 → 穿透该实体的一切无敌手段
        if (attacker instanceof LivingEntity living && hasSeeAndSlash(living)) {
            // 友好火力保护：不穿透自己驯服生物的无敌
            if (FriendlyFireProtection.isOwnerTarget(living, self)) {
                return;
            }

            boolean wasBlocked = !cir.getReturnValue();
            float effectiveAmount = amount;
            // 在 actuallyHurt 执行前捕捉血量，用于检测 Boss 是否通过注入
            // setHealth() 在伤害后恢复了血量（如亚波伦 RevelationFix 的 redirectSetHealth）
            float healthBefore = self.getHealth();

            if (wasBlocked) {
                // 伤害被阻止时手动触发 LivingHurtEvent，
                // 让淬魂等附魔效果能在冷却穿透时正常处理。
                // 不检查事件是否被取消 — 见既斩作为万能钥匙，
                // 不受其他 mod 取消事件的影响。
                LivingHurtEvent hurtEvent = new LivingHurtEvent(self, source, amount);
                MinecraftForge.EVENT_BUS.post(hurtEvent);
                effectiveAmount = hurtEvent.getAmount();
                ((SeeAndSlashLivingEntityAccessor)this).invokeActuallyHurt(source, effectiveAmount);
                cir.setReturnValue(true);
            }

            // 无论 hurt() 返回 true 还是 false，部分 Boss（如亚波伦 RevelationFix）
            // 通过注入 setHealth() 在 actuallyHurt() 后将血量恢复至损伤前水平。
            // 若检测到血量未被实际扣除，强制直写 DataItem.value 字段
            // 绕过 SynchedEntityData.set() 及一切 setHealth() 覆写拦截。
            if (effectiveAmount > 0.0F && self.getHealth() >= healthBefore && self.isAlive()) {
                HealthUtil.setAllHealthLikeRaw(self, Math.max(0.0F, healthBefore - effectiveAmount));
            }

            // 无论伤害是否被阻止，只要攻击者持有见既斩就清除目标的自定义无敌计时器。
            // 部分 Boss（如 Goety Apostle）在 actuallyHurt() 中设置 moddedInvul 等字段，
            // 若不清除，下一次 hurt() 检测到 >0 会直接 return false 且不调用
            // super.hurt()，导致 LivingEntity.hurt() 不被调用、本 Mixin 不再触发、
            // 淬魂Plus 的 NBT 影子血量也无法更新。
            InvulClearUtil.clearCustomInvulTimers(self);
        }
    }

    /**
     * Layer 2.5 — 绕过 LivingHurtEvent 中的 Boss 限伤。
     * <p>
     * 正常流程中 {@code ForgeHooks.onLivingHurt()} 会 post {@link LivingHurtEvent}，
     * Boss 限伤模组在此事件中通过 {@code setAmount()} 压低伤害值。
     * 本 Redirect 在见既斩攻击下替换该调用：
     * <ul>
     *   <li>手动 post LivingHurtEvent（让淬魂/淬魂Plus 正常追加百分比伤害）</li>
     *   <li>返回值取 {@code Math.max(原始值, 事件值)} — 见既斩下伤害只能涨不能降</li>
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

        // 非见既斩攻击 → 走原版管线
        if (!isSeeAndSlashAttack(source, entity)) {
            return ForgeHooks.onLivingHurt(entity, source, amount);
        }

        // 手动 post LivingHurtEvent — 让淬魂等 MME 效果正常处理
        LivingHurtEvent event = new LivingHurtEvent(entity, source, amount);
        MinecraftForge.EVENT_BUS.post(event);

        // 伤害只能涨不能降：淬魂追加的伤害保留，Boss 限伤被忽略
        return Math.max(amount, event.getAmount());
    }

    /**
     * 检查攻击源是否来自见既斩持有者，且非自身/友好火力/重入调用。
     */
    @Unique
    private static boolean isSeeAndSlashAttack(DamageSource source, LivingEntity target) {
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
        if (!hasSeeAndSlash(living)) {
            return false;
        }
        // 友好火力保护：不穿透自己驯服生物
        if (FriendlyFireProtection.isOwnerTarget(living, target)) {
            return false;
        }
        return true;
    }

    /**
     * 检查 LivingEntity 主手或副手物品是否拥有见既斩附魔，
     * 且（对于玩家）是否佩戴了冒险的终点饰品。
     */
    @Unique
    private static boolean hasSeeAndSlash(LivingEntity entity) {
        return AdventurePower.hasPiercingGaze(entity)
            && (!(entity instanceof Player player)
                || AdventureProgressCapability.isAbilityAvailable(player, "piercing_gaze"));
    }

    /**
     * 检查伤害源是否为 MME 内部穿透伤害类型，用于反重入保护。
     * <p>
     * MME 的淬魂/复仇等附魔在处理器内部构造 DamageSource 并调用
     * {@code target.hurt()}，这会再次触发本 Mixin。外层见既斩已处理过
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
