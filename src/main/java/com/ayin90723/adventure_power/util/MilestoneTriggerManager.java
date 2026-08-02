package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.milestone.Milestone;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 里程碑触发器管理器 — 根据 MilestoneRegistry 中的 trigger 定义注册事件监听。
 * 替代旧的硬编码 MilestoneTriggerHandler。
 *
 * 支持 8 种 trigger type:
 * - survive_night: 度过第一夜
 * - first_death: 玩家首次死亡
 * - first_trade: 首次与村民交互
 * - y_below: Y 坐标低于指定值
 * - first_kill: 首次击杀指定实体
 * - enter_dimension: 进入指定维度
 * - reach_y: Y 坐标达到指定值
 * - obtain_item: 拾取指定物品
 *
 * 「已触发」记录按 玩家 × 里程碑 ID 记录（per-milestone），
 * 保证 /reload 新增同类型里程碑后仍可正常触发。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MilestoneTriggerManager {

    /** 已触发的玩家/里程碑集合（Map&lt;UUID, Set&lt;里程碑ID&gt;&gt;，防止重复触发且支持 reload 新增里程碑） */
    private static final Map<UUID, Set<String>> SURVIVE_NIGHT_TRIGGERED = new HashMap<>();
    /** 本夜已处于夜晚的玩家（日落后标记，黎明/睡觉醒来后消费解锁）。
     *  不能依赖黎明窗口（dayTime 23000-24000）判定：睡觉会把 dayTime 直接推进到
     *  24000 倍数，每晚睡觉的玩家永远不经过该窗口——按"夜间标记 + 白天消费"判定 */
    private static final Map<UUID, Boolean> SURVIVE_NIGHT_PASSED = new HashMap<>();
    private static final Map<UUID, Set<String>> FIRST_DEATH_TRIGGERED = new HashMap<>();
    private static final Map<UUID, Set<String>> FIRST_TRADE_TRIGGERED = new HashMap<>();
    private static final Map<UUID, Set<String>> Y_BELOW_TRIGGERED = new HashMap<>();
    private static final Map<UUID, Set<String>> REACH_Y_TRIGGERED = new HashMap<>();
    private static final Map<UUID, Set<String>> FIRST_KILL_TRIGGERED = new HashMap<>();
    private static final Map<UUID, Set<String>> ENTER_DIMENSION_TRIGGERED = new HashMap<>();
    private static final Map<UUID, Set<String>> OBTAIN_ITEM_TRIGGERED = new HashMap<>();

    /** 玩家退出时清理所有关联的触发记录 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        SURVIVE_NIGHT_TRIGGERED.remove(uuid);
        SURVIVE_NIGHT_PASSED.remove(uuid);
        FIRST_DEATH_TRIGGERED.remove(uuid);
        FIRST_TRADE_TRIGGERED.remove(uuid);
        Y_BELOW_TRIGGERED.remove(uuid);
        REACH_Y_TRIGGERED.remove(uuid);
        FIRST_KILL_TRIGGERED.remove(uuid);
        ENTER_DIMENSION_TRIGGERED.remove(uuid);
        OBTAIN_ITEM_TRIGGERED.remove(uuid);
    }

    /** 该类型全部里程碑均已触发或已解锁 -> 提前退出，避免每 tick 空转 */
    private static boolean allDone(List<Milestone> ms, Set<String> triggered, IAdventureProgress progress) {
        for (Milestone m : ms) {
            if (!triggered.contains(m.id()) && !progress.isMilestoneUnlocked(m.id())) {
                return false;
            }
        }
        return true;
    }

    // ===== survive_night（由 PlayerTickDispatcher 分发，已统一门禁与 resolve） =====

    /** 门禁后业务（由 PlayerTickDispatcher 调用）：survive_night 里程碑检测 */
    public static void onTickSurviveNight(Player player, IAdventureProgress progress) {
        List<Milestone> ms = MilestoneRegistry.getByTriggerType("survive_night");
        if (ms.isEmpty()) return;
        UUID uuid = player.getUUID();
        Set<String> triggered = SURVIVE_NIGHT_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());
        if (allDone(ms, triggered, progress)) return;

        // 夜间（日落后 skyDarken≥4）：标记"本夜已度过"——正常度过与睡觉跳夜均覆盖
        if (player.level().isNight()) {
            SURVIVE_NIGHT_PASSED.put(uuid, true);
            return;
        }
        // 白天：消费标记（黎明段 isDay 已为 true / 睡觉醒来 dayTime=0）——本夜度过则解锁
        if (!Boolean.TRUE.equals(SURVIVE_NIGHT_PASSED.remove(uuid))) return;

        for (Milestone m : ms) {
            if (triggered.contains(m.id())) continue;
            if (progress.isMilestoneUnlocked(m.id())) continue;
            if (player instanceof ServerPlayer sp) {
                AdventureProgressCapability.grantMilestone(sp, m.id());
            }
            triggered.add(m.id());
        }
    }

    // ===== first_death =====

    @SubscribeEvent
    public static void onPlayerFirstDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.isCanceled()) return;

        UUID uuid = player.getUUID();
        Set<String> triggered = FIRST_DEATH_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        for (Milestone m : MilestoneRegistry.getByTriggerType("first_death")) {
            if (triggered.contains(m.id())) continue;
            if (progressOpt.map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
            AdventureProgressCapability.grantMilestone(player, m.id());
            triggered.add(m.id());
        }
    }

    // ===== first_trade =====

    @SubscribeEvent
    public static void onPlayerFirstTrade(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!(event.getTarget() instanceof Villager)) return;

        UUID uuid = player.getUUID();
        Set<String> triggered = FIRST_TRADE_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        for (Milestone m : MilestoneRegistry.getByTriggerType("first_trade")) {
            if (triggered.contains(m.id())) continue;
            if (progressOpt.map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
            AdventureProgressCapability.grantMilestone(player, m.id());
            triggered.add(m.id());
        }
    }

    // ===== y_below（由 PlayerTickDispatcher 分发，已统一门禁与 resolve） =====

    /** 门禁后业务（由 PlayerTickDispatcher 调用）：y_below 里程碑检测 */
    public static void onTickYBelow(Player player, IAdventureProgress progress) {
        List<Milestone> ms = MilestoneRegistry.getByTriggerType("y_below");
        if (ms.isEmpty()) return;
        UUID uuid = player.getUUID();
        Set<String> triggered = Y_BELOW_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());
        if (allDone(ms, triggered, progress)) return;

        for (Milestone m : ms) {
            if (triggered.contains(m.id())) continue;
            if (progress.isMilestoneUnlocked(m.id())) continue;
            int threshold = m.trigger().y() != null ? m.trigger().y() : 0;
            if (player.getY() < threshold) {
                if (player instanceof ServerPlayer sp) {
                    AdventureProgressCapability.grantMilestone(sp, m.id());
                }
                triggered.add(m.id());
            }
        }
    }

    // ===== reach_y（由 PlayerTickDispatcher 分发，已统一门禁与 resolve） =====

    /** 门禁后业务（由 PlayerTickDispatcher 调用）：reach_y 里程碑检测（Y ≥ 阈值） */
    public static void onTickReachY(Player player, IAdventureProgress progress) {
        List<Milestone> ms = MilestoneRegistry.getByTriggerType("reach_y");
        if (ms.isEmpty()) return;
        UUID uuid = player.getUUID();
        Set<String> triggered = REACH_Y_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());
        if (allDone(ms, triggered, progress)) return;

        for (Milestone m : ms) {
            if (triggered.contains(m.id())) continue;
            if (progress.isMilestoneUnlocked(m.id())) continue;
            int threshold = m.trigger().y() != null ? m.trigger().y() : 0;
            if (player.getY() >= threshold) {
                if (player instanceof ServerPlayer sp) {
                    AdventureProgressCapability.grantMilestone(sp, m.id());
                }
                triggered.add(m.id());
            }
        }
    }

    // ===== first_kill =====

    @SubscribeEvent
    public static void onPlayerFirstKill(LivingDeathEvent event) {
        // 弹射物击杀时 getEntity() 是箭/三叉戟本身，需追溯发射者（与破敌之眼共用解析逻辑）
        if (!(PiercingGazeUtil.resolveAttacker(event.getSource()) instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.isCanceled()) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        UUID uuid = player.getUUID();
        Set<String> triggered = FIRST_KILL_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        for (Milestone m : MilestoneRegistry.getByTriggerType("first_kill")) {
            if (triggered.contains(m.id())) continue;
            if (progressOpt.map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
            if (m.trigger().entity() == null) continue;

            EntityType<?> requiredType = ForgeRegistries.ENTITY_TYPES.getValue(m.trigger().entity());
            if (requiredType != null && event.getEntity().getType() == requiredType) {
                AdventureProgressCapability.grantMilestone(player, m.id());
                triggered.add(m.id());
            }
        }
    }

    // ===== enter_dimension =====

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        UUID uuid = player.getUUID();
        Set<String> triggered = ENTER_DIMENSION_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());

        // 1.20.1 中 PlayerChangedDimensionEvent.getTo() 返回 ResourceKey<Level>，取 location() 与 JSON 中的维度 ID 比较
        ResourceLocation to = event.getTo().location();
        for (Milestone m : MilestoneRegistry.getByTriggerType("enter_dimension")) {
            if (triggered.contains(m.id())) continue;
            if (progressOpt.map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
            if (m.trigger().dimension() == null) continue;
            if (to.equals(m.trigger().dimension())) {
                AdventureProgressCapability.grantMilestone(player, m.id());
                triggered.add(m.id());
            }
        }
    }

    // ===== obtain_item =====

    @SubscribeEvent
    public static void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(event.getStack().getItem());
        if (itemId == null) return;

        UUID uuid = player.getUUID();
        Set<String> triggered = OBTAIN_ITEM_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());

        for (Milestone m : MilestoneRegistry.getByTriggerType("obtain_item")) {
            if (triggered.contains(m.id())) continue;
            if (progressOpt.map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
            if (m.trigger().item() == null) continue;
            if (itemId.equals(m.trigger().item())) {
                AdventureProgressCapability.grantMilestone(player, m.id());
                triggered.add(m.id());
            }
        }
    }
}
