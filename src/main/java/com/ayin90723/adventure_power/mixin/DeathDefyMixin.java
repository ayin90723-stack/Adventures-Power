package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 死亡抗拒 —— 双层拦截（setHealth HEAD + tick TAIL 钳制）。
 * <p>
 * <b>层1 — setHealth HEAD</b>：无敌期间拒绝一切通过 {@code setHealth()} 的降血，
 * 覆盖 {@code hurt()}、{@code actuallyHurt()}、外部直调三条路径。
 * <p>
 * <b>层2 — tick TAIL 钳制</b>：在实体 tick 末尾检测并恢复被 {@code catchSetTrueHealth}
 * （VarHandle 直写 {@code DataItem.value} 字段）绕过的血量。这是唯一能反制
 * RevelationFix 底层字段直写的机制——不经过任何方法调用，只能在 tick 末通过
 * {@link HealthUtil#setAllHealthLikeRaw} 把值写回去。
 *
 * @see RejectHealthManipMixin
 * @see AdventureProgressCapability#onPlayerDeath
 */
@Mixin(value = LivingEntity.class, priority = 1500)
public abstract class DeathDefyMixin {

    /** 死亡抗拒无敌期间保留的目标血量（固定 20，避开 maxHealth 被污染的问题） */
    private static final float DEATH_DEFY_CLAMP_HEALTH = 20.0F;

    // ===== 层1：setHealth() HEAD 拦截 =====

    @Inject(method = "m_21153_", at = @At("HEAD"), cancellable = true)
    private void onSetHealthDeathDefy(float newHealth, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        float currentHealth = HealthUtil.getHealthDirect(player);
        // 拒绝特殊浮点值：NaN 任何比较都返回 false，+Infinity 被误判为回血
        if (Float.isNaN(newHealth) || Float.isInfinite(newHealth)) {
            ci.cancel();
            return;
        }
        if (newHealth >= currentHealth) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if ((progress.isAdventurer() || progress.isFullyUnlocked())
                  && progress.isAbilityEnabled("death_defy")
                  && progress.isDeathDefyInvulnerable(player.level().getGameTime())) {
                ci.cancel();
            }
        });
    }

    // ===== 层2：tick TAIL 兜底钳制 =====

    /**
     * tick 末尾兜底：反制 {@code catchSetTrueHealth} 通过 VarHandle 直写
     * {@code DataItem.value} 的降血——这是唯一不经过任何方法调用的伤害路径。
     * <p>
     * 比对目标血量和实际血量，若实际血量低于目标则将所有血量相关数据条目
     * 强制写回目标值。固定目标血量 20（而非 {@code getMaxHealth()}）避免
     * 亚波伦结界临时改 maxHealth 导致的钳制值错误。
     */
    @Inject(method = "m_8107_", at = @At("TAIL"))
    private void onTickTailClamp(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled("death_defy")) return;
            if (!progress.isDeathDefyInvulnerable(player.level().getGameTime())) return;

            float current = HealthUtil.getHealthDirect(player);
            if (current < DEATH_DEFY_CLAMP_HEALTH) {
                // catchSetTrueHealth 直写了 DataItem.value → getHealth() 已反映新值
                // setAllHealthLikeRaw 遍历所有血量条目并直接用反射写回，
                // 覆盖 VarHandle 直写的结果
                HealthUtil.setHealthDirect(player, DEATH_DEFY_CLAMP_HEALTH);
                player.setHealth(DEATH_DEFY_CLAMP_HEALTH);
            }
        });
    }
}
