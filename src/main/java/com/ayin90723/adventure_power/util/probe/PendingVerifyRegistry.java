package com.ayin90723.adventure_power.util.probe;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.util.DebugLog;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 探针域 tick 挂起任务注册表（v1.4.3，docs/gate-oracle-proposal.md §6.1）。
 * <p>
 * 「一张 pending 表 + 两类回调」的统一载体——多存储合成血的<b>下 tick 复验对账</b>与
 * GateOracle 轮询型的<b>有界等待裁决</b>（后续接入）共用本表，不写两套：
 * <ul>
 *   <li><b>复验对账</b>（{@code MultiStoreWriter}）：写入同栈验证通过后，对面 Boss 的
 *       tick 对账可能在下一 tick 把派生分量重算回刷（太阳神使 0.01↔3.6 震荡实证）——
 *       同栈验证与下 tick 复验不矛盾，前者只证明"写进去了"，后者证明"写得住"。</li>
 *   <li><b>等待裁决</b>（GateOracle，1.4.3 后续）：轮询型许可写入后挂起，有界窗口内
 *       观测目标是否走正规 die，超时退影杀兜底。</li>
 * </ul>
 * 驱动源：ServerTick END（实体 tick 全部完成后）——挂起任务关心的都是
 * 「对面下一轮 tick 之后世界状态如何」，END 相位保证当 tick 的全部写入与对账可见。
 * <p>
 * 线程模型：全部主线程（register 发生在引擎写入调用栈内，tick 也在主线程），
 * CopyOnWriteArrayList 仅防御性使用（迭代中 unregister 安全）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PendingVerifyRegistry {

    private PendingVerifyRegistry() {
    }

    /** 任务归属：同一实体可同时挂复验对账与 GateOracle 等待任务，级联清理按归属只删自己的（防误删对方的等待裁决任务）。 */
    public enum TaskKind { REVERIFY, GATE }

    /**
     * 挂起任务：到达等待窗口时由 tick 驱动调用 {@link #onVerify}。
     * <p>
     * 返回 true = 任务完成（自然移除）；返回 false = 验证失败（先调 {@link #onFail} 再移除）。
     * 等待期间目标死亡/移除时任务提前完成并调用 {@link #onDead}（弱引用仍存活的短暂窗口内）——
     * 对等待裁决型（GateOracle 轮询）这本身就是成功信号；复验对账型默认静默。
     */
    public interface PendingTask {
        boolean onVerify(LivingEntity target);

        default void onFail(LivingEntity target) {
        }

        /** 目标在等待窗口内死亡/移除（提前成功或自然终结）。 */
        default void onDead(LivingEntity target) {
        }

        /** 目标在裁决前被 GC/卸载（非死亡路径，不发 LivingDeathEvent）——任务自带的状态
         * 清理（如 GateOracle 的 OPENING 残留、死序列候选还原）在此收尾，防静默丢失。 */
        default void onCancel() {
        }
    }

    private static final class Entry {
        final WeakReference<LivingEntity> ref;
        /** 距裁决还剩的 ServerTick END 计数（每 END 相位减 1，到 0 裁决）。 */
        int remaining;
        final PendingTask task;
        /** 任务归属（cancelAll 按归属级联清理，不跨类型误删）。 */
        final TaskKind kind;

        Entry(WeakReference<LivingEntity> ref, int remaining, PendingTask task, TaskKind kind) {
            this.ref = ref;
            this.remaining = remaining;
            this.task = task;
            this.kind = kind;
        }
    }

    private static final CopyOnWriteArrayList<Entry> PENDING = new CopyOnWriteArrayList<>();

    /**
     * 登记挂起任务：delayTicks 个 ServerTick END 后裁决。
     * <p>
     * delayTicks=1 即「<b>当 tick END 裁决</b>」：写入发生在实体 tick 处理链内时，当 tick 的
     * END 在对面下一轮 tick 之前到达——裁决时对面尚未跑下一轮对账。需要观测「对面下一轮
     * tick 之后状态」的复验任务必须用 delay≥2（MultiStoreWriter 复验对账即 delay=2，
     * 太阳神使 0.01 震荡回刷漏检实证）。
     */
    public static void register(LivingEntity target, int delayTicks, PendingTask task) {
        register(target, delayTicks, TaskKind.REVERIFY, task);
    }

    public static void register(LivingEntity target, int delayTicks, TaskKind kind, PendingTask task) {
        if (target == null || task == null || delayTicks < 1) return;
        PENDING.add(new Entry(new WeakReference<>(target), delayTicks, task, kind));
    }

    /** 丢弃某实体指定归属的全部挂起任务（复验失败级联清理用——GateOracle 等待任务不在此列）。 */
    public static void cancelAll(LivingEntity target, TaskKind kind) {
        if (target == null || kind == null) return;
        PENDING.removeIf(e -> e.ref.get() == target && e.kind == kind);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent event) {
        if (event.phase != Phase.END) return;
        if (PENDING.isEmpty()) return;
        Iterator<Entry> it = PENDING.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            LivingEntity target = e.ref.get();
            if (target == null) {
                PENDING.remove(e);
                // 审查修：弱引用死亡（GC/区块卸载等非死亡路径，不发 LivingDeathEvent）静默移除
                // 会让任务自带的状态清理丢失（GateOracle OPENING 残留 → 同 UUID 重载后 tryOpen
                // 恒 PENDING）——通知 onCancel 让任务收尾
                try {
                    e.task.onCancel();
                } catch (Exception ignored) {
                }
                continue;
            }
            if (e.remaining > 1) {
                e.remaining--;
                continue;
            }
            PENDING.remove(e);
            // 已移除实体：处决成功路径当 tick 抹除 / 轮询等待期间目标死亡——通知 onDead 后视为完成
            if (target.isRemoved() || !target.isAlive()) {
                try {
                    e.task.onDead(target);
                } catch (Exception ignored) {
                }
                continue;
            }
            boolean pass;
            try {
                pass = e.task.onVerify(target);
            } catch (Exception ex) {
                DebugLog.probe("[pending] 挂起任务异常（按失败处理）: {}", ex.toString());
                pass = false;
            }
            if (!pass) {
                try {
                    e.task.onFail(target);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
