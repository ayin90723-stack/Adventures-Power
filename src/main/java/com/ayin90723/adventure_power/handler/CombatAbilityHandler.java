package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.ability.SoulQuenchAbility;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.effect.HealingBlockEffect;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.DamageUtil;
import com.ayin90723.adventure_power.util.FriendlyFireProtection;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.Set;

/**
 * 战斗类能力效果处理器。
 * <p>
 * 处理 5 种战斗能力的实际效果：
 * <ul>
 *   <li>灵巧 (Agility) — LivingAttackEvent 中概率闪避</li>
 *   <li>伤害抗性 (DamageResist) — LivingHurtEvent 中全伤害减免</li>
 *   <li>淬魂之力 (SoulQuench) — 真实伤害（绕过护甲/抗性）</li>
 *   <li>影杀 (ShadowKill) — 攻击者侧影子血量 + 饱和式秒杀（委托 {@link ShadowKillHelper}）</li>
 *   <li>禁疗之触 (HealingBlock) — 对目标施加禁疗标记</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CombatAbilityHandler {


    /**
     * 破敌之眼觉醒禁无敌帧 - 目标侧标记到期时间（gameTime）。
     * 内存表（弱 key）替代 persistentData：标记仅影响当前实体实例，无需持久化，
     * 且避免每 tick 对全服实体做 persistentData 探测。实体 GC 后条目自动释放。
     */
    private static final Map<Entity, Long> PIERCING_GAZE_NO_IFRAME_END =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 攻击方能力同 tick 结算去重表：(attacker:target) 同 tick 只结算一次。
     *  防破敌之眼穿透三连的双重 post（手动 postHurtEvent + actuallyHurt 内
     *  ForgeHooks.onLivingHurt 二次 post）导致淬魂/影杀/禁疗/嗜血同 tick 双结算；
     *  ServerTickEvent END 每 tick 清空（与影杀 SHADOW_KILL_TICKED 同生命周期）。 */
    private static final Set<String> COMBAT_TICK_DEDUP = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 攻击方能力同 tick 去重入口（RecoveryHandler 嗜血等跨类调用）：
     *  add 成功 = 本 tick 首次结算；失败 = 已结算过，调用方应跳过 */
    public static boolean tryMarkCombatTick(Player attacker, LivingEntity target) {
        return COMBAT_TICK_DEDUP.add(attacker.getUUID() + ":" + target.getUUID());
    }

    /** 每 tick 清空去重表（tick 末，跨 tick 的攻击不受影响） */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            COMBAT_TICK_DEDUP.clear();
        }
    }

    // ==================== 1. 灵巧 — 概率闪避 ====================

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.isCanceled()) return;
        LivingEntity receiver = event.getEntity();
        if (!(receiver instanceof Player player)) return;
        if (receiver.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.AGILITY).ifPresent(progress -> {
            Ability ability = AbilityRegistry.get(AbilityIds.AGILITY);
            if (ability == null) return;

            float chance = AbilityGate.awakenedRatio(ability,
                AbilityGate.effectiveCount(progress, AbilityIds.AGILITY), progress.isFullyUnlocked());
            if (player.getRandom().nextFloat() < chance) {
                event.setCanceled(true);
                if (player.level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.WITCH,
                        player.getX(), player.getY() + 1.0, player.getZ(),
                        20, 0.4, 0.5, 0.4, 0.15);
                }
            }
        });
    }

    // ==================== 2~5: LivingHurtEvent 入口 ====================

    @SubscribeEvent(receiveCanceled = true)
    public static void onLivingHurt(LivingHurtEvent event) {
        // 事件已发标记（v1.3.3）：任何 post 的 LivingHurtEvent 必然触发本监听器——
        // 标记 = 事件已发的直接证据，供 Layer 0（Player.attack）决定是否补发。
        // 不依赖 Layer 2.5 redirect：redirect 注入失败（require=0 静默失效）时
        // 原版事件照常 post，本监听器照常 mark，Layer 0 据此不重复补发（防淬魂/嗜血双结算）。
        // v1.3.6：按目标实体记录（per-entity），嵌套 hurt（Boss AOE）不污染本实体判定
        PiercingGazeUtil.markVanillaHurtEventPosted(event.getEntity());

        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;

        DamageSource source = event.getSource();

        // 内部伤害源（淬魂/审判）不触发模组能力，防止递归时觉醒标记/易伤/减伤/影杀被重复应用
        if (DamageUtil.isInternalSource(source)) return;

        // 兼容拔刀剑/投射物等 causingEntity 缺失的伤害：通过 resolveAttacker 追溯真正的攻击者
        Entity rawAttacker = PiercingGazeUtil.resolveAttacker(source);

        // 受伤方能力仅处理未取消的事件
        if (!event.isCanceled()) {
            handleHealingBlockVuln(event, target);
            handleDamageResist(event, target);
        }

        // 攻击方能力（取一次 progress 复用，避免每个能力各查一次 Capability）
        if (rawAttacker instanceof Player attacker) {
            if (FriendlyFireProtection.isOwnerTarget(attacker, target)) return;
            // 攻击方伤害能力（淬魂/影杀）不豁免事件取消：v1.3.3 前为"保证生效"不加检查，
            // 该职责已由破敌之眼穿透（手动 post 的事件本身未取消）覆盖——
            // 被其他模组 cancel 的伤害（否定类机制）不再强行结算
            if (event.isCanceled()) return;
            // 同 tick 去重（淬魂/影杀/禁疗/嗜血共享）：穿透三连的手动 post + actuallyHurt 内
            // ForgeHooks 二次 post 会让攻击方能力同 tick 双结算——按 (attacker, target)
            // 同 tick 只结算一次（影杀内部另有 SHADOW_KILL_TICKED 双保险）
            if (!tryMarkCombatTick(attacker, target)) return;
            var progress = AdventureProgressCapability.getAdventureProgress(attacker).orElse(null);
            if (progress == null) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            if (progress.isAbilityEnabled(AbilityIds.HEALING_BLOCK)) handleHealingBlock(event, target, attacker, progress);
            if (progress.isFullyUnlocked() && progress.isAbilityEnabled(AbilityIds.PIERCING_GAZE)) handlePiercingGazeAwakened(event, target, attacker, progress);
            if (progress.isAbilityEnabled(AbilityIds.SOUL_QUENCH)) handleSoulQuench(event, target, attacker, progress);
            if (progress.isAbilityEnabled(AbilityIds.SHADOW_KILL)) ShadowKillHelper.handleShadowKill(event, target, attacker, progress);
        }
    }

    /** 破敌之眼觉醒：破无敌一击后，目标 N tick 内无法获得无敌帧（piercing_gaze+fullyUnlocked 已在 onLivingHurt 门禁） */
    private static void handlePiercingGazeAwakened(LivingHurtEvent event, LivingEntity target, Player attacker, IAdventureProgress progress) {
        // PVP 禁用：禁无敌帧标记对玩家不生效（与穿透链一致，破敌之眼对玩家目标整体禁用）
        if (target instanceof Player) return;
        if (target.invulnerableTime <= 0) return;
        long endTime = target.level().getGameTime() + ModConfig.AWAKEN_PIERCING_GAZE_NO_IFRAME_TICKS.get();
        PIERCING_GAZE_NO_IFRAME_END.put(target, endTime);
    }

    // ==================== 2. 伤害抗性 — 全伤害减免 ====================

    private static void handleDamageResist(LivingHurtEvent event, LivingEntity target) {
        if (!(target instanceof Player player)) return;
        AbilityGate.getActiveProgress(player, AbilityIds.DAMAGE_RESIST).ifPresent(progress -> {
            Ability ability = AbilityRegistry.get(AbilityIds.DAMAGE_RESIST);
            if (ability == null) return;

            float ratio = AbilityGate.awakenedRatio(ability,
                AbilityGate.effectiveCount(progress, AbilityIds.DAMAGE_RESIST), progress.isFullyUnlocked());
            event.setAmount(event.getAmount() * (1.0f - ratio));
        });
    }

    // ==================== 3. 淬魂之力 — 真实伤害 ====================

    /**
     * 淬魂之力：真实百分比伤害，绕过护甲/抗性/无敌帧。
     * <p>
     * 公式：额外伤害 = flatDamage + maxHP×hpRatio + currentHP×hpRatio
     * 通过自定义 DamageSource（bypasses_armor + bypasses_invulnerability +
     * bypasses_enchantments + bypasses_cooldown）施加，若 hurt() 被外部
     * mod 拦截则通过 HealthUtil 直写血量兜底。
     */
    private static void handleSoulQuench(LivingHurtEvent event, LivingEntity target, Player attacker, IAdventureProgress progress) {
        int milestones = AbilityGate.effectiveCount(progress, AbilityIds.SOUL_QUENCH);
        Ability raw = AbilityRegistry.get(AbilityIds.SOUL_QUENCH);
        if (!(raw instanceof SoulQuenchAbility ability)) return;

        float flatDamage = ability.flatDamage(milestones);
        float hpRatio = ability.hpRatio(milestones);

        float extraDamage = flatDamage
            + target.getMaxHealth() * hpRatio
            // 架空参照读数：自定义血条 Boss（亚波伦）原版槽被架空，getHealthDirect 读到不动值，
            // 百分比基准必须取真实血量，否则百分比伤害失真
            + HealthUtil.getEffectiveHealth(target) * hpRatio;

        if (HealingBlockEffect.isActive(target)) {
            extraDamage *= ModConfig.SOUL_QUENCH_HEALING_BLOCK_MULTIPLIER.get().floatValue();
        }

        // 觉醒：斩杀线 — 目标低于阈值 HP 时伤害按配置倍率放大（默认 2.0=翻倍）
        if (progress.isFullyUnlocked()) {
            float threshold = ModConfig.AWAKEN_SOUL_QUENCH_EXECUTE_THRESHOLD.get().floatValue();
            if (HealthUtil.getEffectiveHealth(target) <= target.getMaxHealth() * threshold) {
                extraDamage *= ModConfig.AWAKEN_SOUL_QUENCH_EXECUTE_MULTIPLIER.get().floatValue();
            }
        }

        if (extraDamage <= 0.0F) return;

        // 构建穿透伤害类型：绕过护甲/无敌/附魔保护/攻击冷却
        var source = DamageUtil.createSoulStrike(target.level(), attacker);

        float healthBefore = HealthUtil.getEffectiveHealth(target);
        target.hurt(source, extraDamage);
        float actualDealt = healthBefore - HealthUtil.getEffectiveHealth(target);

        // 清零无敌帧 + 受击闪烁：hurt() 后原版会将 invulnerableTime 设为 10
        target.invulnerableTime = 0;
        clearHurtTime(target);

        // 兜底：hurt() 被外部 mod（Boss 限伤/硬上限等）拦截 → 直写血量
        float epsilon = Math.max(0.01F, extraDamage * 0.01F);
        if (target.isAlive() && actualDealt < extraDamage - epsilon) {
            float correctedHealth = Math.max(healthBefore - extraDamage, 0.0F);
            // 分级直写：通用层（方法扫描+验证）→ 对象图插针 → DataItem 兜底
            HealthUtil.setHealthLikeAny(target, correctedHealth);
            if (correctedHealth <= 0.0F) {
                clearHurtTime(target);
                target.invulnerableTime = 0;
                target.setLastHurtByMob(attacker);
                target.setLastHurtByPlayer(attacker);
                setDeathScoreNegativeOne(target);  // 防止 die() 内部重复计数
                target.die(source);
                // 计分移到 die() 之后：死亡被取消（死亡抗拒/真实血量等救回）时不发击杀分，
                // 避免"击杀分已发放但目标未死"的错位
                if (!target.isAlive()) {
                    attacker.awardKillScore(target, 1, target.level().damageSources().mobAttack(attacker));
                }
            }
        }
    }

    /** 清零实体 hurtTime（反射方式），防止个别 Boss 将 hurtTime>0 作为额外无敌判据 */
    public static void clearHurtTime(LivingEntity target) {
        if (target instanceof Player) return; // PVP 保留原版 10tick 无敌窗口
        if (HURT_TIME_FIELD == null) return;
        try {
            HURT_TIME_FIELD.setInt(target, 0);
        } catch (IllegalAccessException ignored) {}
    }

    static final java.lang.reflect.Field HURT_TIME_FIELD;
    static {
        // f_20916_ = hurtTime（f_19802_ 是 invulnerableTime，曾误写该字段导致清除失效）
        HURT_TIME_FIELD = HealthUtil.reflectField(LivingEntity.class, "f_20916_", "hurtTime");
    }

    /**
     * 将实体的 deathScore 设为 -1，阻止 die() 内部重复调用 awardKillScore。
     * <p>
     * 反射兜底：仅在外部 Boss 绕过 hurt()/setHealth() 直接调 die() 时触发。
     * 若长时间不触发可考虑移除；保留以防 awardKillScore 副作用（记分板/成就统计）。
     * public 供 ActiveSkillHandler 审判兜底路径复用。
     */
    /** deathScore 字段缓存（f_20897_ = deathScore；f_20920_ 是 oAttackAnim(float)，对其 setInt 会抛异常导致静默失效） */
    private static final java.lang.reflect.Field DEATH_SCORE_FIELD =
        HealthUtil.reflectField(LivingEntity.class, "f_20897_", "deathScore");

    public static void setDeathScoreNegativeOne(LivingEntity target) {
        try {
            if (DEATH_SCORE_FIELD != null) {
                DEATH_SCORE_FIELD.setInt(target, -1);
            }
        } catch (Exception ignored) {}
    }

    // ==================== 5. 禁疗之触 — 攻击施加禁疗 ====================

    private static void handleHealingBlock(LivingHurtEvent event, LivingEntity target, Player attacker, IAdventureProgress progress) {
        // PVP 禁用：禁疗标记（含 FORCE_KILL 强制击杀链）对玩家不生效——
        // 玩家目标拥有本模组自己的死亡抗拒/真实血量防御，禁疗击穿免死特性属行为自冲突
        if (target instanceof Player) return;
        int milestones = AbilityGate.effectiveCount(progress, AbilityIds.HEALING_BLOCK);
        Ability ability = AbilityRegistry.get(AbilityIds.HEALING_BLOCK);
        if (ability == null) return;

        int durationSeconds = (int) ability.value(milestones);
        int durationTicks = durationSeconds * 20;
        HealingBlockEffect.apply(target, durationTicks);

        if (progress.isFullyUnlocked()) {
            long endTime = target.level().getGameTime() + durationTicks;
            // 觉醒易伤与禁疗同期：内存 + NBT 双源（防 getPersistentData 被重写的 Boss 标记丢失）
            HealingBlockEffect.applyVuln(target, endTime);
        }
    }

    /** 禁疗之触觉醒易伤：被禁疗标记的目标受伤 +X%（与禁疗同期到期） */
    private static void handleHealingBlockVuln(LivingHurtEvent event, LivingEntity target) {
        Long endTime = HealingBlockEffect.getVulnEnd(target);
        if (endTime == null) return;
        if (target.level().getGameTime() > endTime) {
            HealingBlockEffect.removeVuln(target);
            return;
        }
        float mult = ModConfig.AWAKEN_HEALING_BLOCK_VULN.get().floatValue();
        event.setAmount(event.getAmount() * mult);
    }

    /** 破敌之眼觉醒：标记期间目标无法获得无敌帧（内存表快路径，实体未标记时零开销） */
    @SubscribeEvent
    public static void onLivingTick(LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        // 99% 实体无无敌帧且无标记，先短路再查表
        if (entity.invulnerableTime <= 0) return;
        Long endTime = PIERCING_GAZE_NO_IFRAME_END.get(entity);
        if (endTime == null) return;
        if (entity.level().getGameTime() > endTime) {
            PIERCING_GAZE_NO_IFRAME_END.remove(entity);
            return;
        }
        if (entity.invulnerableTime > 0) {
            entity.invulnerableTime = 0;
        }
    }
}
