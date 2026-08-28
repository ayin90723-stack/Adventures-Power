package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.DebugLog.EngineCaller;
import com.ayin90723.adventure_power.util.probe.PendingVerifyRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * 处决收尾工具（v1.4.6 提权自 {@code ShadowKillHelper.finalizeSaturationKill} 善后段②~⑨，
 * docs/execution-finality-proposal.md §3.4）：战利品 + 死亡事件 + 五重移除链 + 客户端包 +
 * 容器抹除 + 兜底确认。影杀与禁疗终局层共用的处决语义终端。
 *
 * <h3>调用方门禁（语义纪律）</h3>
 * 仅<b>处决语义所有者</b>可调：影杀（处决）与禁疗终局层（"不许活"兜底，GateOracle
 * FAILED/开关关路径）。淬魂/审判/破敌的致死收尾走 {@link DeathFinalizer}（补完原版 die），
 * <b>禁止</b>调本类——强移善后 = 处决语义（v1.4.5 收敛方案被否的边界就在这里）。
 *
 * <h3>各段不幂等</h3>
 * ②强制掉装备/③dropAllDeathLoot/④事件 post 双跑即双掉落双事件（七轮双善后 bug 同型；
 * ⑨ 兜底确认自带重写除外）——防双跑靠调用方契约：{@code GateOracle.tryOpen} 返回 FAILED
 * 时善后已由 finalizeFallback 在其内部跑过，调用方直接 return 勿再调。
 */
