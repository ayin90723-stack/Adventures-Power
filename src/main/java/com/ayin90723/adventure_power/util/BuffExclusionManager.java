package com.ayin90723.adventure_power.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buff 排除管理 - 玩家可在统一面板（Buff永驻标签）把不想被恩赐永驻续期的正面效果加入黑名单。
 * <p>
 * 黑名单存在玩家 persistentData 的 {@link PersistentDataKeys#BUFF_BLACKLIST_KEY} 下，跨死亡持久。
 * 运行时按 UUID 缓存解析结果，避免每 60 tick 从 NBT 重新解析；toggle 时失效，登出时清理。
 */
public final class BuffExclusionManager {

    public static final String BUFF_BLACKLIST_KEY = PersistentDataKeys.BUFF_BLACKLIST_KEY;

    /** 运行时缓存（按 UUID），toggle 时失效，登出时清理 */
    private static final Map<UUID, Set<String>> CACHE = new ConcurrentHashMap<>();

    private BuffExclusionManager() {}

    /** 切换某效果 ID 的排除状态（加入/移除黑名单） */
    public static void toggleBuffExclusion(Player player, String effectId) {
        CompoundTag root = player.getPersistentData();
        CompoundTag blacklist = root.getCompound(BUFF_BLACKLIST_KEY);
        if (blacklist.getBoolean(effectId)) {
            blacklist.remove(effectId);
        } else {
            blacklist.putBoolean(effectId, true);
        }
        if (blacklist.isEmpty()) {
            root.remove(BUFF_BLACKLIST_KEY);
        } else {
            root.put(BUFF_BLACKLIST_KEY, blacklist);
        }
        CACHE.remove(player.getUUID());
    }

    /**
     * 获取玩家排除列表。优先读运行时缓存，miss 时从 persistent data 解析并缓存。
     */
    public static Set<String> getBuffExclusionSet(Player player) {
        UUID uuid = player.getUUID();
        Set<String> cached = CACHE.get(uuid);
        if (cached != null) return cached;
        Set<String> set = Collections.unmodifiableSet(parseFromNbt(player));
        CACHE.put(uuid, set);
        return set;
    }

    private static Set<String> parseFromNbt(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(BUFF_BLACKLIST_KEY)) return Set.of();
        CompoundTag blacklist = root.getCompound(BUFF_BLACKLIST_KEY);
        Set<String> set = new HashSet<>();
        for (String key : blacklist.getAllKeys()) {
            // 长度校验（v1.4.0 审查修复）：网络同步层 readUtf(64) 限长，此处源头对齐——
            // 外部途径（手改存档）写入的超长 key 不进入运行时集合，防同步不对称
            if (key.length() > 64) continue;
            if (blacklist.getBoolean(key)) set.add(key);
        }
        return set;
    }

    /** 玩家登出时清理缓存（由 onPlayerLogout 调用，防内存泄漏） */
    public static void clearCache(UUID uuid) {
        CACHE.remove(uuid);
    }
}
