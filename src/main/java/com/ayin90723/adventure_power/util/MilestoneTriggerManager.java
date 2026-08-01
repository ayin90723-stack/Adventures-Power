package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.milestone.Milestone;
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
 * 支持 5 种 trigger type:
 * - survive_night: 度过第一夜
 * - first_death: 玩家首次死亡
 * - first_trade: 首次与村民交互
 * - y_below: Y 坐标低于指定值
 * - first_kill: 首次击杀指定实体
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MilestoneTriggerManager {

    /** 第一个黎明（23000-24000 为黎明段，24000 为新一天开始）——「度过第一夜」在黎明即达成 */
    private static final long FIRST_DAWN_TICKS = 23000;

    /** 已触发的玩家/里程碑集合（防止重复触发） */
    private static final Set<UUID> SURVIVE_NIGHT_TRIGGERED = new HashSet<>();
    private static final Set<UUID> FIRST_DEATH_TRIGGERED = new HashSet<>();
    private static final Set<UUID> FIRST_TRADE_TRIGGERED = new HashSet<>();
    private static final Map<UUID, Set<String>> Y_BELOW_TRIGGERED = new HashMap<>();
    private static final Map<UUID, Set<String>> FIRST_KILL_TRIGGERED = new HashMap<>();

    /** 玩家退出时清理所有关联的触发记录 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        SURVIVE_NIGHT_TRIGGERED.remove(uuid);
        FIRST_DEATH_TRIGGERED.remove(uuid);
        FIRST_TRADE_TRIGGERED.remove(uuid);
        Y_BELOW_TRIGGERED.remove(uuid);
        FIRST_KILL_TRIGGERED.remove(uuid);
    }

    // ===== survive_night（由 PlayerTickDispatcher 分发，已统一门禁与 resolve） =====

    /** 门禁后业务（由 PlayerTickDispatcher 调用）：survive_night 里程碑检测 */
    public static void onTickSurviveNight(Player player, IAdventureProgress progress) {
        UUID uuid = player.getUUID();

        if (SURVIVE_NIGHT_TRIGGERED.contains(uuid)) return;
        for (Milestone m : MilestoneRegistry.getByTriggerType("survive_night")) {
            if (progress.isMilestoneUnlocked(m.id())) continue;
            long dayTime = player.level().getDayTime();
            if (dayTime >= FIRST_DAWN_TICKS && player.level().isDay()) {
                if (player instanceof ServerPlayer sp) {
                    AdventureProgressCapability.grantMilestone(sp, m.id());
                }
                SURVIVE_NIGHT_TRIGGERED.add(uuid);
            }
        }
    }

    // ===== first_death =====

    @SubscribeEvent
    public static void onPlayerFirstDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.isCanceled()) return;

        UUID uuid = player.getUUID();
        if (FIRST_DEATH_TRIGGERED.contains(uuid)) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        for (Milestone m : MilestoneRegistry.getByTriggerType("first_death")) {
            if (progressOpt.map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
            AdventureProgressCapability.grantMilestone(player, m.id());
            FIRST_DEATH_TRIGGERED.add(uuid);
        }
    }

    // ===== first_trade =====

    @SubscribeEvent
    public static void onPlayerFirstTrade(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!(event.getTarget() instanceof Villager)) return;

        UUID uuid = player.getUUID();
        if (FIRST_TRADE_TRIGGERED.contains(uuid)) return;
        if (!AdventureProgressCapability.isAdventurer(player)) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        for (Milestone m : MilestoneRegistry.getByTriggerType("first_trade")) {
            if (progressOpt.map(p -> p.isMilestoneUnlocked(m.id())).orElse(true)) continue;
            AdventureProgressCapability.grantMilestone(player, m.id());
            FIRST_TRADE_TRIGGERED.add(uuid);
        }
    }

    // ===== y_below（由 PlayerTickDispatcher 分发，已统一门禁与 resolve） =====

    /** 门禁后业务（由 PlayerTickDispatcher 调用）：y_below 里程碑检测 */
    public static void onTickYBelow(Player player, IAdventureProgress progress) {
        UUID uuid = player.getUUID();
        Set<String> triggered = Y_BELOW_TRIGGERED.computeIfAbsent(uuid, k -> new HashSet<>());

        for (Milestone m : MilestoneRegistry.getByTriggerType("y_below")) {
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
}