public final class ExecutionFinalizer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** dropAllDeathLoot 反射缓存（m_6668_ = dropAllDeathLoot(DamageSource)） */
    private static final Method DROP_ALL_DEATH_LOOT =
        HealthUtil.reflectMethod(LivingEntity.class, "m_6668_", "dropAllDeathLoot", DamageSource.class);

    private ExecutionFinalizer() {
    }

    /**
     * 处决善后段（②~⑨）：战利品 + 死亡事件 + 五重移除链 + 客户端包 + 容器抹除 + 兜底确认。
     * <p>
     * 各段独立降级捕获（v1.4.0 分段异常保护随迁）：战利品/事件段失败不阻断移除段；
     * 移除段（⑥~⑨）逐层捕获，保证任一层失败其余层仍执行。
     *
     * @param caller 调用方能力（日志归属，同引擎探针/死亡结算的调用方归属原则）
     */
    public static void finalizeKill(LivingEntity target, DamageSource source, ServerLevel serverLevel,
                                    EngineCaller caller) {
        // 类内 Player guard（v1.4.6 评审入案）：容器抹除链对玩家目标是灾难——调用方门禁是
        // 纪律约束不拦代码，防御纵深进类内（GateOracle.tryOpen 同款，审查修 P3#10 先例）；
        // 与禁疗 PVP 门禁（标记只打非玩家目标）两层不冲突
        if (target instanceof Player) {
            DebugLog.deathFinalize(caller, "[处决善后] 拦截玩家目标（类内 guard），跳过 target={}", target);
            return;
        }

        // ② 强制掉落全套装备 + ③ 反射调用 dropAllDeathLoot（触发战利品表 /
        //     LivingDropsEvent / LootModifier）+ ④ 手动 post LivingDeathEvent（墓碑/任务
        //     模组可正常处理）+ ⑤ 善后清理 —— 战利品/事件段失败不阻断移除
        try {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack equipment = target.getItemBySlot(slot);
                if (!equipment.isEmpty()) {
                    target.spawnAtLocation(equipment.copy());
                    target.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
            if (DROP_ALL_DEATH_LOOT != null) {
                DROP_ALL_DEATH_LOOT.invoke(target, source);
            }
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 战利品段失败（②③），继续移除链 target={}", target, e);
        }
        try {
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(target, source));
            target.unRide();
            target.ejectPassengers();
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 死亡事件/善后段失败（④⑤），继续移除链 target={}", target, e);
        }

        // ⑥ 五重移除链 — 逐层递增，确保无 Boss 可拦截（逐层捕获：任一层失败其余层仍执行）
        try {
            target.remove(Entity.RemovalReason.KILLED);                             // 标准路径
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 移除层1失败 target={}", target, e);
        }
        try {
            target.remove(Entity.RemovalReason.DISCARDED);                          // 双保险
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 移除层2失败 target={}", target, e);
        }
        try {
            HealthUtil.removeDirect(target, Entity.RemovalReason.KILLED);           // 反射 remove() — 绕过 Java 覆写
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 移除层3失败 target={}", target, e);
        }
        try {
            HealthUtil.setRemovedFieldDirect(target, Entity.RemovalReason.KILLED);  // 字段直写 — 绕过一切 Mixin
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 移除层4失败 target={}", target, e);
        }
        // 第5层：CHANGED_DIMENSION 兜底 — 部分 Boss 的 Mixin 仅拦截 KILLED/DISCARDED
        try {
            HealthUtil.setRemovedDirect(target, Entity.RemovalReason.CHANGED_DIMENSION);
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 移除层5失败 target={}", target, e);
        }

        // ⑦ 客户端同步 — 直发维度内所有玩家（v1.4.6 随提权一并修复：原 chunkSource.broadcast
        //     依赖追踪关系，实体 removed 后追踪条目已清、包发不出去——"杀掉但模型还在、
        //     重进才消失"现象在强杀路径的遗留）
        broadcastRemovePacket(target, serverLevel);

        // ⑧ 内部结构抹除 — 从 EntityLookup/EntityTickList/EntitySection 中直接删除实体
        try {
            HealthUtil.eradicateFromWorld(target);
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 容器抹除失败（⑧） target={}", target, e);
        }

        // ⑨ 最终确认 — 若防护 Boss 在移除链中清除了标记（极端场景），兜底重写 removalReason
        try {
            if (!target.isRemoved()) {
                DebugLog.deathFinalize(caller, "[处决善后] 移除标记被清除，兜底重写 target={}", target);
                HealthUtil.setRemovedFieldDirect(target, Entity.RemovalReason.KILLED);
            }
            DebugLog.deathFinalize(caller, "[处决善后] 处决收尾完成 target={} removed={} reason={}",
                target, target.isRemoved(), target.getRemovalReason());
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 兜底确认失败（⑨） target={}", target, e);
        }
    }

    /**
     * 开门成功后的客户端同步兜底（原影杀十二轮，v1.4.6 随迁）：死亡表演型 Boss 覆写 remove
     * 延迟容器移除（deathTime 表演 → 表演结束才真移除），服务端 confirmDead 通过但客户端
     * 模型残留（实测退出重进才消失）。挂统一 pending 表：窗口末已移除 → 补发客户端移除包
     * （幂等，与原版广播重复无害）；仍未移除（表演超时/异常）→ 强制收尾。
     * <p>
     * 十三轮修复（随迁）：onDead 必须覆写——PendingVerifyRegistry 对窗口内已死亡/移除的
     * 目标走 onDead 提前完成分支（不调 onVerify），本末起源 killEntity 同栈立即移除实体，
     * 实体在下个 tick END 就 isRemoved → onDead（原默认空实现）→ 任务静默完成、移除包没发。
     *
     * @param caller 调用方能力（日志归属）
     */
    public static void schedulePostKillSync(LivingEntity target, ServerLevel serverLevel, EngineCaller caller) {
        PendingVerifyRegistry.register(target,
            ModConfig.GATE_ORACLE_WAIT_TICKS.get(), new PendingVerifyRegistry.PendingTask() {
                @Override
                public boolean onVerify(LivingEntity t) {
                    if (t.isRemoved()) {
                        broadcastRemovePacket(t, serverLevel);
                        DebugLog.deathFinalize(caller, "[开门收尾] 实体已移除，补发客户端移除包 target={}", t);
                        return true;
                    }
                    return false; // 未移除 → onFail 强制收尾
                }

                @Override
                public void onDead(LivingEntity t) {
                    // 十三轮：窗口内已移除（killEntity 同栈立即移除的形态）走本分支而非
                    // onVerify——此处即客户端同步的真正主路径，必须补发移除包
                    broadcastRemovePacket(t, serverLevel);
                    DebugLog.deathFinalize(caller, "[开门收尾] 窗口内移除，补发客户端移除包 target={}", t);
                }

                @Override
                public void onFail(LivingEntity t) {
                    DebugLog.deathFinalize(caller, "[开门收尾] 等待窗口结束仍未移除（表演超时？），强制收尾 target={}", t);
                    try {
                        t.remove(Entity.RemovalReason.KILLED);
                        HealthUtil.setRemovedFieldDirect(t, Entity.RemovalReason.KILLED);
                        broadcastRemovePacket(t, serverLevel);
                        HealthUtil.eradicateFromWorld(t);
                    } catch (Exception e) {
                        LOGGER.error("[ExecutionFinalizer] 开门后强制收尾失败 target={}", t, e);
                    }
                }
            });
    }

    /** 广播客户端移除包（幂等——与原版移除链的广播重复无害）。 */
    private static void broadcastRemovePacket(LivingEntity target, ServerLevel serverLevel) {
        try {
            ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(target.getId());
            // 十四轮：不用 chunkSource.broadcast(target,...)——它依赖实体追踪关系，实体已
            // removed（DISCARDED/KILLED）后追踪条目可能已被清理，无观察者包发不出去（十三轮
            // 实测：补发日志正常输出但客户端模型仍残留）。直接对维度内所有玩家逐个发送
            for (ServerPlayer sp : serverLevel.players()) {
                sp.connection.send(packet);
            }
        } catch (Exception e) {
            LOGGER.error("[ExecutionFinalizer] 客户端移除包发送失败 target={}", target, e);
        }
    }
}
