package com.rosycentury.adventure_power.skill;

import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.capability.IAdventureProgress;
import com.rosycentury.adventure_power.config.ModConfig;
import com.rosycentury.adventure_power.util.FriendlyFireProtection;
import com.rosycentury.adventure_power.util.HealthUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.stream.Collectors;

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

        // 门禁检查
        if (!progress.isFullyUnlocked()) return;
        if (!progress.isAbilityEnabled("active_skill")) return;

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

        // 消耗冷却
        int cooldown = ModConfig.ACTIVE_SKILL_JUDGMENT_COOLDOWN.get();
        int gcd = ModConfig.ACTIVE_SKILL_GCD.get();
        progress.setJudgmentCooldownEnd(currentTime + cooldown);
        progress.setActiveSkillGcdEnd(currentTime + gcd);
        AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
        AdventureProgressCapability.syncToClient(player);

        executeJudgment(player);
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
        var progress = progressOpt.get();

        int milestones = progress.getUnlockedMilestoneCount();
        if (milestones == 0) milestones = 1;

        float baseDamage = (float) (double) ModConfig.ACTIVE_SKILL_JUDGMENT_BASE_DAMAGE.get();
        float hpRatio = (float) (double) ModConfig.ACTIVE_SKILL_JUDGMENT_HP_RATIO.get() * milestones;
        double radius = ModConfig.ACTIVE_SKILL_JUDGMENT_RADIUS.get();

        // 觉醒：审判范围 +50%
        if (progress.isFullyUnlocked()) {
            radius *= ModConfig.AWAKEN_JUDGMENT_RANGE_MULT.get();
        }

        AABB aabb = player.getBoundingBox().inflate(radius);
        List<LivingEntity> targets = player.level().getEntitiesOfClass(LivingEntity.class, aabb,
            e -> e != player && e.isAlive() && isHostileTarget(player, e));

        if (targets.isEmpty()) return 0;

        ServerLevel level = (ServerLevel) player.level();
        var key = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("adventure_power", "judgment"));
        var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        var holder = registry.getHolderOrThrow(key);

        for (LivingEntity target : targets) {
            float maxHpPart = target.getMaxHealth() * hpRatio;
            float currentHpPart = target.getHealth() * hpRatio;
            float totalDamage = baseDamage + maxHpPart + currentHpPart;

            var source = new DamageSource(holder, null, player);
            float healthBefore = target.getHealth();
            target.hurt(source, totalDamage);
            float actualDealt = healthBefore - target.getHealth();
            target.invulnerableTime = 0;

            float epsilon = Math.max(0.01F, totalDamage * 0.01F);
            if (target.isAlive() && actualDealt < totalDamage - epsilon) {
                float correctedHealth = Math.max(healthBefore - totalDamage, 0.0F);
                HealthUtil.setAllHealthLikeRaw(target, correctedHealth);
                if (correctedHealth <= 0.0F) {
                    target.invulnerableTime = 0;
                    target.setLastHurtByMob(player);
                    target.setLastHurtByPlayer(player);
                    target.die(source);
                }
            }
        }

        // 音效 + 粒子
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

    /** 敌对目标判定：Monster（非驯服）+ 排除友好火力 */
    private static boolean isHostileTarget(Player player, LivingEntity target) {
        if (target instanceof Player) return false;
        if (FriendlyFireProtection.isOwnerTarget(player, target)) return false;
        if (target instanceof TamableAnimal) return false;
        // 铁傀儡 / 雪傀儡等服务端友方生物检测
        if (target.getType().getDescriptionId().contains("iron_golem")
            || target.getType().getDescriptionId().contains("snow_golem")) return false;
        return target instanceof Monster;
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
        AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
        AdventureProgressCapability.syncToClient(player);

        // 清除玩家身上负面效果（与死亡抗拒一致）
        player.getActiveEffects().stream()
            .filter(e -> e.getEffect().getCategory() == MobEffectCategory.HARMFUL)
            .map(MobEffectInstance::getEffect)
            .collect(Collectors.toList())
            .forEach(player::removeEffect);
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
