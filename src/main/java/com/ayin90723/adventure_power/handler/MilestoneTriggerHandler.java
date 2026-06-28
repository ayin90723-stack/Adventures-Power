package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.milestone.Milestone;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 早期里程碑触发器 — 处理没有原版成就触发器可用的 4 个里程碑。
 * <p>
 * 使用 {@code minecraft:impossible} 成就，由本类检测条件后手动授予。
 * 授予成就后，{@link AdvancementEventHandler} 会自动同步 Capability 和客户端。
 * <p>
 * 后期里程碑（首次附魔/下界/凋零/监守者/末影龙/鞘翅）已由 Advancement JSON
 * 中的原生触发器自动完成，无需 Java 代码处理。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MilestoneTriggerHandler {

    /** 日夜周期长度（tick）= 20 分钟 */
    private static final long DAY_CYCLE_TICKS = 24000;

    // ===== 1. 初次夜冕 — 度过第一夜 =====

    @SubscribeEvent
    public static void onPlayerTickFirstNight(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer()) return;
            if (progress.isMilestoneUnlocked(Milestone.FIRST_NIGHT)) return;

            long dayTime = player.level().getDayTime();
            if (dayTime > DAY_CYCLE_TICKS && player.level().isDay()) {
                AdvancementEventHandler.grantMilestoneAdvancement(sp, Milestone.FIRST_NIGHT);
            }
        });
    }

    // ===== 2. 初尝败绩 — 第一次死亡 =====

    @SubscribeEvent
    public static void onPlayerFirstDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.isCanceled()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer()) return;
            AdvancementEventHandler.grantMilestoneAdvancement(player, Milestone.FIRST_DEATH);
        });
    }

    // ===== 3. 初次交易 — 第一次与村民交互 =====

    @SubscribeEvent
    public static void onPlayerFirstTrade(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!(event.getTarget() instanceof Villager)) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer()) return;
            AdvancementEventHandler.grantMilestoneAdvancement(player, Milestone.FIRST_TRADE);
        });
    }

    // ===== 4. 初探地底 — 第一次进入 Y<0 =====

    @SubscribeEvent
    public static void onPlayerTickFirstDeep(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer()) return;
            if (progress.isMilestoneUnlocked(Milestone.FIRST_DEEP)) return;
            if (player.getY() < 0) {
                AdvancementEventHandler.grantMilestoneAdvancement(sp, Milestone.FIRST_DEEP);
            }
        });
    }
}
