package com.ayin90723.adventure_power.util.probe.gate;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.probe.PendingVerifyRegistry;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GateOracle 阶段四+五（docs/gate-oracle-proposal.md §6/§6.1/§6.2）：
 * KillPlan 生成与 GateOpen 执行——数值探针全败（engine-exhausted）后改用
 * isAlive/isDeadOrDying 语义翻转定位并打开存活许可，让目标走正规 die
 * （战利品/BossBar/簿记对方自清），失败退影杀善后段（开门优先、抹除兜底，零退化）。
 * <p>
 * <b>击杀写升级梯（§6.1 终裁）</b>：每个 Boss 类首次开门走完整梯，命中的介质+模式缓存进
 * {@code GatePlan.resolved}（同模式直用、验证照常、失败作废重走梯）：
 * <ol>
 *   <li><b>KILL_TOOL</b>：反射调用目标自己的静态击杀工具（含常量实参回放）→ 目标自清
 *       （含其注册表/复活列表）→ 真死。验证 = 双条件（isAlive 翻死 且 [LivingDeathEvent
 *       观测 或 deathTime&gt;0 或 removed]——despawn 型只移除不发事件，被排除不采用）。
 *       <b>无探针需求</b>（验证就是死亡本身），前置 = 签名闸硬性：唯一实体作用域参数
 *       （Entity/LivingEntity；多实体参=位歧义弃用，零实体参=副作用面无界排除）+
 *       非实体参可推导（UUID/常量回放，取不到弃用）。</li>
 *   <li><b>PERMIT/PROGRESS 静默写 + 有界等待</b>：可逆探针（DataItem.value 字段直写/Field.set
 *       静默翻转 → liveness 翻转验证 → 无条件还原）通过的候选落静默击杀写，挂
 *       {@link PendingVerifyRegistry}（统一 pending 表）等待 {@code gate_oracle_wait_ticks}；
 *       等待期间目标死亡（含覆写 isAlive 翻死）= 开门成功，正规死亡链自清。</li>
 *   <li><b>响写</b>：超时改 {@code data.set()} 走完整回调链（回调型 Boss 的死亡逻辑挂在
 *       onSyncedDataUpdated/setter 通知——写入必须"响"）；仅 DataItem 槽候选适用。</li>
 *   <li><b>同栈 die()</b>：再超时直接调 {@code target.die(source)}（主动型：die 覆写检查
 *       旗标）；同栈确认死亡。</li>
 *   <li>全败 → {@code finalizeFallback}（影杀善后段补跑）。</li>
 * </ol>
 * <b>探针静默纪律（§6.2）</b>：语义探针一律静默介质（DataItem.value 字段直写——
 * {@code data.get()} 照常读到新值、回调零触发），与击杀写介质配对由升级梯裁决；
 * 探针不提供分类信号。主线程同栈完成写-验证-还原，无观察窗口（守护线程型环境级对手边界外）。
 * <p>
 * 接入点：影杀 saturationKill 归零段 {@code BloodWriteEngine.execute(target, 0)} 返回
 * exhausted 时（处决语义；淬魂磨血不触发——伤害语义与开门语义冲突）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GateOracle {

    private GateOracle() {
    }

    /**
     * tryOpen 结果：SYNC_DEAD=同步确认死亡（正规链自清）；PENDING=轮询等待（善后段已挂兜底）；
     * FAILED=升级梯全败（<b>善后已由 finalizeFallback 跑过</b>，调用方须直接 return，勿双跑）；
     * NOT_APPLICABLE=目标不适用（无覆写/开关关/异常——未做任何事，调用方继续原流程）。
     */
    public enum OpenResult { SYNC_DEAD, PENDING, FAILED, NOT_APPLICABLE }

    // ==================== LivingDeathEvent 观测（KILL_TOOL despawn 区分） ====================

    /** 当 tick 观测到 LivingDeathEvent 的实体（ServerTick END 清空防泄漏；正规 kill 自然发事件，despawn 不发）。 */
    private static final Set<UUID> DEATH_SEEN = ConcurrentHashMap.newKeySet();

    /** 开门尝试中的目标（防重入：等待窗口内影杀再次触发不重复堆任务/重复写）。 */
    private static final Set<UUID> OPENING = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            DEATH_SEEN.add(event.getEntity().getUUID());
            // 审查修 P1#1：异步成功路径兜底——目标最终死亡则开门尝试必然终结，
            // 清 OPENING 防"复活再战时 tryOpen 被残留集合挡住 → saturationKill 直接
            // return → finalizeFallback 不调度"的不可击杀死锁
            OPENING.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent event) {
        if (event.phase == Phase.END) DEATH_SEEN.clear();
    }

    // ==================== 主入口 ====================

    /**
     * GateOpen 主入口（影杀 saturationKill 在 engine-exhausted 后调用）。
     *
     * @param finalizeFallback 全败兜底（影杀善后段：战利品+事件+移除链+容器抹除）——
     *                         轮询型等待超时的降级链末端执行
     */
    public static OpenResult tryOpen(LivingEntity target, DamageSource source, Runnable finalizeFallback) {
        if (!ModConfig.GATE_ORACLE_ENABLED.get()) {
            DebugLog.probe("[GateOracle] 引擎 exhausted 且开关关闭（gate_oracle_enabled=false），跳过语义开门");
            return OpenResult.NOT_APPLICABLE;
        }
        if (target instanceof Player) return OpenResult.NOT_APPLICABLE; // 审查修 P3#10：FAILED=善后已跑过，此处未做任何事
        if (!OPENING.add(target.getUUID())) return OpenResult.PENDING; // 已有开门尝试在等待——不重入
        // 处决语境破盾（2026-08-21 四轮实测驱动）：合成血 Boss 的真血探针（写真血 0 →
        // 合成读数须归 0 才翻 liveness）在盾 >0 时恒失败（读数 = 0+盾 > 0）——先清盾
        // 给 DERIVED_BLOOD/PROGRESS 真血候选铺路（影杀处决语境清盾语义天然合理）
        com.ayin90723.adventure_power.util.probe.MultiStoreWriter.clearShieldComponents(target);
        try {
            GateAnalyzer.GatePlan plan = GateAnalyzer.analyze(target);
            if (plan.isEmpty()) {
                DebugLog.probe("[GateOracle] {} 无模组层 liveness 覆写，不适用", target.getClass().getSimpleName());
                OPENING.remove(target.getUUID());
                return OpenResult.NOT_APPLICABLE;
            }
            OpenResult r = new GateAttempt(target, source, plan, finalizeFallback).run();
            if (r != OpenResult.PENDING) OPENING.remove(target.getUUID());
            return r;
        } catch (Exception e) {
            DebugLog.probe("[GateOracle] tryOpen 异常（零退化退影杀）: {}", e.toString());
            OPENING.remove(target.getUUID());
            return OpenResult.NOT_APPLICABLE;
        }
    }

    /** 级联失效：击杀方案缓存清空（分析结论随类终身有效，仅 resolved 作废）。 */
    public static void invalidate() {
        GateAnalyzer.invalidate();
    }

    /** 击杀方案缓存：mode + 句柄（StateCandidate / KillToolCandidate）。 */
    private record ResolvedPlan(String mode, Object handle) {
    }

    // ==================== per-kill 状态机 ====================

    private static final class GateAttempt {
        final LivingEntity target;
        final DamageSource source;
        final GateAnalyzer.GatePlan plan;
        final Runnable finalizeFallback;
        /** 当前走梯候选（探针通过后激活）。 */
        GateAnalyzer.StateCandidate active;
        /** 探针裁决出的"死态写入值"（PERMIT 的死向布尔 / PROGRESS 的过阈值值）。 */
        Object probeDeadValue;

        GateAttempt(LivingEntity target, DamageSource source, GateAnalyzer.GatePlan plan, Runnable finalizeFallback) {
            this.target = target;
            this.source = source;
            this.plan = plan;
            this.finalizeFallback = finalizeFallback;
        }

        OpenResult run() {
            // resolved 快路径（首杀升级梯裁决产物）：同模式直用，验证照常，失败作废重走梯
            Object resolved = plan.resolved;
            if (resolved instanceof ResolvedPlan rp) {
                if (applyResolved(rp)) return outcomeOf(rp.mode());
                plan.resolved = null;
            }
            // 梯级0：KILL_TOOL（§6 顺序纪律限定——无探针需求，验证=死亡事件本身）
            if (ModConfig.GATE_ORACLE_KILL_TOOL_ENABLED.get()) {
                for (GateAnalyzer.KillToolCandidate kt : plan.killTools) {
                    if (invokeKillTool(kt) && confirmDead()) {
                        plan.resolved = new ResolvedPlan("KILL_TOOL", kt);
                        DebugLog.probe("[GateOracle] KILL_TOOL 命中 {} → 正规死亡链自清", kt);
                        return OpenResult.SYNC_DEAD;
                    }
                }
            }
            // 梯级1：EXEC_COMBO 组合验证（七轮——处决语境对症 die 覆写型）：对每候选
            // "写死态 + 同栈 die() + confirmDead"。六轮实测翻转型探针在处决语境天然全败——
            // 影杀 execute 已写真血 0，isAlive 覆写返回 false（名义已死），探针"翻活翻死"
            // 判定恒失败；而真正的门是 die 覆写内部检查的旗标（写对旗标 + 调 die = 正规
            // 死亡链启动）。验证失败还原候选（不残留乱写），成功保留死态不还原（门已开）
            // 十六轮：deathSequence 类候选优先走"触发模式"（写 true + 等对面自己的 tick 启动
            // 死亡序列演出，不调 die）——removalAuthorized 等快进通道实测被 Integrity 恢复
            // （移除成功 0.5s 后 restored：对面管理系统的注销钩子挂在死亡序列流程里，快进
            // 死亡它看不到）；19:46 成功案例实锤完整演出走完会打"所有使徒已消失"注销遭遇
            for (GateAnalyzer.StateCandidate sc : plan.candidates) {
                if (isDeathSequenceGate(sc) && triggerDeathSequence(sc)) {
                    return OpenResult.PENDING; // 演出期间等待，pending 裁决终态
                }
            }
            for (GateAnalyzer.StateCandidate sc : plan.candidates) {
                if (execComboVerify(sc)) {
                    plan.resolved = new ResolvedPlan("EXEC_COMBO", sc);
                    DebugLog.probe("[GateOracle] EXEC_COMBO 开门成功（候选={} → 正规死亡链自清）", sc);
                    return OpenResult.SYNC_DEAD;
                }
            }
            DebugLog.probe("[GateOracle] 全部候选组合验证失败（写死态+die 均未启动正规死亡），补纯 die() 终结尝试");
            return finishWithInlineDie();
        }

        /** deathSequence 类候选判定（名字含 deathsequence/deathsequenceActive 词根的 PERMIT）。 */
        private static boolean isDeathSequenceGate(GateAnalyzer.StateCandidate sc) {
            String n = sc.name.toLowerCase();
            return n.contains("deathsequence") || n.contains("death_sequence");
        }

        /**
         * 死亡序列触发模式（十六轮）：写候选为 true（激活对面自己的死亡序列）+ 挂 pending
         * 等待演出启动（deathTime&gt;0 / removed）——**不调 die**（die 覆写对已激活序列可能
         * 忽略重复调用）。成功 = 对面完整演出死亡（管理系统注销遭遇，Integrity 不恢复）；
         * 窗口末未启动 → 退回 EXEC_COMBO 常规梯。
         */
        private boolean triggerDeathSequence(GateAnalyzer.StateCandidate sc) {
            try {
                Object orig = snapshotCandidate(sc);
                if (!(orig instanceof Boolean b) || b) return false; // 非 false 布尔（已激活/非布尔）→ 不适用
                writeCandidateValue(sc, Boolean.TRUE);
                DebugLog.probe("[GateOracle] 死亡序列触发模式：激活 {} → 等待对面演出启动", sc);
                PendingVerifyRegistry.register(target, ModConfig.GATE_ORACLE_WAIT_TICKS.get(),
                    new PendingVerifyRegistry.PendingTask() {
                        @Override
                        public boolean onVerify(LivingEntity t) {
                            if (confirmDead()) {
                                OPENING.remove(t.getUUID());  // 审查修 P1#1：异步成功终结开门尝试
                                plan.resolved = new ResolvedPlan("EXEC_COMBO", sc);
                                DebugLog.probe("[GateOracle] 死亡序列演出已启动并完成（候选={} → 正规链自清）", sc);
                                return true;
                            }
                            return false;
                        }

                        @Override
                        public void onDead(LivingEntity t) {
                            OPENING.remove(t.getUUID());  // 审查修 P1#1：窗口内死亡终结开门尝试
                        }

                        @Override
                        public void onFail(LivingEntity t) {
                            DebugLog.probe("[GateOracle] 死亡序列触发失败（窗口内未启动演出），还原候选退常规梯", sc);
                            writeCandidateValue(sc, orig);
                            finishWithInlineDie();  // 内部已清 OPENING
                        }
                    });
                return true; // 触发已提交，等待裁决
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * EXEC_COMBO 组合验证：快照候选现值 → 写死态 → 同栈 die() → confirmDead
         * （正规死亡链证据）。失败还原候选返回 false；成功保留死态（不还原——门已开）。
         * <p>
         * 十五轮补充：die() 同栈已发 LivingDeathEvent 但硬证据（removed/deathTime）未出时
         * （死亡序列异步启动型——deathSequenceActive 表演下一 tick 才递增 deathTime），
         * 不再立即还原判失败——挂 pending 窗口等硬证据，窗口末裁决（成功补客户端同步，
         * 失败还原候选+finalizeFallback）。立即还原会让"die 事件已发、死亡流程将启动"
         * 的半开门状态被回滚，对面的遭遇系统把已发事件的死亡当"中断"恢复（实测
         * restored 二阶段复制）。
         */
        private boolean execComboVerify(GateAnalyzer.StateCandidate sc) {
            try {
                Object deadVal = deadValueOf(sc);
                if (deadVal == null) return false;
                Object orig = snapshotCandidate(sc);
                if (orig == null) return false; // 介质不可读——该候选不可行
                writeCandidateValue(sc, deadVal);
                boolean diePosted;
                try {
                    DEATH_SEEN.clear();
                    target.die(source);
                    diePosted = DEATH_SEEN.contains(target.getUUID());
                } catch (Exception e) {
                    diePosted = false;
                }
                if (confirmDead()) {
                    return true;
                }
                if (diePosted) {
                    // die 事件已发但死亡流程异步启动中——挂窗口等硬证据（十五轮：异步确认）
                    scheduleComboConfirm(sc, orig);
                    return true; // 视为开门中——由 pending 裁决终态
                }
                writeCandidateValue(sc, orig);
                return false;
            } catch (Exception e) {
                return false;
            }
        }

        /** EXEC_COMBO 异步确认（十五轮）：窗口末 removed/deathTime → 成功自清；否则还原候选退影杀。 */
        private void scheduleComboConfirm(GateAnalyzer.StateCandidate sc, Object orig) {
            DebugLog.probe("[GateOracle] die 已发事件但死亡流程异步启动中，挂窗口等待硬证据（候选={}）", sc);
            PendingVerifyRegistry.register(target, ModConfig.GATE_ORACLE_WAIT_TICKS.get(),
                new PendingVerifyRegistry.PendingTask() {
                    @Override
                    public boolean onVerify(LivingEntity t) {
                        if (confirmDead()) {
                            OPENING.remove(t.getUUID());  // 审查修 P1#1：异步成功终结开门尝试
                            plan.resolved = new ResolvedPlan("EXEC_COMBO", sc);
                            DebugLog.probe("[GateOracle] EXEC_COMBO 异步确认成功（候选={} → 正规死亡链自清）", sc);
                            return true;
                        }
                        return false; // → onFail
                    }

                    @Override
                    public void onDead(LivingEntity t) {
                        OPENING.remove(t.getUUID());  // 审查修 P1#1：窗口内死亡终结开门尝试
                    }

                    @Override
                    public void onFail(LivingEntity t) {
                        DebugLog.probe("[GateOracle] EXEC_COMBO 异步确认失败（死亡流程未启动），还原候选退影杀");
                        writeCandidateValue(sc, orig);
                        plan.resolved = null;
                        finishWithInlineDie();  // 内部已清 OPENING
                    }
                });
        }

        /** 候选现值快照（槽/字段介质；读失败返回 null）。 */
        private Object snapshotCandidate(GateAnalyzer.StateCandidate sc) {
            try {
                switch (sc.kind) {
                    case PERMIT_FIELD, PROGRESS_FIELD, DERIVED_BLOOD_FIELD -> {
                        java.lang.reflect.Field f = resolveInstanceField(sc);
                        return f == null ? null : f.get(target);
                    }
                    case PERMIT_DATA_ITEM, PROGRESS_DATA_ITEM -> {
                        DataItemMedium medium = resolveDataItem(sc);
                        return medium == null ? null : HealthUtil.readDataItemValue(medium.item());
                    }
                    default -> {
                        return null;
                    }
                }
            } catch (Exception e) {
                return null;
            }
        }

        /** 按指定值写候选（字段/槽介质分派；介质不可用静默失败）。 */
        private void writeCandidateValue(GateAnalyzer.StateCandidate sc, Object value) {
            try {
                switch (sc.kind) {
                    case PERMIT_FIELD, PROGRESS_FIELD, DERIVED_BLOOD_FIELD -> {
                        java.lang.reflect.Field f = resolveInstanceField(sc);
                        if (f == null) return;
                        if (value instanceof Boolean b) {
                            f.setBoolean(target, b);
                        } else if (value instanceof Float fl) {
                            f.setFloat(target, fl);
                        } else if (value instanceof Integer in) {
                            f.setInt(target, in);
                        }
                    }
                    case PERMIT_DATA_ITEM, PROGRESS_DATA_ITEM -> {
                        DataItemMedium medium = resolveDataItem(sc);
                        if (medium != null) {
                            HealthUtil.writeDataItemValue(medium.item(), value);
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        /** resolved 快路径执行（验证失败返回 false 由调用方作废重走梯）。 */
        private boolean applyResolved(ResolvedPlan rp) {
            return switch (rp.mode()) {
                case "KILL_TOOL" -> invokeKillTool((GateAnalyzer.KillToolCandidate) rp.handle()) && confirmDead();
                // 审查修 P1#3：EXEC_COMBO 回放（原缺失——resolved 写了但 default->false 立即作废，
                // 每次击杀重走全梯，命中前的失败候选各调一次 die() 副作用按候选数重复）
                case "EXEC_COMBO" -> {
                    GateAnalyzer.StateCandidate sc = (GateAnalyzer.StateCandidate) rp.handle();
                    active = sc;
                    yield execComboVerify(sc);  // 快照→写死态→die→confirmDead→失败还原，内部自洽
                }
                case "POLL_SILENT" -> {
                    GateAnalyzer.StateCandidate sc = (GateAnalyzer.StateCandidate) rp.handle();
                    active = sc;
                    probeDeadValue = deadValueOf(sc);
                    if (probeDeadValue == null) yield false;
                    writeKillStateSilently();
                    scheduleWait(this::onTimeoutLoud);
                    yield true; // PENDING 语义：等待窗口是验证的一部分
                }
                case "LOUD_SET" -> {
                    GateAnalyzer.StateCandidate sc = (GateAnalyzer.StateCandidate) rp.handle();
                    active = sc;
                    probeDeadValue = deadValueOf(sc);
                    if (probeDeadValue == null) yield false;
                    loudWrite();
                    scheduleWait(this::onTimeoutDie);
                    yield true;
                }
                case "INLINE_DIE" -> {
                    try {
                        target.die(source);
                    } catch (Exception e) {
                        yield false;
                    }
                    yield confirmDead();
                }
                default -> false;
            };
        }

        private static OpenResult outcomeOf(String mode) {
            return "INLINE_DIE".equals(mode) || "KILL_TOOL".equals(mode) ? OpenResult.SYNC_DEAD : OpenResult.PENDING;
        }

        // ==================== 等待/降级链 ====================

        /** 挂有界等待：窗口内死亡（含覆写 isAlive 翻死）= 开门成功；超时 onFail 降级。
         * 审查修 P2#4 标注：POLL_SILENT/LOUD_SET 轮询型梯级自七轮 EXEC_COMBO 取代后未接线
         * （run() 只产生 KILL_TOOL/EXEC_COMBO/INLINE_DIE 三种 mode）——本方法与
         * applyResolved 轮询分支保留作轮询型 Boss（tick 消费许可而非 die 覆写）预留，
         * 当前不可达。 */
        private void scheduleWait(Runnable onTimeout) {
            int wait = ModConfig.GATE_ORACLE_WAIT_TICKS.get();
            PendingVerifyRegistry.register(target, wait, new PendingVerifyRegistry.PendingTask() {
                @Override
                public boolean onVerify(LivingEntity t) {
                    return isDeadish(t); // 窗口到点仍未死 → onFail 降级
                }

                @Override
                public void onDead(LivingEntity t) {
                    OPENING.remove(t.getUUID());
                    DebugLog.probe("[GateOracle] 开门成功：等待窗口内目标进入死亡（正规链自清）");
                }

                @Override
                public void onFail(LivingEntity t) {
                    onTimeout.run();
                }
            });
        }

        /** 梯级2：响写（data.set() 触发回调链——回调型死亡逻辑挂在通知上，写入必须"响"）。 */
        private void onTimeoutLoud() {
            if (active != null && active.kind.name().endsWith("_DATA_ITEM") && probeDeadValue != null) {
                loudWrite();
                plan.resolved = new ResolvedPlan("LOUD_SET", active);
                scheduleWait(this::onTimeoutDie);
                DebugLog.probe("[GateOracle] 静默写超时，改响写等待（候选={}）", active);
                return;
            }
            // 字段型候选无"响"通道——直接梯级3
            onTimeoutDie();
        }

        /** 梯级3：同栈 die()（主动型：die 覆写消费旗标）；确认失败退影杀善后段。 */
        private void onTimeoutDie() {
            finishWithInlineDie();
        }

        /** 同栈 die() 终结尝试（升级梯末端 / 候选探针全败直达）：成功记 resolved，失败补跑影杀善后段。 */
        private OpenResult finishWithInlineDie() {
            OPENING.remove(target.getUUID());
            boolean dead = false;
            try {
                target.die(source);
                dead = confirmDead();
            } catch (Exception e) {
                DebugLog.probe("[GateOracle] die() 异常: {}", e.toString());
            }
            if (dead) {
                plan.resolved = new ResolvedPlan("INLINE_DIE", active);
                DebugLog.probe("[GateOracle] 同栈 die() 开门成功（正规链自清）");
                return OpenResult.SYNC_DEAD;
            }
            plan.resolved = null;
            DebugLog.probe("[GateOracle] 升级梯全败，退影杀善后段 target={}", target);
            try {
                finalizeFallback.run();
            } catch (Exception ignored) {
            }
            return OpenResult.FAILED;
        }

        // ==================== KILL_TOOL（签名闸 + 常量回放 + 双条件验证） ====================

        /**
         * KILL_TOOL 调用（§3 v3 签名闸硬性）：
         * 唯一实体作用域参数（Entity/LivingEntity——多实体参位歧义弃用、零实体参无界排除）；
         * UUID 参传 target.uuid；其余参数从调用点常量回放按类型取，取不到弃用（保守）。
         */
        private boolean invokeKillTool(GateAnalyzer.KillToolCandidate kt) {
            try {
                Class<?> owner = Class.forName(kt.owner.replace('/', '.'), false,
                    target.getClass().getClassLoader());
                Class<?>[] params = descToParams(kt.desc);
                Method m = owner.getDeclaredMethod(kt.name, params);
                m.setAccessible(true);
                if (!Modifier.isStatic(m.getModifiers())) return false;
                Object[] args = buildKillToolArgs(params, kt.constArgs);
                if (args == null) {
                    DebugLog.probe("[GateOracle] KILL_TOOL 签名闸排除 {}（参数位不可推导）", kt);
                    return false;
                }
                m.invoke(null, args);
                return true;
            } catch (Exception e) {
                DebugLog.probe("[GateOracle] KILL_TOOL 调用失败 {}: {}", kt, e.toString());
                return false;
            }
        }

        /** 签名闸实参组装：Entity 参恰一个传 target；UUID 参传 uuid；其余按类型从常量回放取。不可推导返回 null（弃用）。 */
        private Object[] buildKillToolArgs(Class<?>[] params, java.util.List<Object> consts) {
            Object[] args = new Object[params.length];
            int entitySlots = 0;
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                if (Entity.class.isAssignableFrom(p)) {
                    args[i] = target;
                    entitySlots++;
                } else if (p == UUID.class) {
                    args[i] = target.getUUID();
                } else {
                    Object c = pickConst(consts, p);
                    if (c == null) return null;
                    args[i] = c;
                }
            }
            return entitySlots == 1 ? args : null;
        }

        /** 按类型从常量序列取第一个匹配值（顺序回放）。 */
        private static Object pickConst(java.util.List<Object> consts, Class<?> type) {
            for (Object c : consts) {
                // 审查修 P1#2：布尔字面量在字节码里编译为 ICONST（收集为 Integer），
                // JVM 不允许 Boolean 走 LDC——原 instanceof Boolean 分支恒 false，
                // killEntity(target, true) 型工具的 boolean 回放静默失效
                if (type == boolean.class && c instanceof Integer bi) return bi != 0;
                if (type == boolean.class && c instanceof Boolean b) return b;
                if (type == int.class && c instanceof Integer i) return i;
                if (type == long.class && c instanceof Long l) return l;
                if (type == float.class && c instanceof Float f) return f;
                if (type == double.class && (c instanceof Double || c instanceof Float)) {
                    return c instanceof Float fl ? fl.doubleValue() : c;
                }
                if (type == String.class && c instanceof String s) return s;
                if (type == Object.class) return c;
            }
            return null;
        }

        /** 方法 desc → 参数类型数组（基本类型 + L...; 类引用；数组/不支持形态抛异常由调用方兜）。 */
        private static Class<?>[] descToParams(String desc) throws ClassNotFoundException {
            java.util.List<Class<?>> out = new java.util.ArrayList<>();
            int i = 1; // 跳过 '('
            while (desc.charAt(i) != ')') {
                char c = desc.charAt(i);
                switch (c) {
                    case 'Z' -> { out.add(boolean.class); i++; }
                    case 'I' -> { out.add(int.class); i++; }
                    case 'J' -> { out.add(long.class); i++; }
                    case 'F' -> { out.add(float.class); i++; }
                    case 'D' -> { out.add(double.class); i++; }
                    case 'L' -> {
                        int semi = desc.indexOf(';', i);
                        String cn = desc.substring(i + 1, semi).replace('/', '.');
                        out.add(primitiveAwareClass(cn));
                        i = semi + 1;
                    }
                    default -> throw new ClassNotFoundException("不支持的参数形态: " + desc);
                }
            }
            return out.toArray(new Class<?>[0]);
        }

        private static Class<?> primitiveAwareClass(String name) throws ClassNotFoundException {
            return Class.forName(name, false, GateOracle.class.getClassLoader());
        }

        // ==================== 语义探针（静默可逆） ====================

        /**
         * 静默可逆探针（§6 probeSteps）：翻转候选 → liveness 翻转验证 → 无条件还原。
         * 通过时 {@link #flipAndObserve} 记录死态值（使 liveness 翻死的那一侧值）供击杀写复用；
         * 还原失败（finally 还原后 liveness 仍翻转）视为通道不可用，返回 false 不缓存（§9 纪律）。
         */
        private boolean probeReversible(GateAnalyzer.StateCandidate sc) {
            try {
                boolean aliveBefore = target.isAlive();
                Object deadVal = flipAndObserve(sc);
                if (deadVal == NOT_A_CANDIDATE) return false;
                boolean aliveAfter = target.isAlive();
                // 通过 = 探针翻转成功（死态值非 null）且还原成功（liveness 回到探针前值）
                return deadVal != null && aliveAfter == aliveBefore;
            } catch (Exception e) {
                return false;
            }
        }

        /** 非候选哨兵（flipAndObserve 无法解析介质）。 */
        private static final Object NOT_A_CANDIDATE = new Object();

        /**
         * 候选翻转并观察，返回"死态值"（使 isAlive 变 false 的写入值）；探针失败返回 null；
         * 介质不可用返回 {@link #NOT_A_CANDIDATE}。内部 finally 无条件还原。
         */
        private Object flipAndObserve(GateAnalyzer.StateCandidate sc) throws Exception {
            switch (sc.kind) {
                case PERMIT_FIELD: {
                    Field f = resolveInstanceField(sc);
                    if (f == null) return NOT_A_CANDIDATE;
                    Object orig = f.get(target);
                    if (!(orig instanceof Boolean b)) return NOT_A_CANDIDATE;
                    try {
                        boolean before = target.isAlive();
                        f.set(target, !b);
                        boolean deadSide = !target.isAlive() && before;
                        return deadSide ? !b : null;
                    } finally {
                        f.set(target, orig);
                    }
                }
                case PERMIT_DATA_ITEM:
                case PROGRESS_DATA_ITEM: {
                    DataItemMedium medium = resolveDataItem(sc);
                    if (medium == null) return NOT_A_CANDIDATE;
                    Object orig = HealthUtil.readDataItemValue(medium.item());
                    Object deadVal = dataItemDeadValue(orig, sc);
                    if (deadVal == NOT_A_CANDIDATE || deadVal == null) return deadVal;
                    try {
                        boolean before = target.isAlive();
                        HealthUtil.writeDataItemValue(medium.item(), deadVal);
                        boolean deadSide = !target.isAlive() && before;
                        return deadSide ? deadVal : null;
                    } finally {
                        HealthUtil.writeDataItemValue(medium.item(), orig);
                    }
                }
                case PROGRESS_FIELD: {
                    Field f = resolveInstanceField(sc);
                    if (f == null) return NOT_A_CANDIDATE;
                    float orig = f.getFloat(target);
                    Float deadVal = progressDeadValue(orig, sc);
                    if (deadVal == null) return NOT_A_CANDIDATE;
                    try {
                        boolean before = target.isAlive();
                        f.setFloat(target, deadVal);
                        boolean deadSide = !target.isAlive() && before;
                        return deadSide ? (Object) deadVal : null;
                    } finally {
                        f.setFloat(target, orig);
                    }
                }
                case DERIVED_BLOOD_FIELD: {
                    // 派生血（isDeadOrDying 委托 float 比 0）：探针 = 写 0 验证 isDeadOrDying 翻转
                    Field f = resolveInstanceField(sc);
                    if (f == null) return NOT_A_CANDIDATE;
                    float orig = f.getFloat(target);
                    try {
                        boolean before = target.isDeadOrDying();
                        f.setFloat(target, 0.0F);
                        boolean deadSide = target.isDeadOrDying() && !before;
                        return deadSide ? (Object) Float.valueOf(0.0F) : null;
                    } finally {
                        f.setFloat(target, orig);
                    }
                }
                default:
                    return NOT_A_CANDIDATE;
            }
        }

        /**
         * 死态写入值推导（resolved 快路径 / DATA_ITEM 运行时定型共用）。
         * PERMIT：当前布尔取反；PROGRESS：阈值另一侧最小过阈（compareConst±ε，无阈值时 0）；
         * 派生血：0。
         */
        private Object deadValueOf(GateAnalyzer.StateCandidate sc) {
            try {
                switch (sc.kind) {
                    case PERMIT_FIELD -> {
                        Field f = resolveInstanceField(sc);
                        return f != null && f.get(target) instanceof Boolean b ? !b : null;
                    }
                    case PERMIT_DATA_ITEM, PROGRESS_DATA_ITEM -> {
                        DataItemMedium medium = resolveDataItem(sc);
                        if (medium == null) return null;
                        return dataItemDeadValue(HealthUtil.readDataItemValue(medium.item()), sc);
                    }
                    case PROGRESS_FIELD -> {
                        Field f = resolveInstanceField(sc);
                        return f != null ? progressDeadValue(f.getFloat(target), sc) : null;
                    }
                    case DERIVED_BLOOD_FIELD -> {
                        return 0.0F;
                    }
                    default -> {
                        return null;
                    }
                }
            } catch (Exception e) {
                return null;
            }
        }

        /** DataItem 槽死态值：Boolean→取反；Float/Integer→progressDeadValue；其他形态 NOT_A_CANDIDATE（运行时定型）。 */
        private Object dataItemDeadValue(Object orig, GateAnalyzer.StateCandidate sc) {
            if (orig instanceof Boolean b) return !b;
            Float base = HealthUtil.readDataItemFloatLike(orig);
            if (base != null) {
                Float d = progressDeadValue(base, sc);
                if (orig instanceof Integer) return d == null ? null : (int) (float) d;
                return d;
            }
            return NOT_A_CANDIDATE;
        }

        /** 进度死态值：阈值另一侧最小过阈写入（别写巨值——§8 纪律）；无阈值时 0（&lt;0 判死是常见方向）。 */
        private static Float progressDeadValue(float cur, GateAnalyzer.StateCandidate sc) {
            if (sc.compareConst != null) {
                float eps = Math.max(1.0F, Math.abs(sc.compareConst) * 0.01F);
                return cur < sc.compareConst ? sc.compareConst + eps : sc.compareConst - eps;
            }
            return cur <= 0.0F ? cur - 1.0F : 0.0F;
        }

        // ==================== 击杀写介质 ====================

        /** 静默击杀写（轮询型）：DataItem.value 字段直写 / 实例字段直写——回调零触发，tick 轮询照常读到。 */
        private void writeKillStateSilently() {
            if (active.kind.name().endsWith("_DATA_ITEM")) {
                DataItemMedium medium = resolveDataItem(active);
                if (medium != null) {
                    HealthUtil.writeDataItemValue(medium.item(), probeDeadValue);
                }
            } else {
                Field f = resolveInstanceField(active);
                if (f != null) {
                    try {
                        if (probeDeadValue instanceof Boolean b) {
                            f.setBoolean(target, b);
                        } else if (probeDeadValue instanceof Float fl) {
                            f.setFloat(target, fl);
                        } else if (probeDeadValue instanceof Integer in) {
                            f.setInt(target, in);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        /** 响写（回调型）：data.set() 走完整回调链（onSyncedDataUpdated/setter 通知——写入必须"响"）。 */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void loudWrite() {
            DataItemMedium medium = resolveDataItem(active);
            if (medium == null) return;
            try {
                target.getEntityData().set((EntityDataAccessor) medium.accessor(), probeDeadValue);
            } catch (Exception e) {
                DebugLog.probe("[GateOracle] 响写异常: {}", e.toString());
            }
        }

        // ==================== 介质解析 ====================

        /** DataItem 槽介质：accessor 静态字段 → 槽 id → itemsById 条目。 */
        private record DataItemMedium(EntityDataAccessor<?> accessor, Object item) {
        }

        private DataItemMedium resolveDataItem(GateAnalyzer.StateCandidate sc) {
            try {
                // 沿 target 类链按名找静态 accessor（hidden class 场景必须：hidden 副本的静态
                // accessor 是独立初始化的新 id，原始类的 accessor 与 hidden 实例的 itemsById
                // 对不上；普通场景 target 链即声明链，等价）
                for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(sc.name);
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        if (!(f.get(null) instanceof EntityDataAccessor<?> accessor)) continue;
                        Map<Integer, Object> items = HealthUtil.getDataItems(target);
                        if (items == null) return null;
                        Object item = items.get(accessor.getId());
                        if (item != null) return new DataItemMedium(accessor, item);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 实例字段介质：沿 target 类链按名找非静态字段（hidden class 场景必须——hidden 实例
         * 不是原始类的 isInstance，Class.forName(owner)+isInstance 校验恒 false；普通场景
         * target 链即声明链，等价。capability/外部宿主形态 v1 不追——链上找不到即弃）。
         */
        private Field resolveInstanceField(GateAnalyzer.StateCandidate sc) {
            if (sc.staticField) return null;
            try {
                for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(sc.name);
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        return f;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        // ==================== 死亡确认 ====================

        private static final java.lang.reflect.Field DEATH_TIME_FIELD =
            HealthUtil.reflectField(LivingEntity.class, "f_20919_", "deathTime");

        /**
         * 死亡确认（十轮收紧）：liveness 翻死 且 <b>容器/流程硬证据</b>（isRemoved 或 deathTime&gt;0）。
         * 九轮实测误报教训：LivingDeathEvent 是弱证据——die() 同栈早期就 post 事件（BossBar 清了、
         * 事件簿记都跑了），但 die 覆写可能中途拦截，实体没死（起源·本末终始：EXEC_COMBO 判成功
         * 1 秒后同一实体复活续战）；只认"实体进入移除/死亡流程"的硬证据，事件仅留作 despawn
         * 区分的观测记录不参与判定。
         */
        private boolean confirmDead() {
            if (!isDeadish(target)) return false;
            return target.isRemoved() || deathTime() > 0;
        }

        /**
         * 死亡判定：<b>只认对面自己的死亡证据</b>——isAlive（覆写版）翻死 / removed /
         * deathTime&gt;0（对面 die 流程启动）。不能用 isDeadOrDying：它的 health≤0 分量
         * 会被引擎 exhausted 兜底的 raw 写 0 污染（假血 Boss 原版槽被写 0 但真没死）。
         */
        private static boolean isDeadish(LivingEntity t) {
            return !t.isAlive() || t.isRemoved() || deathTimeOf(t) > 0;
        }

        private static int deathTimeOf(LivingEntity t) {
            if (DEATH_TIME_FIELD == null) return 0;
            try {
                return DEATH_TIME_FIELD.getInt(t);
            } catch (Exception e) {
                return 0;
            }
        }

        private int deathTime() {
            return deathTimeOf(target);
        }
    }
}
