package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.mixin.EntityFieldsAccessor;
import com.ayin90723.adventure_power.mixin.LivingEntityFieldsAccessor;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.ClassPointerGuard;
import com.ayin90723.adventure_power.util.ContainerRebuilder;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 真实血量 - 事件层兜底（两道）。
 * <p>
 * <h3>第一道：die() 免死兜底（{@link LivingDeathEvent}，HIGH 优先级）</h3>
 * 当 true_health 启用且 Capability 备份血量 > 0 时取消死亡事件。
 * <ul>
 *   <li>低于 {@link DeathDefyHandler}(HIGHEST)：让 death_defy 优先处理（冷却好时回满血+进冷却+
 *       无敌期+清负面效果），death_defy cancel 后本层不触发（receiveCanceled=false）。</li>
 *   <li>高于 {@link PlayerStateHandler} 的灵魂绑定 onPlayerDeath(LOW，觉醒时清零经验)：
 *       death_defy 冷却中时本层 backup>0 cancel，避免灵魂绑定误清零经验。</li>
 * </ul>
 * <p>
 * <h3>第二道：血量对账 + 存活性自检（ServerTick END，v1.4.9.1 新增）</h3>
 * 把 {@link com.ayin90723.adventure_power.mixin.TrueHealthMixin} 的读取层对账与 tick 自检
 * 语义在不依赖 Mixin 存活的前提下复制一份：Mixin 层被环境级手段整体废掉（字节码方法头
 * 空转 patch）时，本层是唯一还活着的血量防线。Mixin 健在时本层幂等空转（对账已一致，零修复）。
 * <ul>
 *   <li><b>对账判据照搬 Mixin 读取层</b>：数据层升血（回血/内部直写）→ 备份上移；
 *       降血三分支——maxHealth 驱动的 clamp 归位（rawHealth==maxHealth 且 maxHealth&gt;0）、
 *       合法 hurt 管线伤害落地（{@link #onLivingHurtRecord} 标记，窗口 2 tick）、其余视为
 *       非法降血直写洗回备份；rawHealth ≤ 0 / NaN / ±Infinity → 一律修复到备份（假死/NAN
 *       自愈），<b>零血分支优先于放行分支</b>——合法伤害打空血走"免死修复"而非"承伤同步"，
 *       备份永不被 0 污染（这是 die 兜底 backup&gt;0 门禁的前提）。</li>
 *   <li><b>合法伤害标记</b>：{@code LivingHurtEvent}（默认不收 canceled——被免疫/闪避的
 *       伤害不标记）记录 player UUID → gameTime。事件 post 点在 hurt() 方法体内，Mixin 层
 *       全部失效时事件总线照常工作，标记通道不受空转 patch 影响。</li>
 *   <li><b>存活性自检照搬 Mixin tick 自检</b>：①removalReason 字段直读非 null → 清除+修复
 *       ②NaN/Inf/零血修复 ④dead/deathTime 归位 + 垂死姿势归位 ⑥类指针守卫换回。
 *       Mixin 的 ③（isDeadOrDying 被 ASM 覆写否决）在事件层无对应物（调用仍会进覆写体），
 *       其修复目标（血量/移除标记）已由 ①②覆盖。</li>
 * </ul>
 * <p>
 * <b>已知边界</b>：非法直写压血到 &gt;0 的中间值且同 tick（±2 窗口内）恰有合法 hurt 标记时，
 * 污染值会被当作承伤放行一次（备份降级到污染值，无致死路径——raw≤0 永不放行）；
 * maxHealth 层自检（baseValue 清零 / 恶意 modifier）暂缓，maxHealth=0 语境下 clamp 归位
 * 分支被 maxHealth&gt;0 守卫挡住，走修复路径不受 clamp 影响（repairHealth 直写绕过 setHealth）。
 * <p>
 * 属性访问统一走 Accessor 接口（{@link LivingEntityFieldsAccessor} / {@link EntityFieldsAccessor}），
 * 与 Mixin 自检一致；修复动作全部复用 {@link HealthUtil} 聚合入口。
 *
 * @see com.ayin90723.adventure_power.mixin.TrueHealthMixin
 * @see DeathDefyHandler
 * @see com.ayin90723.adventure_power.handler.ContainerAuditHandler 容器级审计（20 tick 周期，与本层 tick 级对账分层）
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class TrueHealthHandler {

    private static final float EPSILON = 0.001F;

    /** 合法 hurt 管线伤害标记（player UUID -> 最近一次合法 hurt 的 gameTime）。 */
    private static final Map<UUID, Long> LAST_LEGAL_HURT_TICK =
        Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean debugLog() {
        return ModConfig.DEBUG_LOG.get() && ModConfig.DEBUG_LOG_TRUE_HEALTH.get();
    }

    // ===== 第一道：die() 免死兜底 =====

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!AbilityGate.isActive(progress, AbilityIds.TRUE_HEALTH)) return;
            // 备份血量 <= 0 表示玩家确实该死（合法 hurt 路径已将备份归零），不干预
            if (progress.getBackupHealth() <= 0.0F) return;

            // v1.4.0 审查修复：cancel 前必须直写回血——cancel 只阻止 die() 方法体，
            // 此时血量已被 actuallyHurt 归零，isDeadOrDying() 为 true，若无回血，
            // 后续每 tick 走 tickDeath() 20 tick 后 remove(KILLED)。本层触发的前提是
            // die() 的 Mixin 注入被绕过，同一 Mixin 的 tick 自检/remove 拦截大概率同样失效，
            // 不回血 = 玩家断线式消失而非免死。与 DeathDefyHandler cancel 后回血同模式。
            HealthUtil.setHealthDirect(player, progress.getBackupHealth());
            player.deathTime = 0;
            event.setCanceled(true);
        });
    }

    // ===== 第二道：血量对账 + 存活性自检 =====

    /**
     * 合法 hurt 管线伤害标记：事件 post 点在 hurt() 方法体内（Mixin 空转不影响事件总线），
     * 未取消的承伤才标记（免疫/闪避 cancel 的伤害不落地，无需放行窗口）。
     */
    @SubscribeEvent
    public static void onLivingHurtRecord(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        LAST_LEGAL_HURT_TICK.put(player.getUUID(), player.level().getGameTime());
    }

    /**
     * 每 tick 对账（ServerTick END：所有实体 tick 完成后，本 tick 的 hurt/setHealth/字段直写
     * 均已落地，判定窗口最干净——玩家自身 PlayerTickEvent 会早于后 tick 实体的攻击，不采用）。
     * <p>
     * 审查修 P1-1：PlayerList.players 是普通 ArrayList，vanilla 登出路径把 remove 调度到
     * netty eventLoop，与本主线程遍历并发会抛 CME 且发生在迭代器机制内不被 per-player
     * catch 捕获 → 直达事件总线崩服（ContainerAuditHandler.enumeratePlayers 同款结论）——
     * 遍历前拷贝快照。本层每 tick 跑（审计 20 tick），暴露频率更高，防护不可省。
     */
    @SubscribeEvent
    public static void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        // 快照遍历：捕获瞬间名册，登出并发只影响快照新鲜度不崩服
        java.util.List<ServerPlayer> players = new java.util.ArrayList<>(server.getPlayerList().getPlayers());
        for (ServerPlayer player : players) {
            try {
                guardPlayer(player);
            } catch (Throwable t) {
                // 单玩家异常隔离（容器审计同模式）：对账意外不得中断主 tick，也不刷屏
                if (debugLog()) {
                    DebugLog.trueHealth("[TrueHealth-Guard] 对账异常 " +
                        player.getGameProfile().getName() + ": " + t);
                }
            }
        }
    }

    /** 单玩家对账 + 自检（门禁 → 备份重建 → ①移除复活 → ②数据层合法性 → ③对账 → ④~⑥字段/姿势/类指针）。 */
    private static void guardPlayer(ServerPlayer player) {
        // 审查修 P2-1：两段式读取（读空→reviveCaps→重读，catch Throwable）——vp 连招
        // invalidateCaps 攻击窗口内单段读取会整链失明，与本层"Mixin 全废时的唯一血量
        // 防线"定位矛盾；复用容器审计同款入口，零新增依赖
        IAdventureProgress progress = ContainerRebuilder.twoStageProgress(player);
        if (progress == null) return;
        if (!AbilityGate.isActive(progress, AbilityIds.TRUE_HEALTH)) return;
        // 换维度窗口跳过（ContainerAuditHandler 同门禁）：新实例 DataItem 与备份的恢复时序未对齐
        if (player.isChangingDimension()) return;
        // 审查修 P1-2：gameTime 与标记侧同源（player.level()）——1.20.1 原版全维度共享，
        // 但给非主世界维度挂独立 LevelData 的整合包环境下 overworld 与当前维度恒差 N，
        // 跨源比较会让放行窗口恒 miss（承伤同步失效变相无敌）
        long gameTime = player.level().getGameTime();

        float backup = progress.getBackupHealth();
        float raw = HealthUtil.getHealthDirect(player);

        // 备份污染重建（照 Mixin tick 自检：先重建再检测，否则移除复活分支失效；
        // DataItem 同为非法时放弃，交给读取层兜底）
        if (HealthUtil.isSpecialFloat(backup)) {
            if (HealthUtil.isSpecialFloat(raw)) return;
            progress.setBackupHealth(raw);
            backup = raw;
        }

        // 备份无效：真死 / 未初始化（照 Mixin 读取层初始化分支）
        if (backup <= 0.0F) {
            if (raw > 0.0F && Float.isFinite(raw)) {
                progress.setBackupHealth(raw);
            }
            return;
        }

        boolean repaired = false;

        // ① 已移除复活：removalReason 字段直读（EntityLivenessMixin 空转后 isRemoved()
        //    读侧不再强制 false，直读判据语义不变）。
        //    v1.4.9.5 审查修：UNLOADED_WITH_PLAYER/CHANGED_DIMENSION 是登出/换维度的
        //    合法移除（与 ContainerAuditHandler.gateReason 短路集同口径）——登出玩家的
        //    快照可能仍在 ServerTick END 名册里，对其"复活+修血"是对已从所有容器丢弃
        //    对象的无意义操作且污染 debug 日志
        var removalReason = ((EntityFieldsAccessor) (Object) player).adventure_power$getRemovalReason();
        if (removalReason != null && removalReason != Entity.RemovalReason.UNLOADED_WITH_PLAYER
                && removalReason != Entity.RemovalReason.CHANGED_DIMENSION) {
            if (debugLog()) {
                DebugLog.trueHealth("[TrueHealth-Guard] 实体被标记移除！" +
                    " reason=" + removalReason +
                    " backup=" + backup + " -> 清除 + 血量恢复");
            }
            HealthUtil.clearRemovedFlag(player);
            HealthUtil.repairHealth(player, backup);
            repaired = true;
        }

        // ② 数据层合法性：NaN/Inf（污染自愈）与零/负血（假死修复）一律回到备份。
        //    零血分支优先于③的放行分支——合法伤害打空血走"免死修复"，备份永不被 0 污染。
        if (HealthUtil.isSpecialFloat(raw)) {
            if (debugLog()) {
                DebugLog.trueHealth("[TrueHealth-Guard] 数据层血量污染！" +
                    " raw=" + raw + " -> 修复为备份 " + backup);
            }
            HealthUtil.repairHealth(player, backup);
            raw = backup;
            repaired = true;
        } else if (raw <= 0.0F) {
            if (debugLog()) {
                DebugLog.trueHealth("[TrueHealth-Guard] 假死修复！" +
                    " raw=" + raw + " backup=" + backup + " -> 修复");
            }
            HealthUtil.repairHealth(player, backup);
            raw = backup;
            repaired = true;
        }

        // ③ 血量对账（照 Mixin 读取层 diff 分支；觉醒容差 ×2）
        if (!repaired) {
            float epsilon = progress.isFullyUnlocked() ? EPSILON * 2.0F : EPSILON;
            float diff = raw - backup;
            if (diff > epsilon) {
                // 数据层 > 备份：合法回血（休养生息/嗜血/药水/内部修复直写）→ 备份上移
                progress.setBackupHealth(raw);
            } else if (-diff > epsilon) {
                if (HealthUtil.isMaxHealthClampSettle(player, raw)) {
                    // maxHealth 属性驱动的 clamp 降值（生命上限下移）不是篡改，
                    // 接受为合法归位（审查修 P3#4 同款判据）。
                    // 复查修（判据收束）：改调唯一判定源 HealthUtil.isMaxHealthClampSettle
                    // （`newHealth == maxHealth && maxHealth > 0`），与 Mixin 读取层、三层降血闸门同口径。
                    // <b>本点收束为等价替换、无行为差异</b>：此处位于 isSpecialFloat/raw<=0 两个已处理分支
                    // 之后，到达即恒有 raw>0，故新增的 `>0` 守卫在此恒真冗余；原裸表达式 `raw == maxHealth
                    // && maxHealth > 0` 与新谓词逐字等价。
                    // <b>未被关闭的残余面（明示，勿按"已修"理解）</b>：攻击者经 modifier 通道把 maxHealth
                    // 压到"非零但很小"（如 5）后字段直写血量=5，本分支仍会把它当合法归位下调备份——豁免的
                    // 下界只封"归零"（砧板之刃[神] mode 2 的形态），该残余面由 <b>maxHealth 污染本身</b>
                    // 决定（属性层只拦 setBaseValue；modifier 通道按设计开放，因为"减上限诅咒"走 modifier）。
                    // 详见 HealthUtil.isMaxHealthClampSettle 的 javadoc
                    progress.setBackupHealth(raw);
                } else if (recentLegalHurt(player, gameTime)) {
                    // 合法 hurt 管线伤害落地 → 承伤同步（HURT_DEPTH 的事件层替代）
                    progress.setBackupHealth(raw);
                } else {
                    if (debugLog()) {
                        DebugLog.trueHealth("[TrueHealth-Guard] 非法降血检测！" +
                            " raw=" + raw + " backup=" + backup + " diff=" + diff +
                            " -> 修复为备份");
                    }
                    HealthUtil.repairHealth(player, backup);
                }
            }
        }

        // ④ 死亡字段归位（照 Mixin 自检④）：dead 参与 die() 幂等守卫，被直写 true 后
        //    后续 die() 调用被跳过；deathTime 残留让死亡动画持续播放
        LivingEntityFieldsAccessor fields = (LivingEntityFieldsAccessor) (Object) player;
        if (fields.adventure_power$isDead()) {
            if (debugLog()) {
                DebugLog.trueHealth("[TrueHealth-Guard] dead 字段被直写！" +
                    " deathTime=" + fields.adventure_power$getDeathTime() + " -> 归位");
            }
            fields.adventure_power$setDead(false);
            fields.adventure_power$setDeathTime(0);
        } else if (fields.adventure_power$getDeathTime() > 0 && raw > 0.0F) {
            fields.adventure_power$setDeathTime(0);
        }

        // ⑤ 垂死姿势归位（照 Mixin 自检④'）：活体挂 DYING 姿势会同步客户端躺倒
        if (player.getPose() == Pose.DYING) {
            player.setPose(Pose.STANDING);
            if (debugLog()) {
                DebugLog.trueHealth("[TrueHealth-Guard] 垂死姿势残留 -> 归位 STANDING");
            }
        }

        // ⑥ 类指针守卫（照 Mixin 自检⑤）：实体类被替换时换回恢复方法分派
        ClassPointerGuard.record(player);
        if (ClassPointerGuard.isReplaced(player)) {
            if (debugLog()) {
                DebugLog.trueHealth("[TrueHealth-Guard] 检测到实体类被替换！class=" +
                    player.getClass().getName() + " -> 换回 " + ClassPointerGuard.expectedClassName());
            }
            if (ClassPointerGuard.restore(player)) {
                fields.adventure_power$setDead(false);
                fields.adventure_power$setDeathTime(0);
                HealthUtil.clearRemovedFlag(player);
            }
        }
    }

    /** 合法伤害放行窗口：本 tick 或前 2 tick 内存在未取消的 LivingHurtEvent（跨界伤害兜一个 tick 余量）。 */
    private static boolean recentLegalHurt(ServerPlayer player, long gameTime) {
        Long last = LAST_LEGAL_HURT_TICK.get(player.getUUID());
        return last != null && gameTime - last <= 2L;
    }
}
