package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 玩家 tick 统一分发器 - 一次取 Capability + 门禁，按固定顺序调用各能力 handler。
 * <p>
 * 替代原本 5 个 handler 各自订阅 PlayerTickEvent 的模式（每玩家每 tick 5 次
 * Capability 解析 + 5 次门禁），减少到 1 次。
 * <p>
 * 执行顺序：PlayerTickHandler -> PlayerStateHandler -> ExplorationAbilityHandler
 * -> RecoveryHandler -> KnockbackResistHandler。各 handler 的 onTick 不再做
 * 门禁检查（由本分发器统一做），仅保留业务逻辑。
 * <p>
 * 开局安全网（补发饰品 + 测试入口）需对非冒险者执行，在门禁前调用。
 * 首次激活冒险者/全解锁后，本次 tick 用激活前 progress 快照，延迟 1 tick 进入
 * onTick（玩家无感知）。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class PlayerTickDispatcher {

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;

        var progress = AdventureProgressCapability.getAdventureProgress(player).orElse(null);
        // 门禁前：开局安全网（需对非冒险者执行：补发饰品 + 测试入口全解锁）
        PlayerTickHandler.tickSafetyNet(player, progress);

        if (progress == null) return;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

        // 门禁后：按固定顺序分发
        PlayerTickHandler.onTick(player, progress);
        PlayerStateHandler.onTick(player, progress);
        ExplorationAbilityHandler.onTick(player, progress);
        RecoveryHandler.onTick(player, progress);
        KnockbackResistHandler.onTick(player, progress);
        MagnetHandler.onTick(player, progress);
        AllSeeingHandler.onTick(player, progress);
        SwiftHandler.onTick(player, progress);
    }
}
