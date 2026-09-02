package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.mixin.ChunkMapAccessor;
import com.ayin90723.adventure_power.mixin.EntityFieldsAccessor;
import com.ayin90723.adventure_power.mixin.EntityLevelCallbackAccessor;
import com.ayin90723.adventure_power.mixin.EntitySectionStorageAccessor;
import com.ayin90723.adventure_power.mixin.PesmFieldAccessor;
import com.ayin90723.adventure_power.mixin.ServerChunkCacheAccessor;
import com.ayin90723.adventure_power.mixin.ServerLevelContainerAccessor;
import com.ayin90723.adventure_power.mixin.TrackedEntityAccessor;
import com.mojang.logging.LogUtils;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * 容器审计器（v1.4.9 第一部分，纯读）——对单个冒险者玩家逐项评估世界容器接纳状态。
 * <p>
 * 实体级防御（真血/拒篡/死亡抗拒）的前提是"实体仍被世界容器接纳"：第三方以字段级
 * 手段直接抹除容器条目（tick 表 / knownUuids / EntityLookup 双表 / EntitySection /
 * levelCallback / ChunkMap 追踪 / PlayerList 名册）后，实体"在线但冻结"——网络层照常
 * （连接独立于 tick 表），唯独 ServerLevel.tick 的 entityTickList.forEach 迭代不到，
 * 真血双通道修复全部失去入口。
 * <p>
 * 审计项（全健康才绿；单项异常按不健康处理，由退避机制防风暴）：
 * <ul>
 *   <li><b>A1 levelCallback</b>：非 {@code EntityInLevelCallback.NULL} 且归属校验通过
 *       （反射读 Callback.entity == entity——换装型自定义 callback 检测，日志必含
 *       原 callback 实际类全名——低概率误伤面的定位唯一线索）</li>
 *   <li><b>A2 removalReason</b>：null 健康；非 null = 攻击签名证据（记日志，归位由
 *       TrueHealthMixin tick 自检①负责，审计不修）</li>
 *   <li><b>A3 knownUuids</b>：PESM UUID 注册表含本实体</li>
 *   <li><b>A4 byUuid/byId 双查</b>：visibleEntityStorage 是 EntityLookup 类（byId/byUuid
 *       双表）——getEntity(UUID) 只走 byUuid，仅抹 byId 时单查判据恒绿（七轮评审 P1）；
 *       身份比较防 UUID 撞车双实体</li>
 *   <li><b>A5 section</b>：当前坐标 EntitySection 条目存在（条目存在性，不查可见性——
 *       可见性污染为已知边界）</li>
 *   <li><b>A6 entityTickList</b>：tick 资格表</li>
 *   <li><b>A7 追踪</b>：ChunkMap.entityMap 条目存在即健康（玩家不追踪自己、seenBy
 *       单人局恒空——二轮评审：恒假判据会造成无限重建循环；seenBy 降级为诊断字段）</li>
 *   <li><b>A8 players</b>：level.players()（公共方法直返字段本体）</li>
 *   <li><b>A9 PlayerList 名册</b>：getPlayers() + playersByUUID 双表（vp 连招腿②直删
 *       名册后服务器视角玩家不存在：saveAll/广播//list 全部失明）</li>
 * </ul>
 * <p>
 * 驱动与分级动作见 {@code ContainerAuditHandler}；重建见 {@code ContainerRebuilder}。
 * 本类纯读零改写；异常防御由调用方负责（审计/重建链任何意外不得进主 tick）。
 */
