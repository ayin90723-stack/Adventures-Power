package com.ayin90723.adventure_power.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;

/**
 * Buff 排除管理 - 玩家可在统一面板（Buff永驻标签）把不想被恩赐永驻续期的正面效果加入黑名单。
 * <p>
 * 黑名单存在玩家 persistentData 的 {@link PersistentDataKeys#BUFF_BLACKLIST_KEY} 下，跨死亡持久。
 */
public final class BuffExclusionManager {

    public static final String BUFF_BLACKLIST_KEY = PersistentDataKeys.BUFF_BLACKLIST_KEY;

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
    }

    /**
     * 获取玩家排除列表。每次调用从 persistent data 重新解析（集合通常很小，性能影响可忽略）。
     */
    public static Set<String> getBuffExclusionSet(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(BUFF_BLACKLIST_KEY)) return Set.of();
        CompoundTag blacklist = root.getCompound(BUFF_BLACKLIST_KEY);
        Set<String> set = new HashSet<>();
        for (String key : blacklist.getAllKeys()) {
            if (blacklist.getBoolean(key)) set.add(key);
        }
        return set;
    }
}
