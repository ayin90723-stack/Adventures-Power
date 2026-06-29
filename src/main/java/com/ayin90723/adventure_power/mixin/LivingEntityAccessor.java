package com.ayin90723.adventure_power.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 客户端 Mixin Accessor — 暴露原版 {@link LivingEntity#noJumpDelay} 字段。
 * <p>
 * 参照 AirHop 的做法，复用原版跳跃延迟机制来防止空中多段跳连发，
 * 而不是引入自定义冷却计时器。
 * </p>
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("noJumpDelay")
    int adp$getNoJumpDelay();

    @Accessor("noJumpDelay")
    void adp$setNoJumpDelay(int delay);
}
