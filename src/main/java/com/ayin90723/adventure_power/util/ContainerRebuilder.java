package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.mixin.EntityFieldsAccessor;
import com.ayin90723.adventure_power.mixin.EntityLevelCallbackAccessor;
import com.ayin90723.adventure_power.mixin.PesmFieldAccessor;
import com.ayin90723.adventure_power.mixin.ServerLevelContainerAccessor;
import com.mojang.logging.LogUtils;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 容器重建器（v1.4.9 第一部分，写侧兜底）——审计确认容器缺失后恢复实体在世界容器中的接纳。
 * <p>
 * v1.3.9 守护线程方案失败后曾定调"容器抹除只能前置拦截不能事后恢复"；2026-09-01 对
 * 外部先例的解包核实修正该结论：<b>纯 Mixin + @Accessor 层面完全可行</b>，当时的真缺口
 * 是 PESM$Callback 钩子未重建（onRemove 后 levelCallback 被拧 NULL，跨 section 状态链
 * 死）与追踪/客户端广播链未显式恢复——本类补齐这两点。驱动源为 ServerTick END（禁用
 * 自由线程——外部先例的 daemon 线程跨线程写容器与主线程 forEach 换表存在竞态）。
 * <p>
 * 流程（每步前置 before 检查，幂等，大多数调用零写入直通）：
 * <ol>
 *   <li>门禁（排除式）+ 第 0 步血量/死亡状态归位（revive + repairHealth）</li>
 *   <li>第 0.2 步 UUID 归属校验（防双实体）</li>
 *   <li>二级缺失分支（A3~A9 任一为假）：旧 section 残留清理 → 重注册
 *       （addNewEntityWithoutEvent，Forge patch 无事件版）/ knownUuids 直补 → tick 表
 *       直塞（add 无 isRemoved 门禁）→ 追踪重建（onTrackingStart——v1.3.9"复活后客户端
 *       失联卡死"缺失的一环）→ players 表 → PlayerList 名册双表</li>
 *   <li>公共尾步：Callback 手工重建（缺失或换装；重注册成功装了新 Callback 时自然短路）</li>
 *   <li>尾部复查：以容器事实为准</li>
 * </ol>
 * 意外异常一律捕获返回 false（审计/重建链任何意外不得进主 tick）。
 */
