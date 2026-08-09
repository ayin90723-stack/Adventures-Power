package com.ayin90723.adventure_power.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link SynchedEntityData#entity}（SRG f_135344_）的 Accessor 接口。
 * <p>
 * 供 {@link RejectHealthManipDataMixin} 获取数据条目所有者实体——Mixin
 * @Shadow 字段的生产环境映射依赖 refmap 的字段通道（在 Mixin 0.8.5 + Forge
 * 生产环境验证不可靠），改用已验证可用的 @Accessor 接口通道。
 * <p>
 * 通过 Mixin 接口注入，使 {@link SynchedEntityData} 实现此接口，其他 Mixin 类
 * 通过 {@code ((SynchedEntityDataAccessor) data).adventure_power$getEntity()} 访问。
 */
@Mixin(SynchedEntityData.class)
public interface SynchedEntityDataAccessor {

    @Accessor("entity")
    Entity adventure_power$getEntity();
}
