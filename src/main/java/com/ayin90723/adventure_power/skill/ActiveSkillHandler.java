package com.ayin90723.adventure_power.skill;

import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.handler.CombatAbilityHandler;
import com.ayin90723.adventure_power.util.SyncUtil;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.DamageUtil;
import com.ayin90723.adventure_power.util.probe.BloodWriteEngine;
import com.ayin90723.adventure_power.util.probe.ProbeScales;
import com.ayin90723.adventure_power.util.FriendlyFireProtection;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class ActiveSkillHandler {

    /**
     * 服务端入口：处理技能释放请求。
     * @param player     释放者
     * @param skillIndex 0=旅者审判, 1=旅者庇护
     */
    public static void handleSkillRelease(ServerPlayer player, int skillIndex) {
        if (player.level().isClientSide()) return;
        if (skillIndex < 0 || skillIndex > 1) return; // 非法技能索引

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        // 门禁检查（里程碑归属由 isAbilityEnabled 内置硬门禁判定）
        if (!AbilityGate.isActive(progress, AbilityIds.ACTIVE_SKILL)) return;

        long currentTime = player.level().getGameTime();

        // GCD 检查
        long gcdEnd = progress.getActiveSkillGcdEnd();
        if (gcdEnd > 0 && currentTime < gcdEnd) return;

        if (skillIndex == 0) {
            handleJudgment(player, progress, currentTime);
        } else if (skillIndex == 1) {
            handleSanctuary(player, progress, currentTime);
        }
    }

    // ===== 旅者审判 =====

    private static void handleJudgment(ServerPlayer player, IAdventureProgress progress, long currentTime) {
        // 冷却检查
        long cdEnd = progress.getJudgmentCooldownEnd();
        if (cdEnd > 0 && currentTime < cdEnd) return;

        // 空放不惩罚：范围内无敌对目标则不进 CD/GCD（收集结果直接传给执行，
        // 避免同一释放做两次 AABB 查询——v1.4.0 审查优化）
        List<LivingEntity> targets = collectJudgmentTargets(player, progress);
        if (targets.isEmpty()) return;

        // 消耗冷却
        int cooldown = ModConfig.ACTIVE_SKILL_JUDGMENT_COOLDOWN.get();
        int gcd = ModConfig.ACTIVE_SKILL_GCD.get();
        progress.setJudgmentCooldownEnd(currentTime + cooldown);
        progress.setActiveSkillGcdEnd(currentTime + gcd);
        SyncUtil.syncCapabilityToPersistent(player, progress);
        SyncUtil.syncToClient(player);

        executeJudgment(player, targets);
    }

    /** 收集审判范围内敌对目标（空放预检与执行共用——范围随觉醒 +50%） */
    private static List<LivingEntity> collectJudgmentTargets(ServerPlayer player, IAdventureProgress progress) {
        double radius = ModConfig.ACTIVE_SKILL_JUDGMENT_RADIUS.get();
        if (progress.isFullyUnlocked()) {
            radius *= ModConfig.AWAKEN_JUDGMENT_RANGE_MULT.get();
        }
        AABB aabb = player.getBoundingBox().inflate(radius);
        return player.level().getEntitiesOfClass(LivingEntity.class, aabb,
            e -> e != player && e.isAlive() && isHostileTarget(player, e));
    }

    /**
     * 觉醒死亡抗拒触发时调用：无视冷却和 GCD 释放一次审判。
     * 仅造成伤害，不消耗冷却、不触发 GCD。
     *
     * @param player 释放者
     * @return 受影响的实体数量
     */
    public static int executeJudgment(ServerPlayer player) {
        if (player.level().isClientSide()) return 0;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return 0;
        return executeJudgment(player, collectJudgmentTargets(player, progressOpt.get()));
    }

    /**
     * 审判执行体（targets 由调用方传入——handleJudgment 复用空放预检的收集结果，
     * 免费审判入口自收集，避免同一释放做两次 AABB 查询）。
     *
     * @param player 释放者
     * @param targets 已收集的范围内敌对目标
     * @return 受影响的实体数量
     */
    private static int executeJudgment(ServerPlayer player, List<LivingEntity> targets) {
        if (player.level().isClientSide()) return 0;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return 0;
        var progress = progressOpt.get();

        // 防御性门禁：冒险者 + 里程碑解锁 active_skill 即可释放审判
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return 0;
        if (!progress.isAbilityEnabled(AbilityIds.ACTIVE_SKILL)) return 0;

        // 指令后门解锁的 active_skill 按解锁时刻快照平移，之后随里程碑正常成长
        int milestones = com.ayin90723.adventure_power.util.AbilityGate.effectiveCount(progress, AbilityIds.ACTIVE_SKILL);
        if (milestones == 0) milestones = 1;

        float baseDamage = (float) (double) ModConfig.ACTIVE_SKILL_JUDGMENT_BASE_DAMAGE.get();
        float hpRatio = (float) (double) ModConfig.ACTIVE_SKILL_JUDGMENT_HP_RATIO.get() * milestones;

        if (targets.isEmpty()) return 0;
        ServerLevel level = (ServerLevel) player.level();
        for (LivingEntity target : targets) {
            float maxHpPart = target.getMaxHealth() * hpRatio;
            // 架空参照读数：自定义血条 Boss（亚波伦）原版槽被架空，百分比基准与兜底检测取真实血量
            float currentHpPart = HealthUtil.getEffectiveHealth(target) * hpRatio;
            float totalDamage = baseDamage + maxHpPart + currentHpPart;

            var source = DamageUtil.createJudgment(level, player);
            float healthBefore = HealthUtil.getEffectiveHealth(target);
            target.hurt(source, totalDamage);
            float actualDealt = healthBefore - HealthUtil.getEffectiveHealth(target);
            target.invulnerableTime = 0;

            // v1.4.2：拦截判定容差量纲化（大血量目标读数 ulp 地板，同淬魂）
            float epsilon = ProbeScales.interceptTolerance(totalDamage, healthBefore);
            if (target.isAlive() && actualDealt < totalDamage - epsilon) {
                float correctedHealth = Math.max(healthBefore - totalDamage, 0.0F);
                // v1.4.3 二十轮：清盾前置已下沉引擎 execute 磨血分支统一处理（调用点零纪律）
                // v1.4.2：五层引擎（磨血语义）--L3/L4 覆盖静态 Map/加密存储型高级 Boss；
                // 全层失败退 raw（与原 setAllHealthLikeRaw 行为等价）
                BloodWriteEngine.execute(target, correctedHealth);
                if (correctedHealth <= 0.0F) {
                    // v1.4.3 十七轮定调（与淬魂一致）：不主动调 die——主动 die 制造半开门
                    // 状态（die 事件已发、死亡流程未走完），对面当遭遇中断恢复；写 0 后
                    // 对面自然死接管，击杀归属经 lastHurtBy 传递，处决由影杀兜底
                    target.invulnerableTime = 0;
                    target.setLastHurtByMob(player);
                    target.setLastHurtByPlayer(player);
                }
            }
        }

        // 音效 + 粒子（粒子范围与审判半径一致：觉醒 +50%）
        double radius = ModConfig.ACTIVE_SKILL_JUDGMENT_RADIUS.get();
        if (progress.isFullyUnlocked()) {
            radius *= ModConfig.AWAKEN_JUDGMENT_RANGE_MULT.get();
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.0F, 0.8F);
        for (int i = 0; i < 60; i++) {
            double angle = Math.random() * Math.PI * 2;
            double dist = Math.random() * radius;
            double x = player.getX() + Math.cos(angle) * dist;
            double z = player.getZ() + Math.sin(angle) * dist;
            level.sendParticles(ParticleTypes.END_ROD, x, player.getY() + 1.0, z,
                1, 0, 0, 0, 0.05);
            if (i % 3 == 0) {
                level.sendParticles(ParticleTypes.DRAGON_BREATH, x, player.getY() + 0.5, z,
                    1, 0, 0, 0, 0.02);
            }
        }

        return targets.size();
    }

    /** 目标判定：排除玩家(PVP)和友好火力(驯服生物)，其余都打 */
    private static boolean isHostileTarget(Player player, LivingEntity target) {
        if (target instanceof Player) return false;
        if (FriendlyFireProtection.isOwnerTarget(player, target)) return false;
        return true;
    }

    // ===== 旅者庇护 =====

    private static void handleSanctuary(ServerPlayer player, IAdventureProgress progress, long currentTime) {
        // 冷却检查
        long cdEnd = progress.getSanctuaryCooldownEnd();
        if (cdEnd > 0 && currentTime < cdEnd) return;

        int duration = ModConfig.ACTIVE_SKILL_SANCTUARY_DURATION.get();
        int cooldown = ModConfig.ACTIVE_SKILL_SANCTUARY_COOLDOWN.get();
        int gcd = ModConfig.ACTIVE_SKILL_GCD.get();

        progress.setSanctuaryInvulEnd(currentTime + duration);
        progress.setSanctuaryCooldownEnd(currentTime + cooldown);
        progress.setActiveSkillGcdEnd(currentTime + gcd);
        SyncUtil.syncCapabilityToPersistent(player, progress);
        SyncUtil.syncToClient(player);

        // 清除玩家身上负面效果（与死亡抗拒一致，先快照再 remove 防 CME）
        for (MobEffectInstance e : new ArrayList<>(player.getActiveEffects())) {
            if (e.getEffect().getCategory() == MobEffectCategory.HARMFUL) {
                player.removeEffect(e.getEffect());
            }
        }
        player.clearFire();

        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
        // 图腾粒子环绕
        for (int i = 0; i < 30; i++) {
            double angle = Math.random() * Math.PI * 2;
            double yOff = Math.random() * 2.0;
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                player.getX() + Math.cos(angle) * 0.5,
                player.getY() + yOff,
                player.getZ() + Math.sin(angle) * 0.5,
                1, 0, 0, 0, 0.02);
        }
    }
}
