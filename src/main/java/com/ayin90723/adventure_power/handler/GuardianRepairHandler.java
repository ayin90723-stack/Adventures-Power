package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.mixin.EntityFieldsAccessor;
import com.ayin90723.adventure_power.mixin.LivingEntityFieldsAccessor;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.ClassPointerGuard;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.GuardianThread;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 容器抹除防线 · 主线程消费侧（v1.3.9）—— 通道 B。
 * <p>
 * 守护线程（{@link GuardianThread}）检测到异常后只置标记；本 handler 在
 * ServerTickEvent 消费标记并执行修复。与通道 A（TrueHealthMixin tick 自检）
 * 互补：实体被容器抹除后<b>不再 tick</b>，tick 自检永不触发，唯有
 * ServerTickEvent（不依赖任何实体）能在下一主 tick（≤50ms）内完成重新注册。
 * <p>
 * <b>统一门禁</b>（与 TrueHealthMixin.onSetRemoved 语义一致）：
 * <ul>
 *   <li>玩家在线（server.getPlayerList 包含）—— 登出玩家放行</li>
 *   <li>backup &gt; 0（真血激活）—— 正常死亡（backup 已同步为 0）放行</li>
 *   <li>removalReason ∉ {CHANGED_DIMENSION, UNLOADED_WITH_PLAYER} ——
 *       换维度/登出流程放行，不干预原版移除</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuardianRepairHandler {

    private static boolean debugLog() {
        return ModConfig.DEBUG_LOG.get() && ModConfig.DEBUG_LOG_TRUE_HEALTH.get();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (GuardianThread.isEmpty()) return;
        var server = event.getServer();
        if (server == null) return;

        for (var entry : GuardianThread.pendingEntries()) {
            Entity key = entry.getKey();
            if (key == null) continue;
            int flags = entry.getValue();
            if (!(key instanceof ServerPlayer sp)) {
                GuardianThread.consume(key);
                continue;
            }
            if (!server.getPlayerList().getPlayers().contains(sp)) {
                // 玩家已离线：仅清标记，不修复（登记由 onPlayerLogout 注销）
                GuardianThread.consume(sp);
                continue;
            }
            // 门禁：backup > 0 + reason 门禁
            IAdventureProgress progress = AdventureProgressCapability.getAdventureProgress(sp).orElse(null);
            if (progress == null) {
                GuardianThread.consume(sp);
                continue;
            }
            if (progress.getBackupHealth() <= 0.0F) {
                // 正常死亡（备份已同步为 0）—— 放行死亡流程
                GuardianThread.consume(sp);
                continue;
            }
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                GuardianThread.consume(sp);
                continue;
            }
            if (!progress.isAbilityEnabled(AbilityIds.TRUE_HEALTH)) {
                GuardianThread.consume(sp);
                continue;
            }
            // reason 门禁：换维度/登出流程放行（removalReason 非 null 且为这两者时不修）
            Entity.RemovalReason reason =
                ((EntityFieldsAccessor) (Object) sp).adventure_power$getRemovalReason();
            if (reason == Entity.RemovalReason.CHANGED_DIMENSION
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
                // 正常流程（换维度 addEntity 前/登出清理中）—— 不干预
                GuardianThread.consume(sp);
                continue;
            }
            repair(sp, flags, progress);
            GuardianThread.consume(sp);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player != null) {
            GuardianThread.unregister(player);
        }
    }

    /** 按位修复（主线程执行） */
    private static void repair(ServerPlayer sp, int flags, IAdventureProgress progress) {
        if (debugLog()) {
            DebugLog.trueHealth("[MME-TrueHealth] 守护线程标记消费：flags=" + flags
                + " player=" + sp.getName().getString());
        }

        // BIT_HEALTH：血量非法（NaN/±Inf/负值）→ 修复为备份值
        if ((flags & GuardianThread.BIT_HEALTH) != 0) {
            float backup = progress.getBackupHealth();
            if (Float.isFinite(backup) && backup > 0.0F) {
                HealthUtil.setAllHealthLikeRaw(sp, backup);
                HealthUtil.clearNegativeFloatDeltas(sp);
            }
        }

        // BIT_STATE：dead/deathTime 字段被直写 → 归位
        if ((flags & GuardianThread.BIT_STATE) != 0) {
            LivingEntityFieldsAccessor fields = (LivingEntityFieldsAccessor) (Object) sp;
            fields.adventure_power$setDead(false);
            fields.adventure_power$setDeathTime(0);
        }

        // BIT_REMOVED：removalReason/isAddedToWorld 被直写 → 恢复
        if ((flags & GuardianThread.BIT_REMOVED) != 0) {
            EntityFieldsAccessor efields = (EntityFieldsAccessor) (Object) sp;
            efields.adventure_power$setRemovalReason(null);
            efields.adventure_power$setAddedToWorld(true);
        }

        // BIT_CLASS：类指针被替换 → 换回原类并清死亡状态残留
        if ((flags & GuardianThread.BIT_CLASS) != 0) {
            if (ClassPointerGuard.restore(sp)) {
                LivingEntityFieldsAccessor fields = (LivingEntityFieldsAccessor) (Object) sp;
                fields.adventure_power$setDead(false);
                fields.adventure_power$setDeathTime(0);
                HealthUtil.clearRemovedFlag(sp);
            }
        }

        // BIT_CONTAINER：从世界容器抹除 → 重新注册回 ServerLevel
        if ((flags & GuardianThread.BIT_CONTAINER) != 0) {
            // 复查（H-1 防护）：换维度残留标记场景——玩家已在当前维度 byId 表
            // （revive() 清 removalReason 后新维度注册完成），直接跳过修复。
            // 否则 clearContainerResidue 会把玩家从新维度容器"先清后注册"，
            // 若 addPlayer 异常/cancel 则玩家彻底丢失（卡死，需重登）。
            if (HealthUtil.isMissingFromEntityLookup(sp)) {
                EntityFieldsAccessor efields = (EntityFieldsAccessor) (Object) sp;
                efields.adventure_power$setAddedToWorld(true);
                HealthUtil.addEntityBackToWorld(sp);
            }
        }
    }
}
