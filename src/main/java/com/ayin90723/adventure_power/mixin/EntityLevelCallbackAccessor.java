package com.ayin90723.adventure_power.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link Entity#levelCallback}（SRG f_146801_）的 Accessor 接口（v1.4.9 容器重建链）。
 * <p>
 * 审计项 A1 读取 / 重建尾步装回用。{@code onRemove} 清理序列的最后一步是
 * {@code setLevelCallback(NULL)}——callback 被拧掉后实体跨 section 移动的
 * {@code onMove → updateStatus} 链永久断裂，审计-重建链以本 accessor 补回。
 * 与 {@link EntityFieldsAccessor} 同模式（@Accessor 接口通道，生产环境经 refmap 映射）。
 */
@Mixin(Entity.class)
public interface EntityLevelCallbackAccessor {

    @Accessor("levelCallback")
    EntityInLevelCallback adventure_power$getLevelCallback();

    @Accessor("levelCallback")
    void adventure_power$setLevelCallback(EntityInLevelCallback callback);
}
