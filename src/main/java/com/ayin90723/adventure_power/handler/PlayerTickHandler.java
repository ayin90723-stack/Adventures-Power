package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.AdventureItemNbtUtil;
import com.ayin90723.adventure_power.util.BuffExclusionManager;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import com.ayin90723.adventure_power.util.ScoreboardUtil;
import com.ayin90723.adventure_power.util.SyncUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
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
 * 玩家 Tick 处理 - 开局安全网 + 周期性能力逻辑。
 * <p>
 * 从 PlayerTickDispatcher 分发调用（不再独立订阅 PlayerTickEvent）。处理：
 * <ul>
 *   <li>开局安全网（门禁前）：补发冒险饰品 + 自动激活冒险者 + 测试入口全解锁</li>
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

    /**
     * 开局安全网（门禁前，由 PlayerTickDispatcher 调用）。
     * 补发冒险饰品 + 自动激活冒险者（每玩家仅一次）+ 测试入口全解锁。
     * 需对非冒险者执行，故在分发器门禁前调用。
     */
    public static void tickSafetyNet(Player player, IAdventureProgress progress) {
        // 补发冒险饰品 + 自动激活冒险者（每玩家仅一次，persistentData 标记）
        if (!player.getPersistentData().getBoolean(PersistentDataKeys.VERIFIED_BEGIN_ITEM_KEY)) {
            player.getPersistentData().putBoolean(PersistentDataKeys.VERIFIED_BEGIN_ITEM_KEY, true);
            CapabilityLifecycleHandler.giveAdventureBeginIfNeeded(player);
            CapabilityLifecycleHandler.checkAndActivateAdventurer(player);
        }

        // 测试便捷入口：持有冒险的终点 -> 自动全解锁（每 20 tick 检查一次，降低物品栏遍历开销）
        if (progress == null || progress.isFullyUnlocked()) return;
        long currentTime = player.level().getGameTime();
        if (currentTime % 20 != 0) return;
        if (!AdventureItemNbtUtil.playerHasAdventureEnd(player)) return;

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

        // 翱翔飞行立即同步：fullyUnlocked 不等下一 tick handler，
        // 避免两处 TickEvent.Phase.END handler 执行顺序不确定导致的竞态
        if (progress.isAbilityEnabled("soar") && !player.getAbilities().mayfly
            && !player.getAbilities().instabuild && !player.isSpectator()) {
            player.getAbilities().mayfly = true;
            player.onUpdateAbilities();
        }
    }

    /**
     * 门禁后业务（由 PlayerTickDispatcher 调用）：
     * Buff 延长 / 环境免疫 / 受击坚韧超时 / 庇护无敌过期。
     */
    public static void onTick(Player player, IAdventureProgress progress) {
        long currentTime = player.level().getGameTime();

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

        // 环境免疫：每 tick 清除火焰（先检查是否着火，避免无火时的不必要同步）
        if (progress.isAbilityEnabled("env_immunity") && player.getRemainingFireTicks() > 0) {
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
        UUID uuid = event.getEntity().getUUID();
        lastBuffCheck.remove(uuid);
        BuffExclusionManager.clearCache(uuid);
        MagnetHandler.onLogout(uuid);
        SwiftHandler.onLogout(uuid);
    }
}
