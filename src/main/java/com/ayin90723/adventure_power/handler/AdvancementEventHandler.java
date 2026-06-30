package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 成就事件处理器 —— 连接原版成就系统和我们的里程碑系统。
 * <p>
 * 三个方向：
 * <ol>
 *   <li><b>已获得的成就 → 匹配里程碑</b>：当冒险者玩家完成某个与里程碑关联的成就时，自动授予里程碑</li>
 *   <li><b>我们的成就 → Capability 同步</b>：当我们的成就被授予时，更新 Capability 里程碑数据</li>
 *   <li><b>追回遗漏里程碑</b>：玩家成为冒险者时，扫描已完成的成就不回 Capability</li>
 * </ol>
 * <p>
 * 里程碑与成就的关联由数据包 milestones.json 的 advancement 字段定义，不再硬编码。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class AdvancementEventHandler {

    @SubscribeEvent
    public static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceLocation earnedId = event.getAdvancement().getId();

        // ===== 方向一：已获得的成就 → 检查是否匹配某个里程碑 =====
        Milestone linked = MilestoneRegistry.getByAdvancement(earnedId);
        if (linked != null) {
            // 只有冒险者才授予——非冒险者由 catchUpMissedMilestones 在激活时补回
            if (AdventureProgressCapability.isAdventurer(player)) {
                grantMilestoneAdvancement(player, linked.id());
            }
            return;
        }

        // ===== 方向二：我们的成就 → 更新 Capability =====
        if (AdventurePower.MODID.equals(earnedId.getNamespace())) {
            handleOurAdvancement(player, earnedId.getPath());
        }
    }

    /** 处理我们自己的成就被授予 */
    private static void handleOurAdvancement(ServerPlayer player, String path) {
        switch (path) {
            case "root" -> {
                AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                    if (!progress.isAdventurer()) {
                        progress.activateAdventurer();
                        AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
                        AdventureProgressCapability.syncAllAdventureItemNbt(player, progress);
                        AdventureProgressCapability.syncToClient(player);
                    }
                });
                // 激活冒险者后扫描成就补回遗漏的里程碑
                catchUpMissedMilestones(player);
            }
            case "adventure_end" -> {
                AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                    if (!progress.isFullyUnlocked()) {
                        progress.activateFullyUnlocked();
                        AdventureProgressCapability.updateScoreboard(player, true);
                        AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
                        AdventureProgressCapability.syncToClient(player);
                    }
                });
            }
            default -> {
                // 常规里程碑成就 → 同步 Capability
                if (MilestoneRegistry.contains(path)) {
                    AdventureProgressCapability.grantMilestone(player, path);
                }
            }
        }
    }

    /**
     * 补回遗漏的里程碑——玩家成为冒险者时，扫描所有 MilestoneRegistry 中定义的
     * 成就，将 Capability 中缺失的里程碑同步回来。
     * <p>
     * 静默同步（不播放音效/粒子），避免在 catch-up 时刷屏。
     */
    public static void catchUpMissedMilestones(ServerPlayer player) {
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        boolean anyChanged = false;

        // 扫描所有注册的里程碑对应的成就
        for (Milestone m : MilestoneRegistry.getAll()) {
            if (progress.isMilestoneUnlocked(m.id())) continue;
            ResourceLocation advId = m.advancement();
            if (advId == null) continue;
            Advancement adv = player.server.getAdvancements().getAdvancement(advId);
            if (adv != null && isAdvancementDone(player, adv)) {
                progress.unlockMilestone(m.id());
                anyChanged = true;
            }
        }

        if (anyChanged) {
            AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
            AdventureProgressCapability.syncAllAdventureItemNbt(player, progress);
            AdventureProgressCapability.syncToClient(player);
        }
    }

    /** 弹出成就的 AdvancementProgress 并检查 isDone */
    private static boolean isAdvancementDone(ServerPlayer player, Advancement adv) {
        AdvancementProgress advProgress = player.getAdvancements().getOrStartProgress(adv);
        return advProgress.isDone();
    }

    /** 授予我们模组的里程碑成就 */
    public static void grantMilestoneAdvancement(ServerPlayer player, String milestoneId) {
        ResourceLocation advId = new ResourceLocation(AdventurePower.MODID, milestoneId);
        Advancement advancement = player.server.getAdvancements().getAdvancement(advId);
        if (advancement == null) return;

        // 已获得则跳过
        if (player.getAdvancements().getOrStartProgress(advancement).isDone()) return;

        player.getAdvancements().award(advancement, milestoneId);
    }

    /** 授予根成就 */
    public static void grantRootAdvancement(ServerPlayer player) {
        ResourceLocation advId = new ResourceLocation(AdventurePower.MODID, "root");
        Advancement advancement = player.server.getAdvancements().getAdvancement(advId);
        if (advancement == null) return;
        if (player.getAdvancements().getOrStartProgress(advancement).isDone()) return;
        player.getAdvancements().award(advancement, "adventurer");
    }

    /** 授予终极成就 */
    public static void grantEndAdvancement(ServerPlayer player) {
        ResourceLocation advId = new ResourceLocation(AdventurePower.MODID, "adventure_end");
        Advancement advancement = player.server.getAdvancements().getAdvancement(advId);
        if (advancement == null) return;
        if (player.getAdvancements().getOrStartProgress(advancement).isDone()) return;
        player.getAdvancements().award(advancement, "end");
    }
}
