package com.ayin90723.adventure_power.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link EntitySectionStorage#sections}（SRG f_156852_，
 * {@code Long2ObjectMap<EntitySection<T>>}）的 Accessor 接口（v1.4.9 容器重建链）。
 * <p>
 * 审计项 A5 直读当前坐标的 EntitySection（与公共方法 {@code getOrCreateSection} 的区别：
 * 只读不创建——审计零副作用纪律）。
 */
@Mixin(EntitySectionStorage.class)
public interface EntitySectionStorageAccessor {

    @Accessor("sections")
    Long2ObjectMap<EntitySection<?>> adventure_power$getSections();
}