public final class ContainerRebuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ContainerRebuilder() {
    }

    // ==================== 门禁 ====================

    /**
     * 两段式 Capability 读取（四轮评审，vp 连招腿③解法）：读空 → {@code reviveCaps()}
     * → 重读；仍空返回 null。{@code invalidateCaps()} 只置 valid=false 并跑 dispatcher
     * 级 listener，dispatcher 本体与各 provider 实例原样保留；{@code reviveCaps()} 只置
     * valid=true，原数据直接可读（47.4.10 源码核实）。整段包 try-catch 归"跳过"——
     * ServerTick 新代码通用防御纪律。
     */
    public static IAdventureProgress twoStageProgress(ServerPlayer player) {
        try {
            IAdventureProgress progress =
                AdventureProgressCapability.getAdventureProgress(player).orElse(null);
            if (progress == null) {
                player.reviveCaps();
                progress = AdventureProgressCapability.getAdventureProgress(player).orElse(null);
            }
            return progress;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 排除式重建门禁（评审修订，非法举法）：backup &gt; 0 && 在线 && !isChangingDimension
     * && reason ∉ {UNLOADED_WITH_PLAYER, CHANGED_DIMENSION}。
     * <p>
     * 依据：合法移除永远字段先行（remove(reason) 先写 removalReason 再走容器清理），
     * "在线玩家 + 容器缺失"本身就是非法状态，与 reason 取值无关；排除集只留两条真正
     * 合法的移除理由（登出——玩家已离线本就不在审计范围；换维度——由 isChangingDimension
     * 与调用方冷却窗覆盖）。UNLOADED_TO_CHUNK 对在线玩家永不合法（玩家实体不存在"卸载
     * 回区块"生命周期），归入重建集。<b>DISCARDED 不进排除集</b>（v1.4.9-fix 定案）：
     * respawn 的 DISCARDED 移除全程单 tick 同栈完成，ServerTick END 的审计拍不到中间态；
     * 而「先删名册再 discard」的组合拳会经 onSetRemoved 的名册判据放行（官方流程的
     * 实体侧可观察标志只有名册时序），放行后容器全丢 + reason=DISCARDED 残留——
     * 恰恰需要本链重建兜底（网络层枚举仍可达受害者，重建含 revive 清 reason）。
     */
    private static boolean gateOk(ServerPlayer player) {
        if (player.isChangingDimension()) return false;
        if (player.connection == null) return false;
        Entity.RemovalReason reason =
            ((EntityFieldsAccessor) (Object) player).adventure_power$getRemovalReason();
        if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER
            || reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            return false;
        }
        IAdventureProgress progress = twoStageProgress(player);
        // P3-7 门禁对齐：调用方（ContainerAuditHandler.auditPlayer）已门禁，此处自门禁补
        // isAdventurer 同构检查——防 backup>0 残留的非防御态实体被直调 rebuild 时误 revive/
        // 重注册（防御一致性，当前唯一调用方已门禁故不可达）
        if (progress == null) return false;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return false;
        return progress.getBackupHealth() > 0.0F
            && Float.isFinite(progress.getBackupHealth());
    }

    // ==================== 重建主流程 ====================

    /**
     * 重建入口（幂等；大多数调用零写入直通）。
     *
     * @param allowFullRebuild true = 允许二级重注册/直补分支（A3~A9 缺失时的完整重建，
     *                         受 container_rebuild_enabled 门控）；false = 仅一级轻修复
     *                         （血量归位 + 公共尾步 Callback 重建——容器条目全在时的
     *                         低风险路径，不受重建开关限制）
     * @return true 表示尾部复查全项健康
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean rebuild(ServerPlayer player, boolean allowFullRebuild) {
        try {
            if (!gateOk(player)) return false;
            if (!(player.level() instanceof ServerLevel level)) return false;

            PersistentEntitySectionManager rawPesm =
                ((ServerLevelContainerAccessor) (Object) level).adventure_power$getEntityManager();
            if (rawPesm == null) return false;
            UUID uuid = player.getUUID();
            ContainerAuditor.AuditResult before = ContainerAuditor.audit(player);

            // 第 0 步：血量/死亡状态归位（自家既有逻辑，先保证"活着"再谈容器）——公共
            player.revive();  // Forge patch：unsetRemoved + reviveCaps
            float gateBackup;
            IAdventureProgress progress = twoStageProgress(player);
            if (progress != null && progress.getBackupHealth() > 0.0F) {
                gateBackup = progress.getBackupHealth();
            } else {
                gateBackup = player.getMaxHealth();
            }
            HealthUtil.repairHealth(player, gateBackup);

            // 第 0.2 步：UUID 归属校验——别的实体已占用本 UUID 则放弃（防双实体）
            Entity other = level.getEntity(uuid);
            if (other != null && other != player && !other.isRemoved()) {
                LOGGER.warn("[容器重建] {} 放弃：UUID 已被活跃实体 {} 占用（uuid-owned-by-active-other）",
                    player.getGameProfile().getName(), other.getClass().getName());
                return false;
            }

            // ========== 二级缺失分支（A3~A9 任一为假）：重注册/直补路径（Callback 由尾步统一处理） ==========
            // allowFullRebuild=false（一级轻修复模式）：跳过本分支直接落尾步——容器条目
            // 全在时仅 Callback 可能被拧/换装，尾步闭环
            if (allowFullRebuild && !before.containerEntriesHealthy()) {

                // 第 0.5 步：旧 section 身份清理（防双份）——对实体所在 chunk 的全部已存在
                // section 扫描，按 == 身份移除残留条目（覆盖"只清了 tick/UUID 表没动 section"
                // 与"实体坐标已变、残留躺在旧坐标 section"两种情况；ClassInstanceMultiMap
                // 为 identity 语义，按 == 移除恰好）
                EntitySectionStorageCleaner.cleanCurrentChunkSections(rawPesm, player);

                // 第 3 步：空间索引/视图缺失 → 原版全链重注册（单一入口恢复
                // section+Callback+追踪+tick 表）；仅 knownUuids 缺失则直补
                if (!((before.healthyMask & ContainerAuditor.A_LOOKUP) != 0
                    && (before.healthyMask & ContainerAuditor.A_SECTION) != 0)) {
                    ((PesmFieldAccessor) (Object) rawPesm).adventure_power$getKnownUuids().remove(uuid);
                    // EntityLookup 预清（七轮评审 P1）：EntityLookup.add 在 byUuid 已含同 UUID
                    // 时 warn 后直接 return（byId 不写）——"byUuid 残留+byId 被抹"形态不预清
                    // 无法自愈；remove 双表同删，重注册 startTracking 全量重写
                    ((net.minecraft.world.level.entity.EntityLookup) (Object)
                        ((PesmFieldAccessor) (Object) rawPesm).adventure_power$getVisibleEntityStorage())
                        .remove(player);
                try {
                    // Forge patch 方法（无 tsrg 条目，生产保留 dev 名，强类型直调先例
                    // = Entity.revive()）；不发 EntityJoinLevelEvent，无被对手 cancel
                    // 中断重注册的风险。勿用事件版 m_157533_ addNewEntity。
                    // 复查修 P3-2：返回 false（addEntityUuid 查重拒绝等静默失败）与异常
                    // 同款处理——回滚 knownUuids 保重试资格（与 catch 路径对称）
                    if (!rawPesm.addNewEntityWithoutEvent(player)) {
                        throw new IllegalStateException("addNewEntityWithoutEvent returned false");
                    }
                } catch (Exception e) {
                        ((PesmFieldAccessor) (Object) rawPesm).adventure_power$getKnownUuids().add(uuid);
                        // visibleEntityStorage 预清不回滚——回滚会复活 EntityLookup.add 的
                        // 重复 UUID 早退；缺项由下次审计 A4 捕获重试（knownUuids 回滚是为
                        // 保 addEntityUuid 重试资格）
                        LOGGER.warn("[容器重建] {} 重注册失败（已回滚 knownUuids，下轮重试）",
                            player.getGameProfile().getName(), e);
                        return false;
                    }
                } else if ((before.healthyMask & ContainerAuditor.A_KNOWN_UUIDS) == 0) {
                    ((PesmFieldAccessor) (Object) rawPesm).adventure_power$getKnownUuids().add(uuid);
                }

                // 第 4 步：tick 表直塞（不必经 onTickingStart；EntityTickList.add 无 isRemoved 门禁）
                net.minecraft.world.level.entity.EntityTickList tickList =
                    ((ServerLevelContainerAccessor) (Object) level).adventure_power$getEntityTickList();
                if (!tickList.contains(player)) {
                    tickList.add(player);
                }

                // 第 5 步：追踪关系重建（TrackedEntity + 向周边玩家广播 spawn——客户端失联
                // 的解药；重注册全链通常已含 startTracking，本步是 per-项兜底；
                // alreadyTracked 幂等 catch）。onTrackingStart 内部的 players.add 只补
                // level.players（字节码核实），不补 PlayerList——由第 7 步负责
                Object entityMap = ((com.ayin90723.adventure_power.mixin.ChunkMapAccessor) (Object)
                    ((com.ayin90723.adventure_power.mixin.ServerChunkCacheAccessor) (Object)
                        level.getChunkSource()).adventure_power$getChunkMap()).adventure_power$getEntityMap();
                if (!(entityMap instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> map
                    && map.containsKey(player.getId()))) {
                    try {
                        ((net.minecraft.world.level.entity.LevelCallback) (Object)
                            ((PesmFieldAccessor) (Object) rawPesm).adventure_power$getCallbacks())
                            .onTrackingStart(player);
                    } catch (IllegalStateException alreadyTracked) {
                        // 幂等：已 tracked 跳过
                    }
                }

                // 第 6 步：players 表直补（公共方法直返字段本体，直改生效）
                if (!level.players().contains(player)) {
                    level.players().add(player);
                }

                // 第 7 步：PlayerList 名册直补（vp 连招腿②）——playersByUUID 同步重塞保
                // 双表一致；已有条目跳过。反射不可用回退公共 getPlayers()（同为直返字段本体）
                PlayerList playerList = level.getServer().getPlayerList();
                List<ServerPlayer> roster = PlayerListFields.players(playerList);
                if (roster == null) roster = playerList.getPlayers();
                if (!roster.contains(player)) {
                    roster.add(player);
                }
                Map<UUID, ServerPlayer> byUuid = PlayerListFields.playersByUUID(playerList);
                if (byUuid != null && !byUuid.containsKey(uuid)) {
                    byUuid.put(uuid, player);
                }
            }
            // 一级缺失（仅 A1/A2 异常）不走上面分支，直接落尾步轻修复
            //（A2 removalReason 归位：第 0 步 player.revive() 的 unsetRemoved 已顺带置 null，
            //  tick 自检①作为该字段被再次直写时的后续防线——复查修 P3-4 注释对齐）

            // ========== 第 1 步（公共尾步）：Callback 手工重建——缺失或换装 ==========
            // 覆盖四类：①一级缺失（Callback 被拧、容器条目全在）②轻量抹除（A6 假进二级
            // 但 A3~A5 健康未触发重注册——互斥改造曾漏掉此形态）③重注册失败 return false
            // 之外的部分失败残余 ④换装型（非 NULL 但归属校验不符——判据与 A1 同源）。
            // 安全性：重注册成功时装了新 Callback，本步短路跳过；执行时 section.add 有
            // 身份防重（noneMatch 才 add）
            if (callbackMissingOrForeign(player)) {
                long key = SectionPos.of(player.blockPosition()).asLong();
                EntitySection section = ((PesmFieldAccessor) (Object) rawPesm)
                    .adventure_power$getSectionStorage().getOrCreateSection(key);
                if (section.getEntities().noneMatch(e -> e == player)) {
                    section.add(player);
                }
                // 反射构造器工厂（@Invoker 构造器对包私有目标类无法声明返回类型——AP 拒绝，
                // 详见 PesmCallbackFactory 类注释）
                EntityInLevelCallback cb = PesmCallbackFactory.create(rawPesm, player, key, section);
                if (cb == null) {
                    LOGGER.warn("[容器重建] {} Callback 构造失败（反射初始化异常），尾步未完成",
                        player.getGameProfile().getName());
                    return false;
                }
                ((EntityLevelCallbackAccessor) (Object) player).adventure_power$setLevelCallback(cb);
            }

            // 尾部复查：最终以容器事实为准
            return ContainerAuditor.audit(player).allHealthy();
        } catch (Throwable t) {
            LOGGER.warn("[容器重建] {} 重建异常（零退化，下轮重试）", player.getGameProfile().getName(), t);
            return false;
        }
    }

    /** Callback 缺失或换装判定（判据与审计 A1 同源）。 */
    private static boolean callbackMissingOrForeign(ServerPlayer player) {
        try {
            EntityInLevelCallback cb =
                ((EntityLevelCallbackAccessor) (Object) player).adventure_power$getLevelCallback();
            return cb == null || cb == EntityInLevelCallback.NULL
                || !ContainerAuditor.callbackBelongsTo(cb, player);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 第 0.5 步旧 section 身份清理（按 == 移除当前 chunk 各 section 的本实体残留）。
     * <p>
     * P3-6 已知边界（明示）：只扫<b>当前 chunk</b>——"对手先移位（把实体挪到别的 chunk）
     * 再抹除"组合下，旧残留躺在<b>其他 chunk</b> 的 section 里扫不到，且审计 A5 只查
     * 当前坐标 section、跨 chunk 残留实际永不暴露（非"下轮 A5 暴露"——原注释不准）。
     * 影响面：旧 chunk 的 AABB 查询会命中残留条目（身份相同去重于消费端，重注册后新旧
     * section 各一份），轻微且需对手特意组合（移位+抹除），跨 chunk 全扫描成本不值。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final class EntitySectionStorageCleaner {
        private static void cleanCurrentChunkSections(PersistentEntitySectionManager pesm, ServerPlayer player) {
            try {
                net.minecraft.world.level.entity.EntitySectionStorage storage =
                    ((PesmFieldAccessor) (Object) pesm).adventure_power$getSectionStorage();
                long chunkKey = player.chunkPosition().toLong();
                for (Object s : storage.getExistingSectionsInChunk(chunkKey).toList()) {
                    if (s instanceof EntitySection es && es.getEntities().anyMatch(e -> e == player)) {
                        es.remove(player);
                    }
                }
            } catch (Exception ignored) {
                // 清理失败（区块边界等）不阻断重建——重注册的 section.add 有身份防重，
                // 双份风险由下次审计 A5 的身份比较暴露
            }
        }
    }
}
