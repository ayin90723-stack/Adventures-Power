package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.RejectHealthManipUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拒绝篡改 —— 拒绝外部直接对玩家血量的操纵。
 * <p>
 * <b>setHealth 保护</b>：通过 {@code ThreadLocal} 嵌套深度计数器区分
 * "合法 {@code hurt()} 路径"与"外部直接调用 {@code setHealth()}"。
 * 直调降血的（如亚波伦尾杀的 {@code target.setHealth(1.0f)}）会被拒绝。
 * <p>
 * <b>setMaxHealth 保护</b>：通过 {@code RejectHealthManipAttributeMixin}
 * 拦截异常低的 {@code setBaseValue()} 调用。
 * <p>
 * <b>设计要点</b>：使用 {@code ThreadLocal<Integer>} 替代
 * {@code Map<UUID, Integer>} 追踪嵌套深度——Minecraft 实体逻辑在
 * 服务端主线程执行，ThreadLocal 天然隔离调用链，无锁、无 UUID 查表、
 * 无需 tick 级扫描清理过期条目。
 *
 * <h3>已知限制与兜底</h3>
 * {@code HURT_DEPTH} 由 {@code hurt()} 的 HEAD/RETURN 注入维护。若 {@code hurt()}
 * 内部抛出未捕获异常、或外部模组以低优先级 Mixin 对 {@code hurt()} HEAD
 * cancellable cancel，RETURN 不执行，depth 残留 +1，导致同线程后续
 * {@code setHealth} 保护误判为"合法 hurt 路径"而放行外部篡改。兜底：
 * {@link HealthUtil#resetHurtDepthPerTick()} 在 ServerTickEnd 无条件归零
 * （hurt 不跨 tick，tick 末深度必为 0 是不变量），泄漏窗口仅为 cancel 发生的
 * 同 tick 内。
 *
 * @see RejectHealthManipAttributeMixin
 * @see RejectHealthManipUtil
 */
@Mixin(value = LivingEntity.class)
public abstract class RejectHealthManipMixin {

    // ===== hurt() 管道标记 =====

    @Inject(method = "m_6469_", at = @At("HEAD"))
    private void onHurtEnter(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player) {
            HealthUtil.HURT_DEPTH.set(HealthUtil.HURT_DEPTH.get() + 1);
        }
    }

    @Inject(method = "m_6469_", at = @At("RETURN"))
    private void onHurtExit(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player) {
            int depth = HealthUtil.HURT_DEPTH.get() - 1;
            if (depth <= 0) {
                HealthUtil.HURT_DEPTH.remove();
            } else {
                HealthUtil.HURT_DEPTH.set(depth);
            }
        }
    }

    // ===== setHealth() 拦截 =====

    @Inject(method = "m_21153_", at = @At("HEAD"), cancellable = true)
    private void rejectSetHealth(float newHealth, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        float currentHealth = HealthUtil.getHealthDirect(player);
        // NaN 任何比较都返回 false，+Infinity 会被误判为回血穿透
        // 这两种特殊浮点值写入 DataItem 后会导致血量永久异常
        if (Float.isNaN(newHealth) || Float.isInfinite(newHealth)) {
            ci.cancel();
            return;
        }
        // 回血放行（newHealth >= currentHealth）
        if (newHealth >= currentHealth) return;

        // 合法 hurt() 调用链内的 setHealth 放行
        if (HealthUtil.HURT_DEPTH.get() > 0) return;
        // 模组内部降血（vitality 关闭/启用时的血量裁剪等）放行——
        // reject_manip 防的是外部篡改，不拦模组自身的状态维护
        if (HealthUtil.INTERNAL_HEALTH_WRITE.get()) return;

        // 审查修 P3#4：原版 maxHealth 属性驱动的 clamp 降值放行——onAttributeModified →
        // onHealthChanged 写入 setHealth(clamp(current, 0, maxHealth))，生命上限下移
        // （诅咒装备/药水类机制）时写入值精确等于新 maxHealth。拦截它会让血量停在新上限
        // 之上直到下次受伤才自纠；写 maxHealth 值在当前血 ≤ 上限时属升血方向已被上方放行，
        // 能进到这里的 newHealth==maxHealth 必然是"血高于上限的归位"，与 clamp 语义一致
        if (newHealth == player.getMaxHealth()) return;

        // 外部直接 setHealth 降血 → 检查能力（ProgressCache 按 tick 缓存引用，避免每次 resolve）
        var progress = com.ayin90723.adventure_power.util.ProgressCache.get(player);
        if (progress != null && (progress.isAdventurer() || progress.isFullyUnlocked())
              && progress.isAbilityEnabled(AbilityIds.REJECT_MANIP)) {
            ci.cancel();
            // 觉醒：反弹 30% 被拒绝的伤害给攻击来源
            if (progress.isFullyUnlocked()) {
                float reflected = (currentHealth - newHealth) * ModConfig.AWAKEN_REJECT_MANIP_REFLECT_RATIO.get().floatValue();
                net.minecraft.world.entity.Entity lastAttacker = player.getLastHurtByMob();
                if (lastAttacker != null && reflected > 0.0F && lastAttacker.isAlive()) {
                    lastAttacker.hurt(player.damageSources().magic(), reflected);
                }
            }
        }
    }

    // ===== 移除时清理 ATTR_OWNER（防泄漏，不 cancel remove）=====
    // 历史残留：原计划拦截对玩家的 remove 做保护，后意识到模组一般不对玩家 remove，
    // 保留此注入仅做 ATTR_OWNER map 清理。玩家换维度(CHANGED_DIMENSION)/登出/死亡 respawn
    // 都会触发原版 remove，此处清理 map 中指向该玩家的 AttributeInstance 条目，
    // 避免 static map 持有已移除玩家引用导致 GC 泄漏。不 cancel remove。

    @Inject(method = "m_142687_", at = @At("HEAD"))
    private void cleanupOnRemoval(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player) {
            RejectHealthManipUtil.ATTR_OWNER.values().removeIf(v -> v == self);
        }
    }

    // ===== getAttribute() 追踪 AttributeInstance 所有者 =====

    @Inject(method = "m_21051_", at = @At("RETURN"))
    private void trackAttrOwner(Attribute attribute, CallbackInfoReturnable<AttributeInstance> cir) {
        if (attribute != Attributes.MAX_HEALTH) return;
        AttributeInstance instance = cir.getReturnValue();
        if (instance == null) return;
        LivingEntity self = (LivingEntity) (Object) this;
        // 门禁前置：reject_manip 只对冒险者生效，非冒险者玩家与所有非玩家实体直接跳过，
        // 避免对绝大多数 getAttribute(MAX_HEALTH) 调用做无意义的 map 操作
        if (!(self instanceof Player player)) return;
        var progress = com.ayin90723.adventure_power.util.ProgressCache.get(player);
        if (progress == null) return;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
        // putIfAbsent：同一 instance 首次追踪后跳过，避免重复 put 开销
        RejectHealthManipUtil.ATTR_OWNER.putIfAbsent(instance, player);
    }
}
