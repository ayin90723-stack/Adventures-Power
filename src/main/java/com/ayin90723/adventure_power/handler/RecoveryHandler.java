package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.DamageUtil;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.FriendlyFireProtection;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    /** 上次再生检查时间缓存（服务端主线程单线程，无需并发） */
    private static final Map<UUID, Long> lastRecoveryCheck = new HashMap<>();

    /** 玩家最后受伤时间 */
    private static final Map<UUID, Long> lastHurtTimestamps = new HashMap<>();

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
            if (!AbilityGate.isActive(progress, AbilityIds.RAPID_RECOVERY)) return;
            lastHurtTimestamps.put(player.getUUID(), player.level().getGameTime());
        });
    }

    /**
     * 每 tick 检查：
     * 休养生息 — 脱战超过延迟阈值后，直写 SynchedEntityData 回血并恢复饱食度。
     * 不使用药水效果（addEffect），避免被 MobEffectEvent / removeAllEffects 拦截。
     */
    /** 门禁后业务（由 PlayerTickDispatcher 调用）：休养生息脱战再生 */
    public static void onTick(Player player, IAdventureProgress progress) {

        // ---- 休养生息 ----
        if (progress.isAbilityEnabled(AbilityIds.RAPID_RECOVERY)) {
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
                    Ability ability = AbilityRegistry.get(AbilityIds.RAPID_RECOVERY);
                    if (ability != null) {
                        int amplifier = (int) ability.value(AbilityGate.effectiveCount(progress, AbilityIds.RAPID_RECOVERY));
                        // 觉醒：额外直写回血量（HP/周期）
                        if (progress.isFullyUnlocked()) {
                            amplifier += ModConfig.AWAKEN_RAPID_RECOVERY_BONUS.get();
                        }

                        // ① 直写回血 — 绕过一切药水效果拦截（addEffect/MobEffectEvent 均不可靠）
                        float maxHealth = player.getMaxHealth();
                        float currentHealth = HealthUtil.getHealthDirect(player);
                        if (currentHealth < maxHealth) {
                            // 每级 amplifier 折算回血量由配置控制（默认 1.0 HP/周期）
                            float healAmount = (amplifier + 1) * ModConfig.RAPID_RECOVERY_HEAL_PER_AMPLIFIER.get().floatValue();
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

    // ==================== 嗜血 — 击杀回馈 ====================

    /**
     * 嗜血：击杀生物时额外回馈固定血量（"吸干"收割感）。
     * <p>
     * 与吸血不同，击杀回馈不区分伤害来源——影杀斩杀/淬魂补刀/审判击杀都算
     * （"击杀就是胜利"）。PVP 击杀排除（与嗜血 PVP 无效一致）、友伤保护
     * （杀自家驯服生物不触发）。回血量 lifesteal_kill_heal，觉醒叠加
     * AWAKEN_LIFESTEAL_KILL_HEAL。直写血量绕过 heal() 拦截。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;
        if (target instanceof Player) return; // PVP 击杀无效

        // 弹射物击杀（弓/弩/三叉戟）的 getEntity() 是弹射物本身，走 resolveAttacker 回溯
        Entity rawAttacker = PiercingGazeUtil.resolveAttacker(event.getSource());
        if (!(rawAttacker instanceof Player attacker)) return;
        if (attacker == target) return; // 自杀不算

        if (FriendlyFireProtection.isOwnerTarget(attacker, target)) return;

        AbilityGate.getActiveProgress(attacker, AbilityIds.LIFESTEAL).ifPresent(progress -> {
            float healAmount = ModConfig.LIFESTEAL_KILL_HEAL.get().floatValue();
            if (progress.isFullyUnlocked()) {
                healAmount += ModConfig.AWAKEN_LIFESTEAL_KILL_HEAL.get().floatValue();
            }
            if (healAmount <= 0.0F) return;

            float current = HealthUtil.getHealthDirect(attacker);
            float newHealth = Math.min(attacker.getMaxHealth(), current + healAmount);
            if (newHealth > current) {
                DebugLog.lifesteal("[嗜血] {} 击杀回馈 {} → {}", attacker, healAmount, newHealth);
                HealthUtil.setAllHealthLikeRaw(attacker, newHealth);
            }
        });
    }

    // ==================== 嗜血 — 攻击吸血 ====================

    /**
     * 嗜血：攻击造成伤害时按比例回复自身生命值。
     * <p>
     * 跳过内部穿透伤害（soul_strike / judgment / shadow_kill），防止递归吸血。
     * 吸血量上限为最大生命值的 {@link ModConfig#LIFESTEAL_CAP_RATIO} 倍。
     * <p>
     * LOW 优先级：在伤害抗性等 NORMAL 监听器之后执行，
     * 基于减伤后的最终 amount 吸血，避免同优先级执行顺序不确定。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurtLifesteal(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;
        // 弹射物击杀（弓/弩/三叉戟）的 getEntity() 是弹射物本身，走 resolveAttacker 回溯
        //（v1.3.7 与击杀回馈 onLivingDeath 统一，弓/弩/三叉戟伤害也能吸血）
        if (!(PiercingGazeUtil.resolveAttacker(event.getSource()) instanceof Player attacker)) return;
        if (target instanceof Player) return; // PVP 无效

        // 跳过内部穿透伤害，防递归
        if (DamageUtil.isInternalSource(event.getSource())) return;

        if (FriendlyFireProtection.isOwnerTarget(attacker, target)) return;

        // 同 tick 去重（与淬魂/影杀/禁疗共享 COMBAT_TICK_DEDUP）：破敌之眼穿透三连的
        // 双重 post 会让嗜血同 tick 双吸血——按 (attacker, target) 同 tick 只吸一次
        if (!CombatAbilityHandler.tryMarkCombatTick(attacker, target)) return;

        AbilityGate.getActiveProgress(attacker, AbilityIds.LIFESTEAL).ifPresent(progress -> {
            Ability ability = AbilityRegistry.get(AbilityIds.LIFESTEAL);
            if (ability == null) return;

            float percentage = ability.value(AbilityGate.effectiveCount(progress, AbilityIds.LIFESTEAL)) / 100.0f;
            float healAmount = event.getAmount() * percentage;
            float cap = attacker.getMaxHealth() * ModConfig.LIFESTEAL_CAP_RATIO.get().floatValue();
            healAmount = Math.min(healAmount, cap);

            if (healAmount > 0.0F) {
                // 直写血量绕过 heal() — 避免被外部模组（如泽林变体）在 heal() HEAD cancel 拦截
                float healthBeforeHeal = HealthUtil.getHealthDirect(attacker);
                float newHealth = Math.min(attacker.getMaxHealth(), healthBeforeHeal + healAmount);
                DebugLog.lifesteal("[嗜血] {} 吸血 {}（伤害 {} × {}%）→ {}", attacker, healAmount, event.getAmount(), percentage * 100, newHealth);
                HealthUtil.setAllHealthLikeRaw(attacker, newHealth);
                // 觉醒：过量治疗转为吸收护盾（满血时全部吸血量转护盾）
                if (progress.isFullyUnlocked()) {
                    float toFull = attacker.getMaxHealth() - healthBeforeHeal;
                    float excess = toFull > 0 ? Math.max(0, healAmount - toFull) : healAmount;
                    if (excess > 0) {
                        float shieldCap = attacker.getMaxHealth()
                            * ModConfig.AWAKEN_LIFESTEAL_SHIELD_CAP.get().floatValue();
                        excess = Math.min(excess, shieldCap);
                        if (excess > 0.0F) {
                            // 上限只限模组新增部分：min(新总量, max(既有吸收, shieldCap))——
                            // 玩家已有更高吸收（金苹果/其他模组护盾）时不被本能力压掉
                            attacker.setAbsorptionAmount(Math.min(
                                attacker.getAbsorptionAmount() + excess,
                                Math.max(attacker.getAbsorptionAmount(), shieldCap)));
                        }
                    }
                }
            }
        });
    }
}
