package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.handler.CapabilityLifecycleHandler;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.AdventureItemNbtUtil;
import com.ayin90723.adventure_power.util.BuffExclusionManager;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.ScoreboardUtil;
import com.ayin90723.adventure_power.util.SyncUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家 Tick 处理 - 每 tick 周期性能力逻辑 + 开局安全网。
 * <p>
 * 从 AdventureProgressCapability 拆出。处理：
 * <ul>
 *   <li>开局安全网：补发冒险饰品 + 自动激活冒险者（每玩家仅一次）</li>
 *   <li>测试入口：持有冒险的终点 -> 自动全解锁</li>
 *   <li>Buff 延长（恩赐永驻，每 60 tick）</li>
 *   <li>环境免疫（每 tick 清火）</li>
 *   <li>受击坚韧（超时层数归零）</li>
 *   <li>庇护无敌过期清除</li>
 * </ul>
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class PlayerTickHandler {

    private static final int BUFF_CHECK_INTERVAL = 60;
    private static final Map<UUID, Long> lastBuffCheck = new HashMap<>();
    private static final Set<UUID> verifiedBeginItem = new java.util.HashSet<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent event) {
        if (event.phase != Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;

        // 开局安全网：补发冒险饰品（若丢失）+ 自动激活冒险者
        if (!verifiedBeginItem.contains(player.getUUID())) {
            verifiedBeginItem.add(player.getUUID());
            CapabilityLifecycleHandler.giveAdventureBeginIfNeeded(player);
            CapabilityLifecycleHandler.checkAndActivateAdventurer(player);
        }

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        long currentTime = player.level().getGameTime();

        // 测试便捷入口：持有冒险的终点 -> 自动全解锁（每 tick 检查，已解锁则跳过）
        if (!progress.isFullyUnlocked() && AdventureItemNbtUtil.playerHasAdventureEnd(player)) {
            if (!progress.isAdventurer()) {
                progress.activateAdventurer();
            }
            for (Milestone m : MilestoneRegistry.getAll()) {
                progress.unlockMilestone(m.id());
            }
            progress.activateFullyUnlocked();
            ScoreboardUtil.updateScoreboard(player, true);
            SyncUtil.syncCapabilityToPersistent(player, progress);
            AdventureItemNbtUtil.syncAllAdventureItemNbt(player, progress);
            SyncUtil.syncToClient(player);

            // 翱翔飞行立即同步：fullyUnlocked 不等 PlayerStateHandler 下一 tick，
            // 避免两处 TickEvent.Phase.END handler 执行顺序不确定导致的竞态--
            // 若 PlayerStateHandler 先执行，soar 的 else 分支会剥离 mayfly 并发送
            // mayfly=false 到客户端，之后此处再发送 mayfly=true 覆盖（TCP 保序）。
            if (progress.isAbilityEnabled("soar") && !player.getAbilities().mayfly
                && !player.getAbilities().instabuild && !player.isSpectator()) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }

            // 所有里程碑已在上面解锁，无需再授予成就
        }

        // 门禁：非冒险者跳过后续的每 tick 能力处理（Buff 延长/环境免疫/受击坚韧/庇护过期）
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

        // Buff 延长（每 3 秒）
        if (progress.isAbilityEnabled("perpetual_blessing")) {
            long lastCheck = lastBuffCheck.getOrDefault(player.getUUID(), -1L);
            if (lastCheck == -1L) {
                lastBuffCheck.put(player.getUUID(), currentTime);
            } else if (currentTime - lastCheck >= BUFF_CHECK_INTERVAL) {
                lastBuffCheck.put(player.getUUID(), currentTime);
                extendBeneficialEffects(player);
            }
        } else {
            lastBuffCheck.remove(player.getUUID());
        }

        // 环境免疫：每 tick 清除火焰
        if (progress.isAbilityEnabled("env_immunity")) {
            player.clearFire();
        }

        // 受击坚韧：超过 5 秒无受伤 -> 层数归零
        if (progress.isAbilityEnabled("resilience")) {
            long lastHurt = progress.getLastHurtTime();
            if (lastHurt > 0 && currentTime - lastHurt >= ModConfig.RESILIENCE_RESET_TICKS.get()) {
                progress.setResilienceStacks(0);
                progress.setLastHurtTime(0);
            }
        }

        // 庇护无敌过期后清除（避免残留值，同步客户端和持久数据）
        if (progress.getSanctuaryInvulEnd() > 0 && currentTime >= progress.getSanctuaryInvulEnd()) {
            progress.setSanctuaryInvulEnd(0);
            SyncUtil.syncCapabilityToPersistent(player, progress);
            SyncUtil.syncToClient(player);
        }
    }

    private static void extendBeneficialEffects(Player player) {
        boolean extended = false;
        Set<String> excluded = BuffExclusionManager.getBuffExclusionSet(player);
        int minDuration = ModConfig.BUFF_MIN_DURATION.get();
        int extendAmount = ModConfig.BUFF_EXTEND_AMOUNT.get();
        int threshold = minDuration + extendAmount;
        for (MobEffectInstance effect : new ArrayList<>(player.getActiveEffects())) {
            if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                String effectId = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()).toString();
                if (excluded.contains(effectId)) continue;
                if (effect.getDuration() < threshold) {
                    extended = true;
                    player.addEffect(new MobEffectInstance(effect.getEffect(), threshold,
                        effect.getAmplifier(), effect.isAmbient(), effect.isVisible(), effect.showIcon()));
                }
            }
        }
        if (extended && player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.GLOW,
                player.getX(), player.getY() + 1.5, player.getZ(), 15, 0.5, 0.5, 0.5, 0.1);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerLoggedOutEvent event) {
        lastBuffCheck.remove(event.getEntity().getUUID());
        verifiedBeginItem.remove(event.getEntity().getUUID());
    }
}
