package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.input.DoubleJumpHandler;
import com.ayin90723.adventure_power.util.MilestoneTriggerManager;
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
 * 执行顺序（门禁后）：PlayerTickHandler -> PlayerStateHandler -> ExplorationAbilityHandler
 * -> RecoveryHandler -> KnockbackResistHandler -> FortuneFavorHandler -> MagnetHandler
 * -> AllSeeingHandler -> SwiftHandler -> MilestoneTriggerManager(surviveNight/yBelow/reachY)
 * -> DoubleJumpHandler.onTick。各 handler 的
 * onTick 不再做门禁检查（由本分发器统一做），仅保留业务逻辑。
 * <p>
 * 开局安全网（补发饰品 + 自动激活冒险者）与庇护速度维护需对非冒险者执行，在门禁前调用。
 * tickSafetyNet 修改的是同一 progress 实例，激活结果对下方门禁立即可见（同 tick 分发）。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class PlayerTickDispatcher {

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;

        // 死亡态短路（v1.4.9.7，防御纵深）：已死/已移除玩家的 per-tick 能力处理整体无意义，
        // 且是"内部写入复活尸体"这类事故的唯一入口面——实测事故为休养生息把尸体血量抬到 >0，
        // 触发原版重生门禁（ServerGamePacketListenerImpl.handleClientCommand 的
        // `getHealth() > 0 → return`）静默吞掉重生请求，玩家停在"服务端实体已移除、血量 >0"
        // 的幽灵态（挖不了方块/区块与实体跟踪停摆）。此处整体短路后，30 个能力都不会再对
        // 死亡态玩家做任何写入。
        // 判据用 isDeadOrDying()：原版语义即 `!(getHealth() > 0)`，取方法读——真血备份 >0 的
        // 假死态返回备份值（>0）不受影响，假死救场仍由真血链路独立完成。
        if (player.isRemoved() || player.isDeadOrDying()) return;

        var progress = AdventureProgressCapability.getAdventureProgress(player).orElse(null);
        // 门禁前：开局安全网（需对非冒险者执行：补发饰品 + 自动激活冒险者）
        PlayerTickHandler.tickSafetyNet(player);

        if (progress == null) return;

        // 门禁前：庇护移动速度维护——else 恢复分支不能受 adventurer 门禁限制
        // （卸下饰品后门禁失效，若只在门禁后执行，庇护写入的 base=0 将永久锁死移动）
        PlayerStateHandler.tickSanctuarySpeed(player, progress);

        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

        // 门禁后：按固定顺序分发
        PlayerTickHandler.onTick(player, progress);
        PlayerStateHandler.onTick(player, progress);
        ExplorationAbilityHandler.onTick(player, progress);
        RecoveryHandler.onTick(player, progress);
        KnockbackResistHandler.onTick(player, progress);
        FortuneFavorHandler.onTick(player, progress);
        MagnetHandler.onTick(player, progress);
        AllSeeingHandler.onTick(player, progress);
        SwiftHandler.onTick(player, progress);
        // 里程碑触发器检测（原独立 PlayerTickEvent 订阅并入，消除每 tick 重复 resolve）
        MilestoneTriggerManager.onTickSurviveNight(player, progress);
        MilestoneTriggerManager.onTickYBelow(player, progress);
        MilestoneTriggerManager.onTickReachY(player, progress);
        // 二段跳落地清零（无 capability 依赖，仅需 END phase 每 tick 一次）
        DoubleJumpHandler.onTick(player);
    }
}
