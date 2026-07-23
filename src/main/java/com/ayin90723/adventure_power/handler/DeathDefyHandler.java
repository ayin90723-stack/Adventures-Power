package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.SyncUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

import java.util.stream.Collectors;

/**
 * 死亡抗拒 - 监听玩家死亡事件（HIGHEST 优先级），能力启用且冷却结束时取消死亡、回满血、进入无敌。
 * <p>
 * 从 AdventureProgressCapability 拆出。syncCapabilityToPersistent/syncToClient 暂回调
 * AdventureProgressCapability（阶段2 提取 SyncUtil 后改调）。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class DeathDefyHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled("death_defy")) return;

            long currentTime = player.level().getGameTime();
            if (progress.getDeathDefyCooldownEnd() > currentTime) return; // 冷却中

            // 取消死亡
            event.setCanceled(true);

            // 清除所有有害药水效果（必须在 setHealth 之前，否则含降 maxHealth 的效果会导致回不满血）
            player.getActiveEffects().stream()
                .filter(e -> e.getEffect().getCategory() == MobEffectCategory.HARMFUL)
                .map(MobEffectInstance::getEffect)
                .collect(Collectors.toList())
                .forEach(player::removeEffect);
            player.clearFire();

            // 双轨恢复：取 getMaxHealth() 和 20 的较大值
            // 上限 - 支持 Vitality 等能力提升的最大生命值
            // 下限 - 防止外部模组（如亚波伦结界）临时污染 maxHealth 导致复活后血量过低
            float restoreHealth = Math.max(20.0F, player.getMaxHealth());
            HealthUtil.setHealthDirect(player, restoreHealth);
            player.setHealth(restoreHealth);

            // 写入无敌和冷却结束时间
            long invulEnd = currentTime + ModConfig.DEATH_DEFY_INVUL_DURATION.get();
            long cooldownEnd = currentTime + ModConfig.DEATH_DEFY_COOLDOWN_DURATION.get();
            progress.setDeathDefyInvulEnd(invulEnd);
            progress.setDeathDefyCooldownEnd(cooldownEnd);

            SyncUtil.syncCapabilityToPersistent(player, progress);
            SyncUtil.syncToClient(player);

            // 觉醒：触发死亡抗拒时自动释放一次免费旅者审判
            if (progress.isFullyUnlocked() && progress.isAbilityEnabled("active_skill")) {
                com.ayin90723.adventure_power.skill.ActiveSkillHandler.executeJudgment(
                    (ServerPlayer) player);
            }

            if (player.level() instanceof ServerLevel serverLevel) {
                serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
                serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    player.getX(), player.getY() + 1.5, player.getZ(),
                    50, 0.5, 0.5, 0.5, 0.3);
            }
        });
    }
}
