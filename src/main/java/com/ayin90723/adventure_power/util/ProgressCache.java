package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 高频注入点的 per-tick 门禁缓存。
 * <p>
 * 背景：TrueHealthMixin.getHealth() / RejectHealthManipMixin.getAttribute() /
 * DeathDefyMixin.setHealth() 等注入点位于 MC 极高频调用路径（getHealth 每 tick
 * 每玩家数十次），每次调用都做 LazyOptional.resolve()（内部带锁 + Optional 分配），
 * 且对未解锁相关能力的玩家也付全价。
 * <p>
 * 方案：以实体为弱 key 缓存「本 tick 已解析的 progress 引用」，tick 变化即失效重算。
 * 每玩家每 tick 至多 resolve 一次，命中后直接复用引用（门禁位图查询远便宜于 resolve）。
 * 弱 key 保证玩家实体被 GC 后缓存条目自动释放，无内存泄漏（配合实体登出/移除清理）。
 * <p>
 * 一致性：能力开关/里程碑变更最迟 1 tick 内生效，与事件层同步节奏一致。
 * 线程安全：Minecraft 主线程（服务端/客户端）单线程调用为主，synchronized 仅作防御。
 */
public final class ProgressCache {

    private static final Map<Entity, Entry> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Entry {
        volatile long tick; // 构造后不再变更；volatile 保证 synchronized 块外读取可见
        final IAdventureProgress progress; // null = 本 tick 已检查且未附加 capability
        Entry(long tick, IAdventureProgress progress) {
            this.tick = tick;
            this.progress = progress;
        }
    }

    private ProgressCache() {}

    /**
     * 获取实体本 tick 的 progress 引用。非玩家 / 未附加 capability / level 未初始化 时返回 null。
     */
    public static IAdventureProgress get(Entity entity) {
        if (entity.level() == null) return null;
        long tick = entity.level().getGameTime();

        Entry entry;
        synchronized (CACHE) {
            entry = CACHE.get(entity);
        }
        if (entry != null && entry.tick == tick) return entry.progress;

        IAdventureProgress progress = null;
        if (entity instanceof Player player) {
            progress = AdventureProgressCapability.getAdventureProgress(player).orElse(null);
        }
        synchronized (CACHE) {
            CACHE.put(entity, new Entry(tick, progress));
        }
        return progress;
    }
}
