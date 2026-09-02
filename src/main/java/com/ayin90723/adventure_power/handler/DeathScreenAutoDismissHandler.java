package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.mixin.LivingEntityFieldsAccessor;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * 客户端玩家实体自愈（v1.4.9-fix，vp 连招实测回填）——"原版不可能出现的矛盾态"自动收敛。
 * <p>
 * <b>死亡画面不拦截（2026-09-02 用户拍板退役画面拦截链）</b>：此前版本对假死画面做过
 * 源头拦截（ScreenEvent.Opening）+ 演出归位 + 拉锯逃生，实机反复暴露嵌套竞态，用户定案：
 * 画面照常出现，玩家点「重生」——<b>服务端重生门禁</b>（{@code PlayerListRespawnGateMixin}，
 * backup&gt;0 拒绝未死之身，含 wonGame 分支 hp 直读判据与读空保守拒绝）保证点重生后原地
 * 返回；客户端画面关闭由 {@code ClientRespawnMixin}（m_7583_ RETURN 收尾：清死态+关画面）
 * 负责——原版「重生」按钮回调只发包不关画面（画面关闭依赖服务端 RespawnPacket，门禁拒发
 * 后画面永开，需绕回「回到标题」确认框才能关）。本类不再做源头拦截与演出归位，仅保留
 * <b>重生收尾窗口驱动</b>（{@code fakeRespawnFinisherUntil}，ClientRespawnMixin 点重生后
 * 置 600ms 窗口：vp 收手前尚有在途演出包会重开画面/重写死态，窗口内每 tick 清死态+关画面
 * 兜住残余，结束自动过期）。边界披露：窗口期内若玩家真实再次死亡，新开的合法死屏也会被
 * 短暂强关、死态被清，直至 RespawnPacket 到达纠正（极端且自愈，窗口不含"仅清已受理画面"
 * 的区分判据）。
 * <p>
 * <b>本轮保留的职责——客户端自身实体被移除自愈</b>：{@code mc.player} 引用有效且属于
 * 当前世界，但实体已被移除（{@code isRemoved()}——客户端实移除包所致）。实测症状链
 * （vp 连招）：客户端玩家实体被移除 → ①Forge 在实体移除时自动 {@code invalidateCaps()}，
 * 一切取玩家 capability 的模组（solcarrot 等）抛异常/静默失效（尸体物品界面渲染即崩）；
 * ②实体不在客户端玩家列表 → 客户端本地移动失效（"不能移动"，但指令 tp 走位置包不依赖
 * 实体列表故仍可"移动"——服务端实体与连接全程正常）。自愈动作：{@code revive()}
 * （Forge patch = unsetRemoved + reviveCaps，移除标记与 capability 一并恢复）+ 把实体
 * 塞回 {@code level.players()}（公共方法直返列表本体，可直接 add）+ 客户端 tick 表归位
 * （f_171630_——tick 资格丢失 → 输入恒 0 卡死，第五轮诊断定案）+ EntityLookup/section/
 * levelCallback 全链补充（P2-1：onRemove 级联的三处缺口——注意 TESM.addEntity 一步只补
 * EntityLookup 双表/section/levelCallback，tick 表与 players 由上方调用点单独补齐）。
 * 防御判据「实体属于当前 level」天然排除维度切换窗口（旧实体挂在旧 level 上，
 * {@code p.level() == mc.level} 不成立，跳过——误 revive 即将被丢弃的旧实体虽无实害，
 * 也不做）；同一实体只自愈一次（去重日志）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class DeathScreenAutoDismissHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 自愈日志限频：vp 持续重复移除时自愈每 tick 触发（实测 40+ 条/1.5 秒）——1 秒窗口只记一次，
     *  状态信息（修复前 tick 表）不丢，可观测性保留 */
    private static long selfHealLogAt = 0L;

    /** 重生收尾窗口截止（wall ms，ClientRespawnMixin 写、本类 tick 驱动）：玩家点重生后 vp 尚有
     *  1~2 个在途演出包（服务端收手前已发出），即时收尾会被它们重开画面（实测"要点两次"：
     *  第一次点后画面回正又回弹，第二次才稳定）；窗口内每 tick 清死态+关画面，覆盖残余包后
     *  自动过期。放这里而非 mixin 类——@Mixin 类禁止非 private static 字段 */
    static long fakeRespawnFinisherUntil = 0L;

    // 供 ClientRespawnMixin 单向写入（跨包需 public；mixin 验证器禁非 private static 字段故放此处）
    public static void setFakeRespawnFinisherUntil(long until) {
        fakeRespawnFinisherUntil = until;
    }

    /** 冻结诊断窗口：自愈触发后的 10 秒内每 20 tick 打一次排查快照（用户可据此报告"动不了"的失效层） */
    private static long revivedAt = -1L;
    private static long diagTick = 0L;

    /** 服务端传送确认字段（f_9766_，诊断专用反射；null=不可用跳过）。
     *  注：该字段类型是 Vec3（awaitingPositionFromClient）而非 boolean——String.valueOf
     *  输出坐标值或 null（null=未在等待）；判读按"null=未等待"语义（P3-4 修正预期） */
    private static java.lang.reflect.Field AWAITING_TP_FIELD;

    /** 客户端 tick 表字段（f_171630_，客户端 tick 资格归位与诊断专用） */
    private static java.lang.reflect.Field TICKING_ENTITIES_FIELD;

    /** 客户端实体存储字段（f_171631_，ClientLevel.entityStorage = TransientEntitySectionManager） */
    private static java.lang.reflect.Field ENTITY_STORAGE_FIELD;
    /** TESM 的 EntityLookup 字段（f_157637_，预清防 addEntity 内 EntityLookup.add 的 byUuid 同 UUID 早退） */
    private static java.lang.reflect.Field TESM_ENTITY_STORAGE_FIELD;
    /** EntityLookup.remove（public，编译期可引用；反射统一走双名惯例） */
    private static java.lang.reflect.Method LOOKUP_REMOVE_METHOD;
    /** TESM.addEntity（public，一步补齐 EntityLookup+section+levelCallback；tick 表与
     *  players 列表由调用点单独补齐——审查修 P3：字段注释与实现一致化） */
    private static java.lang.reflect.Method TESM_ADD_METHOD;

    static {
        try {
            AWAITING_TP_FIELD = net.minecraft.server.network.ServerGamePacketListenerImpl.class
                .getDeclaredField("f_9766_");
            AWAITING_TP_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            try {
                AWAITING_TP_FIELD = net.minecraft.server.network.ServerGamePacketListenerImpl.class
                    .getDeclaredField("awaitingPositionFromClient");
                AWAITING_TP_FIELD.setAccessible(true);
            } catch (NoSuchFieldException ignored) {
                AWAITING_TP_FIELD = null;
            }
        }
        try {
            TICKING_ENTITIES_FIELD = net.minecraft.client.multiplayer.ClientLevel.class
                .getDeclaredField("f_171630_");
        } catch (NoSuchFieldException e) {
            try {
                TICKING_ENTITIES_FIELD = net.minecraft.client.multiplayer.ClientLevel.class
                    .getDeclaredField("tickingEntities");
            } catch (NoSuchFieldException ignored) {
                TICKING_ENTITIES_FIELD = null;
            }
        }
        try {
            ENTITY_STORAGE_FIELD = net.minecraft.client.multiplayer.ClientLevel.class
                .getDeclaredField("f_171631_");
        } catch (NoSuchFieldException e) {
            try {
                ENTITY_STORAGE_FIELD = net.minecraft.client.multiplayer.ClientLevel.class
                    .getDeclaredField("entityStorage");
            } catch (NoSuchFieldException ignored) {
                ENTITY_STORAGE_FIELD = null;
            }
        }
        try {
            TESM_ENTITY_STORAGE_FIELD = net.minecraft.world.level.entity.TransientEntitySectionManager.class
                .getDeclaredField("f_157637_");
        } catch (NoSuchFieldException e) {
            try {
                TESM_ENTITY_STORAGE_FIELD = net.minecraft.world.level.entity.TransientEntitySectionManager.class
                    .getDeclaredField("entityStorage");
            } catch (NoSuchFieldException ignored) {
                TESM_ENTITY_STORAGE_FIELD = null;
            }
        }
        // 方法名双名惯例（审查修 P2-01）：生产 SRG（m_156822_/m_157653_）先查、dev 名回退
        // （此前只有 dev 名，生产环境两个 getMethod 全部 NoSuchMethodException
        // → 存储链恢复静默失效）；分块捕获仅吞预期异常（Java 17 无 SecurityManager，
        // 非检查异常现实中不可达，无需外层 Throwable 全兜）
        try {
            LOOKUP_REMOVE_METHOD = net.minecraft.world.level.entity.EntityLookup.class
                .getMethod("m_156822_", net.minecraft.world.level.entity.EntityAccess.class);
        } catch (NoSuchMethodException e) {
            try {
                LOOKUP_REMOVE_METHOD = net.minecraft.world.level.entity.EntityLookup.class
                    .getMethod("remove", net.minecraft.world.level.entity.EntityAccess.class);
            } catch (NoSuchMethodException ignored) {
                LOOKUP_REMOVE_METHOD = null;
            }
        }
        try {
            TESM_ADD_METHOD = net.minecraft.world.level.entity.TransientEntitySectionManager.class
                .getMethod("m_157653_", net.minecraft.world.level.entity.EntityAccess.class);
        } catch (NoSuchMethodException e) {
            try {
                TESM_ADD_METHOD = net.minecraft.world.level.entity.TransientEntitySectionManager.class
                    .getMethod("addEntity", net.minecraft.world.level.entity.EntityAccess.class);
            } catch (NoSuchMethodException ignored) {
                TESM_ADD_METHOD = null;
            }
        }
        if (TICKING_ENTITIES_FIELD != null) {
            TICKING_ENTITIES_FIELD.setAccessible(true);
        }
        if (ENTITY_STORAGE_FIELD != null) {
            ENTITY_STORAGE_FIELD.setAccessible(true);
        }
        if (TESM_ENTITY_STORAGE_FIELD != null) {
            TESM_ENTITY_STORAGE_FIELD.setAccessible(true);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // ① 重生收尾窗口驱动（fakeRespawnFinisherUntil，ClientRespawnMixin 写）：玩家点重生后
        //    600ms 内 vp 尚有在途演出包（收手前发出）会重开画面/重写死态——每 tick 清死态+关
        //    画面兜住残余，窗口结束自动过期。判据为窗口标记，非死亡语境零副作用
        if (fakeRespawnFinisherUntil > 0L) {
            if (System.currentTimeMillis() < fakeRespawnFinisherUntil) {
                if (player != null) {
                    var acc = (LivingEntityFieldsAccessor) player;
                    if (acc.adventure_power$isDead()) {
                        acc.adventure_power$setDead(false);
                    }
                    if (acc.adventure_power$getDeathTime() > 0) {
                        acc.adventure_power$setDeathTime(0);
                    }
                    if (player.getPose() == Pose.DYING) {
                        player.setPose(Pose.STANDING);
                    }
                }
                if (mc.screen instanceof DeathScreen) {
                    mc.setScreen(null);
                }
            } else {
                fakeRespawnFinisherUntil = 0L;
            }
        }

        // ② 客户端自身实体被移除自愈：mc.player 有效、属于当前世界、却被移除
        if (player != null && player.level() == mc.level && player.isRemoved()) {
            // 诊断前采样（P3-2）：修复前的 tick 表状态（修复后恒显示"已恢复"看不清病根）
            boolean tickingBefore = isInTickList(player);
            player.revive();  // unsetRemoved + reviveCaps（capability 一并恢复——solcarrot 崩溃根因）
            if (mc.level != null && !mc.level.players().contains(player)) {
                mc.level.players().add(player);
            }
            // tick 资格归位（第五轮诊断定案：客户端 despawn 的 onRemove→stopTicking 移除
            // tick 表 → aiStep/input.tick 不执行 → input 恒 0 WASD 无效）
            addToTickListIfMissing(player);
            // P2-1 修复：客户端实体存储全链补充——onRemove 级联还留下三处缺口：
            // entityStorage（EntityLookup byId/byUuid 双表）、section 成员、levelCallback
            //（NULL no-op 实例——无 NPE 但 ClientLevel.getEntity(id) 恒 null、跨 section
            // 移动管理静默失效）。EntityLookup 预清 + addEntity 一步补齐全链
            restoreClientEntityStorage(player);
            // 自愈日志 1 秒限频（vp 持续重复移除时自愈每 tick 触发，40+ 条/1.5 秒——限频
            // 后状态信息不丢、噪音可控；消息带上修复前 tick 表状态供排障）
            long now = System.currentTimeMillis();
            if (now - selfHealLogAt >= 1000L) {
                selfHealLogAt = now;
                LOGGER.warn("[客户端自愈] 本地玩家实体曾被移除（外部移除包？），已恢复移除标记/capability/"
                    + "玩家列表/tick 表/EntityLookup+section+Callback 全链（修复前 tick 表={}）",
                    tickingBefore ? "在" : "缺");
            }
            revivedAt = System.currentTimeMillis();
        }

        // ② 冻结诊断（只读零副作用）：自愈触发后 10 秒内每 20 tick 打排查快照——分辨
        //    「输入层瘫痪」（client input=0）vs「客户端本地正常但被服务端拉回」（client pos 动
        //    而 server pos 不动 / awaitingTp 卡真=服务端等传送确认拒移动包）vs「血量/姿势残留」
        //    vs「tick 资格丢失」（clientTicking=false——实体不在客户端 tick 表，aiStep 不跑）
        if (revivedAt > 0 && System.currentTimeMillis() - revivedAt < 10_000L
            && (diagTick++ % 20L) == 0L && player != null && mc.level != null) {
            try {
                net.minecraft.world.entity.Pose pose = player.getPose();
                int deathTime = ((LivingEntityFieldsAccessor) player)
                    .adventure_power$getDeathTime();
                boolean clientTicking = false;
                if (TICKING_ENTITIES_FIELD != null) {
                    try {
                        Object tickList = TICKING_ENTITIES_FIELD.get(mc.level);
                        if (tickList instanceof net.minecraft.world.level.entity.EntityTickList l) {
                            clientTicking = l.contains(player);
                        }
                    } catch (Exception ignored) {
                    }
                }
                String serverInfo;
                var server = mc.getSingleplayerServer();
                net.minecraft.server.level.ServerPlayer sp =
                    server != null ? server.getPlayerList().getPlayer(player.getUUID()) : null;
                if (sp != null) {
                    String awaitingTp = "n/a";
                    if (AWAITING_TP_FIELD != null) {
                        try {
                            awaitingTp = String.valueOf(AWAITING_TP_FIELD.get(sp.connection));
                        } catch (Exception ignored) {
                        }
                    }
                    serverInfo = String.format("server: hp=%.1f removed=%s pos=(%.2f,%.2f,%.2f) awaitingTp=%s",
                        sp.getHealth(), sp.isRemoved(), sp.getX(), sp.getY(), sp.getZ(), awaitingTp);
                } else {
                    serverInfo = "server: player-not-found";
                }
                LOGGER.warn("[冻结诊断] client: hp={} pose={} deathTime={} removed={} clientTicking={}"
                    + " screen={} input=({},{}) pos=({},{},{}) | {}",
                    player.getHealth(), pose, deathTime, player.isRemoved(), clientTicking,
                    mc.screen == null ? "null" : mc.screen.getClass().getSimpleName(),
                    player.input.leftImpulse, player.input.forwardImpulse,
                    player.getX(), player.getY(), player.getZ(), serverInfo);
            } catch (Exception e) {
                LOGGER.warn("[冻结诊断] 快照异常: {}", e.toString());
            }
        }
    }

    /** 客户端 tick 表归位（f_171630_ 反射；失败静默——诊断位会显示未归位，不影响其他自愈）。 */
    private static void addToTickListIfMissing(LocalPlayer player) {
        if (TICKING_ENTITIES_FIELD == null) return;
        try {
            Object tickList = TICKING_ENTITIES_FIELD.get(player.level());
            if (tickList instanceof net.minecraft.world.level.entity.EntityTickList l
                && !l.contains(player)) {
                l.add(player);
            }
        } catch (Exception ignored) {
        }
    }

    /** 读取客户端 tick 表包含状态（诊断前采样用；反射不可用返回 false）。 */
    private static boolean isInTickList(LocalPlayer player) {
        if (TICKING_ENTITIES_FIELD == null) return false;
        try {
            Object tickList = TICKING_ENTITIES_FIELD.get(player.level());
            return tickList instanceof net.minecraft.world.level.entity.EntityTickList l
                && l.contains(player);
        } catch (Exception e) {
            return false;
        }
    }

    /** 客户端实体存储全链补充（P2-1）：EntityLookup 预清 + TESM.addEntity 一步补齐
     *  EntityLookup 双表/section/levelCallback（客户端 onRemove 级联的三处缺口）。
     *  失败静默——不影响其余自愈动作，EntityLookup 查询失明由下次自愈窗口重试。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restoreClientEntityStorage(LocalPlayer player) {
        try {
            if (ENTITY_STORAGE_FIELD == null || TESM_ADD_METHOD == null) return;
            Object tesm = ENTITY_STORAGE_FIELD.get(player.level());
            if (tesm == null) return;
            // 预清 EntityLookup：TESM.addEntity 的 EntityLookup.add 在 byUuid 已含同 UUID 时
            // warn 后直接 return（byId 不写）——残留半边必须清掉才能全量重写
            if (TESM_ENTITY_STORAGE_FIELD != null && LOOKUP_REMOVE_METHOD != null) {
                Object lookup = TESM_ENTITY_STORAGE_FIELD.get(tesm);
                if (lookup != null) {
                    LOOKUP_REMOVE_METHOD.invoke(lookup, player);
                }
            }
            TESM_ADD_METHOD.invoke(tesm, player);
        } catch (Exception ignored) {
        }
    }
}
