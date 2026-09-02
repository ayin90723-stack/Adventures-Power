package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.DebugLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实血量 —— 重生入口门禁（v1.4.9-fix，vp 实测回填）：未死之身不接受重生。
 * <p>
 * <b>背景</b>：1.20.1 原版 {@code ServerGamePacketListenerImpl.handleClientCommand} 的
 * 重生分支有 {@code player.getHealth() > 0 → return} 门禁（活着点重生无效）；但部分
 * 外部模组整个替换了 handleClientCommand（HEAD cancel + 自实现），其实现里<b>没有
 * 这个门禁</b>——玩家在假死画面（血量被持续写 0 的拉锯窗口）点"重生"会被<b>无条件
 * respawn</b>，绕过一切血量判据（对 vp 字节码实锤：其替换实现的 Revive 分支仅做
 * 自家传送/复活标记后直接调 {@code PlayerList.respawn(player, false)}）。
 * <p>
 * <b>门禁</b>：{@code respawn(player, keepEverything=false)}（重生语义——wonGame/
 * 末地 credits 走 true 不在此列）时，若玩家 true_health 启用且备份血量 &gt; 0
 * （未被合法伤害杀死的<b>事实依据</b>——正常死亡 hurt 链内 backup 已同步 ≤0，
 * 假死攻击写 0 不经合法路径、backup 恒 &gt;0），拒绝重生并返回原玩家。
 * <ul>
 *   <li>正常死亡重生：backup ≤ 0 → 放行（零影响）</li>
 *   <li>假死画面点重生：backup &gt; 0 → 拒绝——玩家实际存活，客户端死亡画面由
 *       {@code DeathScreenAutoDismissHandler}（血量恢复后自动关闭）收敛</li>
 *   <li>wonGame（末地通关标记，f_8944_）语境 respawn(player, true)：玩家活着
 *       （hp 直读 &gt;0，正常 Credits 通关/被杀后保留重生）→ 放行；hp 直读 ≤0
 *       （外部演出写 0 污染窗口的假死点重生——wonGame 标记杀过龙后恒 true，
 *       外部实现替换 handleClientCommand 后借该分支绕过血量门禁，21:10 实测
 *       真重生到出生点）→ 落入 backup 判据：backup&gt;0 拒绝，守住"未死之身"</li>
 * </ul>
 * 与原版门禁同义（"活着点重生无效"），只是把判据从瞬时血量（可被攻击写 0 伪造）
 * 换成 backup 事实。拒绝时若死亡画面仍开着，客户端会在血量同步恢复后自愈关闭。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnGateMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("adventure_power.respawn_gate");

    /** 拒绝重生日志去重：uuid → 上次拒绝时刻 gameTime（攻击期间反复点击不刷屏）。 */
    private static final Map<UUID, Long> REJECT_SEEN = new ConcurrentHashMap<>();

    @Inject(method = "m_11236_", at = @At("HEAD"), cancellable = true)
    private void rejectRespawnOfLivingAdventurer(ServerPlayer player, boolean keepEverything,
                                                 CallbackInfoReturnable<ServerPlayer> cir) {
        if (player == null) return;
        // wonGame（f_8944_，末地通关/credits）分支 = respawn(player, true)：原设计对
        // keepEverything=true 无条件放行（末地 Credits 通关重生的语义保证），但世界杀过
        // 末影龙后 wonGame 标记恒 true（直到该分支被成功走一次）——假死画面点重生（外部
        // 实现替换 handleClientCommand 后）会先命中 wonGame 分支，无条件放行=真重生到出生点
        // （21:10 实测事故。区分：正常 Credits 重生=玩家活着（hp 直读 >0）；假死点重生=外部
        // 演出持续写 0 污染数据层（hp 直读 ≤0）——以 HealthUtil.getHealthDirect 直读为据
        // （不依赖可覆写方法），hp≤0 的 keepEverything 重生落入下方 backup 判据同规处置）
        if (keepEverything && com.ayin90723.adventure_power.util.HealthUtil.getHealthDirect(player) > 0.0F) {
            return;
        }
        // 两段式 capability 读取（读空→reviveCaps→重读）：vp 连招含 invalidateCaps 腿，
        // 失效窗口内单次读取为空会使门禁失明——与容器审计门禁同款防御
        var progress = com.ayin90723.adventure_power.util.ContainerRebuilder.twoStageProgress(player);
        if (progress == null) {
            // 保守拒绝（2026-09-02 实测定案）：持续攻击窗口内 cap 读空曾静默放行 → 未死之身
            // 真重生到出生点（21:10 与 21:29 两轮实测，复活后位置偏移为证）。cap 读空=外部
            // invalidateCaps 连招中——玩家必然存活（真死时 backup=0 的读取不受影响；cap
            // 读空+真死为极端组合，审计链 ≤20 tick 恢复服务端实体后玩家可再次重生）。
            // 边界披露：该分支位于冒险者/能力检查之前——非冒险者玩家（未装配饰品/未开
            // true_health，本不该走防御门禁）若恰遇 cap 读空（模组生态异常）也会被拒一次，
            // 直至审计链或下次点击恢复；可接受的保守代价（防御优先于"普通玩家的一次重生
            // 延迟"）。另外：wonGame 分支的 hp 直读依赖 HealthUtil.getHealthDirect 反射可用
            // ——反射初始化失败时回落 TrueHealthMixin 方法读（返回 backup>0），wonGame 分支
            // 恒放行（21:10 事故形态的退化路径）；反射失败全局影响所有直读点，历史上从未
            // 出现，仅作边界披露。
            long now = player.level().getGameTime();
            Long last = REJECT_SEEN.get(player.getUUID());
            if (last == null || now - last >= 100L) {
                REJECT_SEEN.put(player.getUUID(), now);
                LOGGER.warn("[重生门禁] {} capability 读空（外部攻击窗口？），"
                    + "保守拒绝重生并返回原玩家（原地恢复）", player.getGameProfile().getName());
            }
            REJECT_SEEN.entrySet().removeIf(e -> now - e.getValue() > 12_000L);
            cir.setReturnValue(player);
            return;
        }
        // 审查修 P3-2：与 TrueHealthMixin.gatedProgress 门禁同构（isAdventurer/fullyUnlocked
        // 前置）——当前 backup>0 只可能由 gated 路径写入，补检查防未来新 setBackupHealth
        // 调用点打破该隐含不变量
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
        if (!progress.isAbilityEnabled(AbilityIds.TRUE_HEALTH)) return;
        float backup = progress.getBackupHealth();
        if (backup > 0.0F && Float.isFinite(backup)) {
            long now = player.level().getGameTime();
            Long last = REJECT_SEEN.get(player.getUUID());
            if (last == null || now - last >= 100L) {
                REJECT_SEEN.put(player.getUUID(), now);
                DebugLog.trueHealth("[MME-TrueHealth] 拒绝未死重生：backup=" + backup +
                    "（玩家实际存活——respawn 请求被外部实现绕过血量门禁直调），返回原玩家");
            }
            // 审查修 P3-1：限频表老化自清（mixin 类不能挂 @SubscribeEvent，采用容器审计
            // DIM_COOLDOWN 同款"到期自清"——上次拒绝超 10 分钟（12000 tick）的条目顺带
            // 移除；条目 8 字节级，老化清理防 UUID 长生命周期场景累积
            REJECT_SEEN.entrySet().removeIf(e -> now - e.getValue() > 12_000L);
            // 审查修 P2-1 提示：caps 失效盲窗内发生"合法致命伤"的极端组合下（读层/数据层
            // 门禁同时失明导致 backup 未同步归 0、死亡成立、本门禁拒绝重生），玩家会滞留
            // 死亡画面——其自动出口依赖容器审计链（≤20 tick 把"应活"玩家复活，死屏自愈
            // 关闭）；审计关闭（container_audit_enabled=false）时无自动出口，仅剩返回标题
            // 断线。此处提示管理员该前置条件（含血量直读 ≤0 的死亡态残留信号）
            if (com.ayin90723.adventure_power.util.HealthUtil.getHealthDirect(player) <= 0.0F) {
                LOGGER.warn("[重生门禁] {} 拒绝重生但玩家血量直读 ≤0（caps 失效盲窗内的合法死亡？"
                    + "）——滞留死屏的唯一自动出口是容器审计链（container_audit_enabled 须为 true）",
                    player.getGameProfile().getName());
            }
            cir.setReturnValue(player);
        }
    }
}
