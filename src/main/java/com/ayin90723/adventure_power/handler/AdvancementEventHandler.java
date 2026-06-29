package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.milestone.Milestone;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成就事件处理器 —— 连接原版成就系统和我们的里程碑系统。
 * <p>
 * 三个方向：
 * <ol>
 *   <li><b>原版成就 → 我们的里程碑成就</b>：当冒险者玩家完成特定原版成就时，自动授予对应里程碑成就</li>
 *   <li><b>我们的成就 → Capability 同步</b>：当我们的成就被授予时，更新 Capability 里程碑数据</li>
 *   <li><b>追回遗漏里程碑</b>：玩家成为冒险者时，扫描已完成的原版/模组成就补回 Capability</li>
 * </ol>
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class AdvancementEventHandler {

    /** 原版成就 ID → 对应里程碑的映射（双向联动）。
     *  注意：监守者没有专门的原版成就，移除映射；
     *  我们的 warden.json 使用 player_killed_entity 原生触发器自动完成。 */
    static final Map<ResourceLocation, Milestone> VANILLA_TO_MILESTONE = new LinkedHashMap<>();

    static {
        VANILLA_TO_MILESTONE.put(new ResourceLocation("story/enchant_item"), Milestone.FIRST_ENCHANT);
        VANILLA_TO_MILESTONE.put(new ResourceLocation("story/enter_the_nether"), Milestone.NETHER);
        // 凋零：wither.json 使用 player_killed_entity 原生触发器（击杀时触发），
        // 不联动 nether/summon_wither（召唤时触发），避免放出来就完成成就
        VANILLA_TO_MILESTONE.put(new ResourceLocation("end/kill_dragon"), Milestone.DRAGON);
        VANILLA_TO_MILESTONE.put(new ResourceLocation("end/elytra"), Milestone.ELYTRA);
    }

    @SubscribeEvent
    public static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceLocation earnedId = event.getAdvancement().getId();

        // ===== 方向一：原版成就 → 授予我们的对应里程碑成就 =====
        Milestone linked = VANILLA_TO_MILESTONE.get(earnedId);
        if (linked != null) {
            // ★ 只有冒险者才授予——非冒险者由 catchUpMissedMilestones 在激活时补回
            if (AdventureProgressCapability.isAdventurer(player)) {
                grantMilestoneAdvancement(player, linked);
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
                // ★ 激活冒险者后扫描成就补回遗漏的里程碑
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
                Milestone milestone = Milestone.fromId(path);
                if (milestone != null) {
                    AdventureProgressCapability.grantMilestone(player, milestone);
                }
            }
        }
    }

    /**
     * 补回遗漏的里程碑——玩家成为冒险者时，扫描已完成的原版和模组成就，
     * 将 Capability 中缺失的里程碑同步回来。
     * <p>
     * 静默同步（不播放音效/粒子），避免在 catch-up 时刷屏。
     */
    public static void catchUpMissedMilestones(ServerPlayer player) {
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        boolean anyChanged = false;

        // 1. 扫描我们自己的成就
        for (Milestone m : Milestone.values()) {
            if (progress.isMilestoneUnlocked(m)) continue;
            ResourceLocation advId = new ResourceLocation(AdventurePower.MODID, m.getId());
            Advancement adv = player.server.getAdvancements().getAdvancement(advId);
            if (adv != null && isAdvancementDone(player, adv)) {
                progress.unlockMilestone(m);
                anyChanged = true;
            }
        }

        // 2. 扫描原版成就（联动映射）
        for (var entry : VANILLA_TO_MILESTONE.entrySet()) {
            Milestone m = entry.getValue();
            if (progress.isMilestoneUnlocked(m)) continue;
            Advancement adv = player.server.getAdvancements().getAdvancement(entry.getKey());
            if (adv != null && isAdvancementDone(player, adv)) {
                progress.unlockMilestone(m);
                anyChanged = true;
            }
        }

        if (anyChanged) {
            AdventureProgressCapability.syncCapabilityToPersistent(player, progress);
            AdventureProgressCapability.syncAllAdventureItemNbt(player, progress);
            AdventureProgressCapability.syncToClient(player);
        }
    }

    /** 弹出成就的 AdvancementProgress 并检查 isDone，避免每次调用 getOrStartProgress */
    private static boolean isAdvancementDone(ServerPlayer player, Advancement adv) {
        AdvancementProgress advProgress = player.getAdvancements().getOrStartProgress(adv);
        return advProgress.isDone();
    }

    /** 授予我们模组的里程碑成就 */
    public static void grantMilestoneAdvancement(ServerPlayer player, Milestone milestone) {
        ResourceLocation advId = new ResourceLocation(AdventurePower.MODID, milestone.getId());
        Advancement advancement = player.server.getAdvancements().getAdvancement(advId);
        if (advancement == null) return;

        // 已获得则跳过
        if (player.getAdvancements().getOrStartProgress(advancement).isDone()) return;

        player.getAdvancements().award(advancement, milestone.getId());
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
