package com.ayin90723.adventure_power.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link Entity#removalReason}（SRG f_146795_）的 Accessor 接口。
 * <p>
 * 供 {@link TrueHealthMixin} tick 自检直读"是否被外部标记移除"的字段
 * （EntityLivenessMixin 对真血玩家强制 isRemoved()=false，tick 自检必须
 * 直读字段而非 isRemoved()）——与 {@link LivingEntityFieldsAccessor} 同模式
 * （@Accessor 接口通道，生产环境经 refmap 映射）。
 * <p>
 * <b>注意</b>：敌方模组还引用 {@code Entity.valid} 字段——1.20.1 Forge
 * （47.4.10）<b>不存在</b>该字段（对方运行环境可能为其他 Forge 版本），
 * 故不提供对应 Accessor。v1.3.9-fix 起移除 isAddedToWorld 暴露（Forge 补丁
 * 字段，原容器抹除防线恢复链已删除，无调用方）。
 */
@Mixin(Entity.class)
public interface EntityFieldsAccessor {

    @Accessor("removalReason")
    Entity.RemovalReason adventure_power$getRemovalReason();

    @Accessor("removalReason")
    void adventure_power$setRemovalReason(Entity.RemovalReason reason);
}
