package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.mixin.EntityFieldsAccessor;
import com.ayin90723.adventure_power.mixin.LivingEntityFieldsAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器抹除防线 · 守护线程（v1.3.9）—— 检测侧。
 * <p>
 * <b>设计（B 流派）</b>：守护线程<b>只读字段 + 置 volatile 标记</b>，修复写入
 * 全部由主线程（Mixin tick 自检 / ServerTickEvent 消费）执行 —— 无跨线程写、
 * 无竞态、无 Capability 跨线程访问。检测滞后只延迟修复、不误修复
 * （消费侧有 backup/reason 门禁兜底）。
 * <p>
 * <b>检测项</b>（每 20ms ≈ 50Hz，压过 50ms 主 tick）：
 * <ol>
 *   <li>{@link HealthUtil#readHealthForGuardian} —— 血量非法值（NaN/±Inf/负值），
 *       专用直读不降级</li>
 *   <li>{@link LivingEntityFieldsAccessor} —— dead/deathTime 字段直读</li>
 *   <li>{@link EntityFieldsAccessor} —— removalReason/valid/isAddedToWorld 直读</li>
 *   <li>{@link ClassPointerGuard#isReplaced} —— 类指针替换（纯读，线程安全）</li>
 *   <li>{@link HealthUtil#isMissingFromEntityLookup} —— 容器级抹除信号（byId 缺失）</li>
 * </ol>
 * <b>检测侧红线</b>：零 Capability 访问、零 MC 方法调用（level()/isRemoved() 等
 * 一律字段直读）、零写入。
 * <p>
 * <b>消费</b>：{@link #consume(Entity)} 由主线程取标记（返回位掩码并清除）。
 * 实体仍在 tick 时由 TrueHealthMixin tick 自检消费；实体被抹除不 tick 时由
 * {@code GuardianRepairHandler}（ServerTickEvent）消费。
 */
public final class GuardianThread {

    private static final Logger LOGGER = LoggerFactory.getLogger("adventure_power.guardian");

    // ==================== 标记位 ====================

    /** 血量非法（NaN/±Inf/负值直写） */
    public static final int BIT_HEALTH = 1;
    /** dead/deathTime 字段被直写 */
    public static final int BIT_STATE = 2;
    /** removalReason/valid/isAddedToWorld 字段被直写 */
    public static final int BIT_REMOVED = 4;
    /** 实体类指针被替换 */
    public static final int BIT_CLASS = 8;
    /** 从 EntityLookup 容器抹除（killEntity 链） */
    public static final int BIT_CONTAINER = 16;

    // ==================== 状态 ====================

    /** 登记表（弱 key：玩家登出/GC 后自动清除）。迭代必须包 synchronized 或先快照。 */
    private static final Map<Entity, Boolean> ADVENTURERS =
        Collections.synchronizedMap(new WeakHashMap<>());
    /** 待修复标记表：ConcurrentHashMap 弱一致迭代无 CME，消费后 remove */
    private static final Map<Entity, Integer> PENDING = new ConcurrentHashMap<>();

    private static volatile Thread thread;

    private GuardianThread() {
    }

    // ==================== 登记 / 注销 / 消费 ====================

    /**
     * 登记守护对象（主线程调用）：仅服务端 + 冒险者/觉醒玩家。
     * 首次登记时懒启动守护线程。
     */
    public static void register(Player player) {
        if (!(player instanceof ServerPlayer)) return;
        var progress = ProgressCache.get(player);
        if (progress == null) return;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
        ADVENTURERS.put(player, Boolean.TRUE);
        ensureStarted();
    }

    /** 注销（玩家登出时调用） */
    public static void unregister(Player player) {
        ADVENTURERS.remove(player);
        PENDING.remove(player);
    }

    /**
     * 主线程消费：取出并清除标记，返回位掩码（0 = 无待修复）。
     * 消费侧仍需自行执行 backup/reason 门禁（GuardianRepairHandler / TrueHealthMixin）。
     */
    public static int consume(Entity player) {
        Integer flags = PENDING.remove(player);
        return flags == null ? 0 : flags;
    }

    /** 检测循环是否空闲（无登记玩家且无待修复标记）—— 供外部观察/测试。
     *  双查：ADVENTURERS 空但 PENDING 残留时仍需消费（L-1 防护） */
    public static boolean isEmpty() {
        return ADVENTURERS.isEmpty() && PENDING.isEmpty();
    }

    /**
     * 主线程遍历待修复表（ConcurrentHashMap 弱一致迭代，无 CME）。
     * 消费后必须调用 {@link #consume(Entity)} 清理标记。
     */
    public static Set<Map.Entry<Entity, Integer>> pendingEntries() {
        return PENDING.entrySet();
    }

    // ==================== 线程生命周期 ====================

    private static void ensureStarted() {
        Thread t = thread;
        if (t != null && t.isAlive()) return;
        synchronized (GuardianThread.class) {
            t = thread;
            if (t != null && t.isAlive()) return;
            Thread nt = new Thread(GuardianThread::runLoop, "AdventurePower-Guardian");
            nt.setDaemon(true);
            nt.setPriority(Thread.MIN_PRIORITY);
            nt.setUncaughtExceptionHandler((th, ex) -> {
                LOGGER.error("[GuardianThread] 守护线程异常终止，重建", ex);
                try {
                    // 重建退避（L-2 防护）：runLoop 静态初始化即异常时防止无限快速重建刷日志
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                synchronized (GuardianThread.class) {
                    if (thread == th) {
                        thread = null;
                    }
                }
                ensureStarted();
            });
            thread = nt;
            nt.start();
        }
    }

    // ==================== 检测循环 ====================

    private static void runLoop() {
        while (true) {
            try {
                boolean empty = detectOnce();
                Thread.sleep(empty ? 500 : 20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // 循环级兜底：单轮失败不退出线程
                LOGGER.error("[GuardianThread] 检测轮异常", t);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 一轮检测：返回登记表是否为空（决定退避间隔） */
    private static boolean detectOnce() {
        Entity[] snapshot;
        synchronized (ADVENTURERS) {
            if (ADVENTURERS.isEmpty()) return true;
            snapshot = ADVENTURERS.keySet().toArray(new Entity[0]);
        }
        for (Entity p : snapshot) {
            if (p == null || !(p instanceof ServerPlayer sp)) continue;
            int flags = 0;

            // ① 血量非法（专用直读，句柄不可用返回 NaN 跳过）
            float h = HealthUtil.readHealthForGuardian(sp);
            if (Float.isNaN(h) || Float.isInfinite(h) || h < 0.0F) {
                flags |= BIT_HEALTH;
            }

            // ② dead/deathTime 字段直读
            LivingEntityFieldsAccessor fields = (LivingEntityFieldsAccessor) (Object) sp;
            if (fields.adventure_power$isDead() || fields.adventure_power$getDeathTime() > 0) {
                flags |= BIT_STATE;
            }

            // ③ removalReason / isAddedToWorld 字段直读
            //    （1.20.1 Forge 无 Entity.valid 字段——敌方模组引用的 valid 在其
            //    运行环境才存在；容器状态判定以 removalReason + isAddedToWorld + byId 为准）
            EntityFieldsAccessor efields = (EntityFieldsAccessor) (Object) sp;
            if (efields.adventure_power$getRemovalReason() != null
                || !efields.adventure_power$isAddedToWorld()) {
                flags |= BIT_REMOVED;
            }

            // ④ 类指针替换（纯读 + 比较，线程安全）
            if (ClassPointerGuard.isReplaced(sp)) {
                flags |= BIT_CLASS;
            }

            // ⑤ 容器级抹除信号：byId 表缺失（正常玩家恒在表中）
            if (HealthUtil.isMissingFromEntityLookup(sp)) {
                flags |= BIT_CONTAINER;
            }

            if (flags != 0) {
                PENDING.put(sp, flags);
            }
        }
        return false;
    }
}
