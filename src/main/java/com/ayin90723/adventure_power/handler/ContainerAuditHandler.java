package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.ContainerAuditor;
import com.ayin90723.adventure_power.util.ContainerRebuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器审计驱动（v1.4.9 第一部分 1.6/1.7）——ServerTick END 周期巡检，禁用自由线程。
 * <p>
 * <b>枚举源 = 网络层玩家集 ∪ PlayerList 名册</b>（四轮评审：PlayerList.players 可被
 * 直删、受害者不在枚举内整链失明；连接独立于容器正是本链立论）。connections 列表
 * 迭代以 synchronized 包裹（vanilla 自身对其迭代即以 monitorenter 包裹，javap 核实）。
 * <p>
 * 分级动作：全健康 → 清告警标记；一级缺失（仅 A1/A2 异常）→ 轻修复（Callback 重建，
 * 不受 container_rebuild_enabled 限制）；二级缺失（A3~A9 任一）→ 完整重建（受
 * container_rebuild_enabled 门控）；重建后复查失败 → ERROR（去重），连续失败进入
 * 200 tick 退避（防每秒全链重试的性能陷阱与日志风暴）。
 * <p>
 * 换维度保护双保险：isChangingDimension 入口短路 + PlayerChangedDimensionEvent 登记
 * 40 tick 审计冷却（覆盖维度切换尾部 teleport/addPlayer 窗口）。登出清理全部 per-player
 * 状态。<b>不挂 PendingVerifyRegistry</b>（周期巡检 ≠ 探针域有界等待，混用污染
 * TaskKind 归属契约）。
 * <p>
 * 已知形态披露：对抗性持续抹除的"周期复活闪烁"（对手每 tick 抹、本链按周期复活）是
 * "攻击仍在持续"的表现而非重建失效——日志以重建计数持续增长为特征，排障勿误判。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ContainerAuditHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 换维度后审计冷却（tick）——覆盖 isChangingDimension 翻回 false 后 levelCallback 仍在新维度装配中的窗口。 */
    private static final long DIM_CHANGE_COOLDOWN = 40L;
    /** 重建失败退避时长（tick）。 */
    private static final long BACKOFF_TICKS = 200L;

    /** 周期计数器（ServerTick END 每跳 +1；独立计数而非 gameTime 取模——interval 配置热变更无跳变）。 */
    private static long tickCounter = 0L;

    /** 换维度冷却表：uuid → 冷却截止 gameTime。 */
    private static final Map<UUID, Long> DIM_COOLDOWN = new ConcurrentHashMap<>();
    /** 重建退避表：uuid → 退避截止 gameTime。 */
    private static final Map<UUID, Long> BACKOFF = new ConcurrentHashMap<>();
    /** 连续重建失败计数：uuid → 失败轮数。 */
    private static final Map<UUID, Integer> FAIL_STREAK = new ConcurrentHashMap<>();
    /** 告警标记：上一轮审计即缺失（缺失→健康的状态翻转才再告警，防周期刷屏）。 */
    private static final Map<UUID, Boolean> WARNED = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // 总开关（false 时零注册零开销——仅生命周期事件表清理不可达，无残留开销）
        if (!ModConfig.CONTAINER_AUDIT_ENABLED.get()) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        int interval = ModConfig.CONTAINER_AUDIT_INTERVAL_TICKS.get();
        if (interval < 1) interval = 20;
        if (++tickCounter % interval != 0L) return;

        long gameTime = server.overworld() != null ? server.overworld().getGameTime() : 0L;
        for (ServerPlayer player : enumeratePlayers(server)) {
            try {
                auditPlayer(player, gameTime);
            } catch (Exception e) {
                // per-player 异常隔离：单个玩家的审计意外不阻断整轮巡检
                LOGGER.warn("[容器审计] {} 巡检异常（跳过本轮）", player.getGameProfile().getName(), e);
            }
        }
    }

    /** 单玩家巡检：门禁 → 冷却/退避 → 审计 → 分级动作。 */
    private static void auditPlayer(ServerPlayer player, long gameTime) {
        UUID uuid = player.getUUID();

        // ① 两段式门禁（PlayerTickDispatcher 同款 + backup>0）：读空 → reviveCaps → 重读；
        //    仍空 / 非防御态 → 跳过（零开销）——vp 连招腿③不再使门禁失明
        IAdventureProgress progress = ContainerRebuilder.twoStageProgress(player);
        if (progress == null) return;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
        float backup = progress.getBackupHealth();
        if (!(backup > 0.0F) || !Float.isFinite(backup)) return;

        // ② 换维度双保险：isChangingDimension 短路 + 事件登记的冷却窗
        if (player.isChangingDimension()) return;
        Long dimCd = DIM_COOLDOWN.get(uuid);
        if (dimCd != null) {
            if (gameTime < dimCd) return;
            DIM_COOLDOWN.remove(uuid);
        }

        // ③ 退避窗
        Long backoff = BACKOFF.get(uuid);
        if (backoff != null) {
            if (gameTime < backoff) return;
            BACKOFF.remove(uuid);
        }

        // ④ 审计（全健康 → 清告警标记返回）
        ContainerAuditor.AuditResult result = ContainerAuditor.audit(player);
        if (result.allHealthy()) {
            if (WARNED.remove(uuid) != null) {
                LOGGER.info("[容器审计] {} 容器状态恢复健康（告警清除）", player.getGameProfile().getName());
                FAIL_STREAK.remove(uuid);
            }
            return;
        }

        // 复查修 P3-1：合法移除理由的告警层短路（与 gateOk 排除集对称）——登出竞态窗口
        // （PlayerLoggedOutEvent 已清四表 → connection 尚在 connections 列表）内
        // reason=UNLOADED_WITH_PLAYER 的误告警防护；误重建本就被 gateOk 拦，此处只拦
        // 告警层，重登后首轮审计全绿自愈。DISCARDED 不在此列（respawn 单 tick 完成
        // 审计拍不到；攻击 discard 放行后的 reason=DISCARDED 残留需要重建兜底）
        net.minecraft.world.entity.Entity.RemovalReason gateReason =
            ((com.ayin90723.adventure_power.mixin.EntityFieldsAccessor) (Object) player)
                .adventure_power$getRemovalReason();
        if (gateReason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER
            || gateReason == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION) {
            return;
        }

        // 告警（告警标记去重——持续缺失只在状态翻转时输出；A1 换装/A2 攻击签名证据必带）
        if (WARNED.put(uuid, Boolean.TRUE) == null) {
            LOGGER.warn("[容器审计] {} 容器缺失：{}{}{}", player.getGameProfile().getName(),
                result.describeMissing(),
                result.foreignCallbackClass != null ? "（callback 换装证据：原类=" + result.foreignCallbackClass + "）" : "",
                result.removalReasonEvidence != null ? "（攻击签名：removalReason=" + result.removalReasonEvidence + "）" : "");
            // 诊断细节走 debug_log_container 子开关（与告警分离）
            com.ayin90723.adventure_power.util.DebugLog.container(
                "[容器审计] {} 诊断：seenBy 观察者数={}（-1=追踪条目缺失/诊断不可用）",
                player.getGameProfile().getName(), result.seenByCount);
        } else if (result.removalReasonEvidence != null) {
            // A2 攻击签名证据：每轮记录（去重只在 WARNED 维度——持续攻击需持续可见），
            // per-player×去重模式与 isRemoved 日志同款：同玩家每审计周期至多一条
            LOGGER.warn("[容器审计] {} 攻击签名持续：removalReason={}",
                player.getGameProfile().getName(), result.removalReasonEvidence);
        }

        // ⑤ 分级动作：一级轻修复（不受重建开关限制）/ 二级完整重建（受开关门控）
        boolean ok;
        if (result.containerEntriesHealthy()) {
            ok = ContainerRebuilder.rebuild(player, false);
        } else if (ModConfig.CONTAINER_REBUILD_ENABLED.get()) {
            ok = ContainerRebuilder.rebuild(player, true);
        } else {
            LOGGER.error("[容器审计] {} 容器缺失但重建开关关闭（container_rebuild_enabled=false），仅告警",
                player.getGameProfile().getName());
            return;
        }

        // ⑥ 复查失败 → 连续失败计数 → 退避
        if (!ok) {
            int streak = FAIL_STREAK.merge(uuid, 1, Integer::sum);
            if (streak >= ModConfig.CONTAINER_REBUILD_BACKOFF_THRESHOLD.get()) {
                BACKOFF.put(uuid, gameTime + BACKOFF_TICKS);
                FAIL_STREAK.remove(uuid);
                LOGGER.error("[容器审计] {} 连续 {} 轮重建失败，进入 {} tick 退避（持续抹除攻击？重建计数增长为攻击特征）",
                    player.getGameProfile().getName(), streak, BACKOFF_TICKS);
            }
        } else {
            FAIL_STREAK.remove(uuid);
        }
    }

    // ==================== 枚举源：网络层 ∪ PlayerList 名册 ====================

    /**
     * 网络层玩家集（connection.packetListener instanceof ServerGamePacketListenerImpl 且
     * player != null）与 PlayerList.getPlayers() 并集去重——全 public API 零 accessor。
     * <p>
     * 复查修 P2-1：整体 try-catch——PlayerList.players 是普通 ArrayList，vanilla 登出路径
     * 把 remove 调度到 netty eventLoop，与主线程拷贝并发会抛 CME；异常若不吞会直达事件
     * 总线（违反"审计/重建链任何意外不得进主 tick"纪律）。PlayerList 半边拷贝失败时
     * 退化为仅网络层枚举（vp 连招腿②的正主恰恰在网络层半边，方向无损）。
     */
    private static List<ServerPlayer> enumeratePlayers(MinecraftServer server) {
        List<ServerPlayer> out;
        try {
            out = new ArrayList<>(server.getPlayerList().getPlayers());
        } catch (Exception e) {
            out = new ArrayList<>();
        }
        try {
            var connection = server.getConnection();
            if (connection == null) return out;
            List<Connection> connections = connection.getConnections();
            // vanilla 自身对 connections 的迭代即以 monitorenter 包裹（javap 核实）——照抄
            synchronized (connections) {
                for (Connection c : connections) {
                    try {
                        if (c.getPacketListener() instanceof ServerGamePacketListenerImpl listener
                            && listener.player != null && !out.contains(listener.player)) {
                            out.add(listener.player);
                        }
                    } catch (Exception ignored) {
                        // 连接正在断开等瞬时态——跳过
                    }
                }
            }
        } catch (Exception e) {
            // 网络层半边迭代失败（瞬时态）：退化为已收集的 PlayerList 半边，本轮照常
        }
        return out;
    }

    // ==================== 生命周期：换维度冷却登记 + 登出清理 ====================

    /** 换维度登记审计冷却（自订阅——CapabilityLifecycleHandler 不动，七轮评审定落点）。 */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        long now = player.level().getGameTime();
        DIM_COOLDOWN.put(player.getUUID(), now + DIM_CHANGE_COOLDOWN);
    }

    /** 登出清理：告警/退避/失败计数/冷却表条目（防累积）。 */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        DIM_COOLDOWN.remove(uuid);
        BACKOFF.remove(uuid);
        FAIL_STREAK.remove(uuid);
        WARNED.remove(uuid);
    }
}
