package com.rosycentury.adventure_power.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

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
}
