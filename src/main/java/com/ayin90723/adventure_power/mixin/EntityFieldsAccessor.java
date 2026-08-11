package com.ayin90723.adventure_power.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link Entity#removalReason}（SRG f_146795_）与 Forge 补丁字段
 * {@code isAddedToWorld} 的 Accessor 接口。
 * <p>
 * 供守护线程检测侧与 {@link TrueHealthMixin} tick 自检直接读/写"是否被外部
 * 从世界容器抹除"的字段——与 {@link LivingEntityFieldsAccessor} 同模式
 * （@Accessor 接口通道，生产环境经 refmap 映射）。
 * <p>
 * 字段说明：
 * <ul>
 *   <li>{@code removalReason}：原版字段，SRG f_146795_，refmap 条目已维护</li>
 *   <li>{@code isAddedToWorld}：<b>Forge 补丁字段</b>（1.20.1 原版不存在；
 *       原版/Forge 编译期 AP 会报 "Unable to locate obfuscation mapping" 警告，
 *       无害——Forge 补丁字段不做 SRG 化，生产环境保持官方名，无需 refmap 条目）。
 *       注意 1.20.1 中该字段为 private（经 public getter 暴露），@Accessor 可直读。
 *       终极骷髅等模组通过直写它标记"实体不在世界中"</li>
 * </ul>
 * <b>注意</b>：敌方模组还引用 {@code Entity.valid} 字段——1.20.1 Forge
 * （47.4.10）<b>不存在</b>该字段（对方运行环境可能为其他 Forge 版本），
 * 故不提供对应 Accessor，容器状态判定以 removalReason + isAddedToWorld +
 * EntityLookup.byId 缺失（HealthUtil.isMissingFromEntityLookup）为准。
 */
@Mixin(Entity.class)
public interface EntityFieldsAccessor {

    @Accessor("removalReason")
    Entity.RemovalReason adventure_power$getRemovalReason();

    @Accessor("removalReason")
    void adventure_power$setRemovalReason(Entity.RemovalReason reason);

    @Accessor("isAddedToWorld")
    boolean adventure_power$isAddedToWorld();

    @Accessor("isAddedToWorld")
    void adventure_power$setAddedToWorld(boolean addedToWorld);
}
