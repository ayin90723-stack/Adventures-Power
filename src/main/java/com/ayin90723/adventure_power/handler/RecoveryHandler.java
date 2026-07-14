package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.FriendlyFireProtection;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 恢复类能力效果处理器。
 * <p>
 * 处理 2 种恢复能力的实际效果：
 * <ul>
 *   <li>休养生息 (rapid_recovery) — 脱战后直写回血 + 恢复饱食度</li>
 *   <li>嗜血 (lifesteal) — 攻击造成伤害时回复自身生命值</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RecoveryHandler {

    /** 上次再生检查时间缓存 */
    private static final Map<UUID, Long> lastRecoveryCheck = new ConcurrentHashMap<>();

    /** 玩家最后受伤时间 */
    private static final Map<UUID, Long> lastHurtTimestamps = new ConcurrentHashMap<>();

    // ==================== 休养生息 — 脱战再生 ====================

    /**
     * 记录所有冒险者玩家的受伤时间，供休养生息判断脱战间隔。
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
            lastHurtTimestamps.put(player.getUUID(), player.level().getGameTime());
        });
    }

    /**
     * 每 tick 检查：
     * 休养生息 — 脱战超过延迟阈值后，直写 SynchedEntityData 回血并恢复饱食度。
     * 不使用药水效果（addEffect），避免被 MobEffectEvent / removeAllEffects 拦截。
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

        // ---- 休养生息 ----
        if (progress.isAbilityEnabled("rapid_recovery")) {
            long currentTime = player.level().getGameTime();

            // 初始化上次受伤时间（防止启用/登录时误判为"已脱战"而立即回血）
            lastHurtTimestamps.putIfAbsent(player.getUUID(), currentTime);

            long lastCheck = lastRecoveryCheck.getOrDefault(player.getUUID(), -1L);
            if (lastCheck == -1L) {
                lastRecoveryCheck.put(player.getUUID(), currentTime);
            } else if (currentTime - lastCheck >= ModConfig.RAPID_RECOVERY_CHECK_INTERVAL.get()) {
                lastRecoveryCheck.put(player.getUUID(), currentTime);

                int delayTicks = ModConfig.RAPID_RECOVERY_DELAY_TICKS.get();
                long lastHurt = lastHurtTimestamps.getOrDefault(player.getUUID(), 0L);
                long timeSinceHurt = currentTime - lastHurt;

                // 脱战超过延迟阈值 → 直写血量 + 恢复饱食度（避免药水效果被拦截）
                if (timeSinceHurt >= delayTicks) {
                    Ability ability = AbilityRegistry.get("rapid_recovery");
                    if (ability != null) {
                        int amplifier = (int) ability.value(progress.getUnlockedMilestoneCount());
                        // 觉醒：额外直写回血量（HP/周期）
                        if (progress.isFullyUnlocked()) {
                            amplifier += ModConfig.AWAKEN_RAPID_RECOVERY_BONUS.get();
                        }

                        // ① 直写回血 — 绕过一切药水效果拦截（addEffect/MobEffectEvent 均不可靠）
                        float maxHealth = player.getMaxHealth();
                        float currentHealth = HealthUtil.getHealthDirect(player);
                        if (currentHealth < maxHealth) {
                            float healAmount = (amplifier + 1) * 1.0F; // HP per 3s cycle
                            float newHealth = Math.min(maxHealth, currentHealth + healAmount);
                            HealthUtil.setAllHealthLikeRaw(player, newHealth);
                        }

                        // ② 恢复饱食度 — HealthUtil 直写 FoodData 字段，绕过方法拦截
                        HealthUtil.restoreFoodData(player);
                    }
                }
            }
        }
    }

    /**
     * 玩家登出时清理缓存，防止内存泄漏。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        lastRecoveryCheck.remove(id);
        lastHurtTimestamps.remove(id);
    }

    // ==================== 嗜血 — 攻击吸血 ====================

    /**
     * 嗜血：攻击造成伤害时按比例回复自身生命值。
     * <p>
     * 跳过内部穿透伤害（soul_strike / judgment），防止递归吸血。
     * 吸血量上限为最大生命值的 {@link ModConfig#LIFESTEAL_CAP_RATIO} 倍。
     */
    @SubscribeEvent
    public static void onLivingHurtLifesteal(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;
        if (!(event.getSource().getEntity() instanceof Player attacker)) return;
        if (target instanceof Player) return; // PVP 无效

        // 跳过内部穿透伤害，防递归
        String msgId = event.getSource().getMsgId();
        if ("soul_strike".equals(msgId) || "judgment".equals(msgId)) return;

        if (FriendlyFireProtection.isOwnerTarget(attacker, target)) return;

        AdventureProgressCapability.getAdventureProgress(attacker).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("lifesteal")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            Ability ability = AbilityRegistry.get("lifesteal");
            if (ability == null) return;

            float percentage = ability.value(progress.getUnlockedMilestoneCount()) / 100.0f;
            float healAmount = event.getAmount() * percentage;
            float cap = attacker.getMaxHealth() * ModConfig.LIFESTEAL_CAP_RATIO.get().floatValue();
            healAmount = Math.min(healAmount, cap);

            if (healAmount > 0.0F) {
                // 直写血量绕过 heal() — 避免被外部模组（如泽林变体）在 heal() HEAD cancel 拦截
                float healthBeforeHeal = HealthUtil.getHealthDirect(attacker);
                float newHealth = Math.min(attacker.getMaxHealth(), healthBeforeHeal + healAmount);
                HealthUtil.setAllHealthLikeRaw(attacker, newHealth);
                // 觉醒：过量治疗转为吸收护盾
                if (progress.isFullyUnlocked()) {
                    float toFull = attacker.getMaxHealth() - healthBeforeHeal;
                    if (healAmount > toFull && toFull > 0) {
                        float excess = healAmount - toFull;
                        float shieldCap = attacker.getMaxHealth()
                            * com.ayin90723.adventure_power.config.ModConfig.AWAKEN_LIFESTEAL_SHIELD_CAP.get().floatValue();
                        excess = Math.min(excess, shieldCap);
                        if (excess > 0.0F) {
                            attacker.setAbsorptionAmount(Math.min(
                                attacker.getAbsorptionAmount() + excess, shieldCap));
                        }
                    }
                }
            }
        });
    }
}