public final class ContainerAuditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ContainerAuditor() {
    }

    // ==================== 审计位定义 ====================

    public static final int A_CALLBACK = 1 << 0;
    public static final int A_REMOVAL_REASON = 1 << 1;
    public static final int A_KNOWN_UUIDS = 1 << 2;
    public static final int A_LOOKUP = 1 << 3;
    public static final int A_SECTION = 1 << 4;
    public static final int A_TICK_LIST = 1 << 5;
    public static final int A_TRACKING = 1 << 6;
    public static final int A_LEVEL_PLAYERS = 1 << 7;
    public static final int A_PLAYER_LIST = 1 << 8;

    /** 容器条目位（A3~A9）——任一缺失走二级重建分支。 */
    public static final int CONTAINER_MASK =
        A_KNOWN_UUIDS | A_LOOKUP | A_SECTION | A_TICK_LIST | A_TRACKING | A_LEVEL_PLAYERS | A_PLAYER_LIST;
    public static final int ALL_MASK = CONTAINER_MASK | A_CALLBACK | A_REMOVAL_REASON;

    /** PESM$Callback.entity 字段（SRG f_157609_ / dev entity——包私有内部类，SRG+dev 双名反射）。 */
    private static final Field CALLBACK_ENTITY_FIELD;

    static {
        Field f = null;
        try {
            Class<?> clz = Class.forName("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback");
            f = HealthUtil.reflectField(clz, "f_157609_", "entity");
        } catch (ClassNotFoundException e) {
            LOGGER.error("[容器审计] PESM$Callback 类不可达，A1 归属校验降级为仅判非 NULL", e);
        }
        CALLBACK_ENTITY_FIELD = f;
    }

    // ==================== 审计结果 ====================

    /** 审计产出：健康位掩码 + 攻击签名证据 + 诊断字段。 */
    public static final class AuditResult {
        /** 健康位集合（对应位 1 = 健康）。 */
        public final int healthyMask;
        /** A2 证据：removalReason 非 null 时的实际值（null = 无证据）。 */
        public final Entity.RemovalReason removalReasonEvidence;
        /** A1 证据：callback 换装时的原 callback 实际类全名（null = 无证据）。 */
        public final String foreignCallbackClass;
        /** 诊断：追踪条目的 seenBy 观察者数（-1 = 不可用）。 */
        public final int seenByCount;

        AuditResult(int healthyMask, Entity.RemovalReason removalReasonEvidence,
                    String foreignCallbackClass, int seenByCount) {
            this.healthyMask = healthyMask;
            this.removalReasonEvidence = removalReasonEvidence;
            this.foreignCallbackClass = foreignCallbackClass;
            this.seenByCount = seenByCount;
        }

        public boolean allHealthy() {
            return healthyMask == ALL_MASK;
        }

        /** 容器条目（A3~A9）全在——false 即二级缺失（走重注册/直补分支）。 */
        public boolean containerEntriesHealthy() {
            return (healthyMask & CONTAINER_MASK) == CONTAINER_MASK;
        }

        public boolean callbackHealthy() {
            return (healthyMask & A_CALLBACK) != 0;
        }

        /** 缺失项描述（日志用）。 */
        public String describeMissing() {
            StringBuilder sb = new StringBuilder();
            if (!callbackHealthy()) sb.append("levelCallback ");
            if ((healthyMask & A_REMOVAL_REASON) == 0) sb.append("removalReason=").append(removalReasonEvidence).append(' ');
            if ((healthyMask & A_KNOWN_UUIDS) == 0) sb.append("knownUuids ");
            if ((healthyMask & A_LOOKUP) == 0) sb.append("byUuid/byId ");
            if ((healthyMask & A_SECTION) == 0) sb.append("section ");
            if ((healthyMask & A_TICK_LIST) == 0) sb.append("entityTickList ");
            if ((healthyMask & A_TRACKING) == 0) sb.append("tracking ");
            if ((healthyMask & A_LEVEL_PLAYERS) == 0) sb.append("level.players ");
            if ((healthyMask & A_PLAYER_LIST) == 0) sb.append("playerList ");
            return sb.toString().trim();
        }
    }

    // ==================== 主审计入口 ====================

    /**
     * 对单个玩家做全项审计（纯读）。
     *
     * @throws IllegalStateException 目标不在 ServerLevel（调用方跳过）
     */
    public static AuditResult audit(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("player not in ServerLevel");
        }
        int mask = 0;
        Entity.RemovalReason removalEvidence = null;
        String foreignCallback = null;
        int seenBy = -1;

        // A1 levelCallback：非 NULL + 归属校验
        try {
            EntityInLevelCallback cb =
                ((EntityLevelCallbackAccessor) (Object) player).adventure_power$getLevelCallback();
            if (cb != null && cb != EntityInLevelCallback.NULL) {
                if (callbackBelongsTo(cb, player)) {
                    mask |= A_CALLBACK;
                } else {
                    // 换装证据：非 NULL 但归属校验不符（自定义 callback 或读不到 entity 字段）。
                    // 日志必含原 callback 实际类全名——误伤定位唯一线索（七轮后补）
                    foreignCallback = cb.getClass().getName();
                }
            }
        } catch (Exception ignored) {
        }

        // A2 removalReason：非 null 即攻击签名证据（记日志；归位由 tick 自检①负责）
        try {
            Entity.RemovalReason reason =
                ((EntityFieldsAccessor) (Object) player).adventure_power$getRemovalReason();
            if (reason == null) {
                mask |= A_REMOVAL_REASON;
            } else {
                removalEvidence = reason;
            }
        } catch (Exception ignored) {
        }

        PersistentEntitySectionManager<?> pesm =
            ((ServerLevelContainerAccessor) (Object) level).adventure_power$getEntityManager();
        if (pesm != null) {
            UUID uuid = player.getUUID();
            // A3 knownUuids
            try {
                if (((PesmFieldAccessor) (Object) pesm).adventure_power$getKnownUuids().contains(uuid)) {
                    mask |= A_KNOWN_UUIDS;
                }
            } catch (Exception ignored) {
            }
            // A5 section：当前坐标 EntitySection 条目存在（只读——sections 直读，不 getOrCreate）
            try {
                long key = SectionPos.asLong(player.blockPosition());
                Object section = ((EntitySectionStorageAccessor) (Object)
                    ((PesmFieldAccessor) (Object) pesm).adventure_power$getSectionStorage())
                    .adventure_power$getSections().get(key);
                if (section instanceof net.minecraft.world.level.entity.EntitySection<?> es
                    && es.getEntities().anyMatch(e -> e == player)) {
                    mask |= A_SECTION;
                }
            } catch (Exception ignored) {
            }
        }

        // A4 byUuid/byId 双查（EntityLookup 双表——七轮评审 P1：仅抹 byId 时单查恒绿）
        try {
            if (level.getEntity(player.getUUID()) == player
                && level.getEntity(player.getId()) == player) {
                mask |= A_LOOKUP;
            }
        } catch (Exception ignored) {
        }

        // A6 entityTickList
        try {
            if (((ServerLevelContainerAccessor) (Object) level).adventure_power$getEntityTickList().contains(player)) {
                mask |= A_TICK_LIST;
            }
        } catch (Exception ignored) {
        }

        // A7 追踪：entityMap 条目存在即健康；seenBy 仅诊断
        try {
            Object entityMap = ((ChunkMapAccessor) (Object)
                ((ServerChunkCacheAccessor) (Object) (Object) level.getChunkSource())
                .adventure_power$getChunkMap()).adventure_power$getEntityMap();
            if (entityMap instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> map
                && map.containsKey(player.getId())) {
                mask |= A_TRACKING;
                try {
                    Object tracked = map.get(player.getId());
                    if (tracked != null) {
                        seenBy = ((TrackedEntityAccessor) tracked).adventure_power$getSeenBy().size();
                    }
                } catch (Exception ignored) {
                    // seenBy 诊断读取失败（refmap 不生效等）不影响主审计
                }
            }
        } catch (Exception ignored) {
        }

        // A8 players（公共方法直返字段本体）
        try {
            if (level.players().contains(player)) {
                mask |= A_LEVEL_PLAYERS;
            }
        } catch (Exception ignored) {
        }

        // A9 PlayerList 名册双表（getPlayers 公共方法 + playersByUUID 反射；反射不可用只查公共半边）
        try {
            PlayerList playerList = level.getServer().getPlayerList();
            boolean inList = playerList.getPlayers().contains(player);
            var byUuid = PlayerListFields.playersByUUID(playerList);
            boolean inMap = byUuid == null || byUuid.containsKey(player.getUUID());
            if (inList && inMap) {
                mask |= A_PLAYER_LIST;
            }
        } catch (Exception ignored) {
        }

        return new AuditResult(mask, removalEvidence, foreignCallback, seenBy);
    }

    /**
     * A1 归属校验：callback 是本实体的 PESM$Callback。
     * <p>
     * 反射基建失效（CALLBACK_ENTITY_FIELD == null，环境异常）时降级为"通过"——审计基建
     * 失效不误报换装；callback 为换装对象（无 entity 字段或归属不符）时 {@code Field.get}
     * 抛异常 / 值不符 → false（换装证据）。
     */
    /** 归属校验（包级可见：ContainerRebuilder 公共尾步与本审计 A1 共用同源判据）。 */
    static boolean callbackBelongsTo(EntityInLevelCallback cb, Entity entity) {
        if (CALLBACK_ENTITY_FIELD == null) return true;
        try {
            return CALLBACK_ENTITY_FIELD.get(cb) == entity;
        } catch (Exception e) {
            return false;
        }
    }
}
