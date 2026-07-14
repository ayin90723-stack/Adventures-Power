package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 成就事件处理器 —— 监听原版成就，匹配里程碑后直接解锁。
 * <p>
 * 不再创建任何模组成就，不依赖 achievement JSON 文件。
 * 玩家进度通过饰品 tooltip 和右键查看。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class AdvancementEventHandler {

    @SubscribeEvent
    public static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceLocation earnedId = event.getAdvancement().getId();

        // 原版成就 → 匹配里程碑 → 直接解锁
        Milestone linked = MilestoneRegistry.getByAdvancement(earnedId);
        if (linked != null && AdventureProgressCapability.isAdventurer(player)) {
            AdventureProgressCapability.grantMilestone(player, linked.id());
        }
    }

    /**
     * 补回遗漏的里程碑——玩家成为冒险者时，扫描所有已完成的原版成就，
     * 将 Capability 中缺失的里程碑同步回来。静默同步，不播放音效。
     */
    public static void catchUpMissedMilestones(ServerPlayer player) {
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        boolean anyChanged = false;
        for (Milestone m : MilestoneRegistry.getAll()) {
            if (progress.isMilestoneUnlocked(m.id())) continue;
            // 先检查 advancement
            if (m.advancement() != null) {
                Advancement adv = player.server.getAdvancements().getAdvancement(m.advancement());
                if (adv != null && isAdvancementDone(player, adv)) {
                    progress.unlockMilestone(m.id());
                    anyChanged = true;
                    continue;
                }
            }
            // 再检查 trigger 是否已可触发（所有 5 种类型均支持追赶）
            if (m.trigger() != null) {
                boolean met = switch (m.trigger().type()) {
                    case "survive_night" -> player.level().getDayTime() > 24000 && player.level().isDay();
                    case "y_below" -> player.getY() < (m.trigger().y() != null ? m.trigger().y() : 0);
                    case "first_death" -> player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)) > 0;
                    case "first_trade" -> player.getStats().getValue(Stats.CUSTOM.get(Stats.TALKED_TO_VILLAGER)) > 0;
                    case "first_kill" -> {
                        if (m.trigger().entity() == null) yield false;
                        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(m.trigger().entity());
                        yield type != null && player.getStats().getValue(Stats.ENTITY_KILLED.get(type)) > 0;
                    }
                    default -> false;
                };
                if (met) {
                    progress.unlockMilestone(m.id());
                    anyChanged = true;
                }
            }
        }

        if (anyChanged) {
            AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
            AdventureProgressCapability.syncAllAdventureItemNbt(player, progress);
            AdventureProgressCapability.syncToClient(player);
        }
    }

    private static boolean isAdvancementDone(ServerPlayer player, Advancement adv) {
        AdvancementProgress advProgress = player.getAdvancements().getOrStartProgress(adv);
        return advProgress.isDone();
    }
}
