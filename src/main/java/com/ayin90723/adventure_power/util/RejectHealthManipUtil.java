package com.ayin90723.adventure_power.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拒绝篡改 —— 工具类，存放 {@code RejectHealthManipMixin}
 * 与 {@code RejectHealthManipAttributeMixin} 之间的共享状态。
 * <p>
 * Mixin 验证器禁止 {@code @Mixin} 类中有非 private 的 static 成员，
 * 因此将跨 Mixin 引用的共享数据分离到此独立工具类中。
 */
public class RejectHealthManipUtil {

    /** AttributeInstance → 所属 LivingEntity 映射（供 AttributeMixin 查询所有者） */
    public static final Map<AttributeInstance, LivingEntity> ATTR_OWNER = new ConcurrentHashMap<>();

    /** 查询 AttributeInstance 的所有者 */
    public static LivingEntity getOwner(AttributeInstance instance) {
        return ATTR_OWNER.get(instance);
    }

    /**
     * 玩家登出时清理该玩家的 ATTR_OWNER 条目。
     * <p>
     * 冗余兜底：1.20.1 登出链 PlayerList.remove → removePlayerImmediately(UNLOADED_WITH_PLAYER)
     * → Entity.remove 必然触发 {@code RejectHealthManipMixin.cleanupOnRemoval}（主要清理路径）；
     * ATTR_OWNER 的 value 是 LivingEntity 强引用，本方法保证即使 remove 链被外部
     * Mixin 拦截，ServerPlayer 对象仍可被 GC。
     */
    public static void clearOwner(Player player) {
        ATTR_OWNER.values().removeIf(v -> v == player);
    }
}
