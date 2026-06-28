package com.rosycentury.adventure_power.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@link LivingEntity#actuallyHurt(DamageSource, float)} 的 Accessor 接口。
 * <p>
 * 通过 Mixin 接口注入，使 {@link LivingEntity} 实现此接口，
 * 其他 Mixin 类可以通过 {@code ((SeeAndSlashLivingEntityAccessor) living).invokeActuallyHurt(...)}
 * 安全地调用实际伤害方法，无需反射或跨 Mixin 强转。
 *
 * @see SeeAndSlashLivingEntityMixin
 * @see SeeAndSlashPlayerAttackMixin
 */
@Mixin(LivingEntity.class)
public interface SeeAndSlashLivingEntityAccessor {

    /**
     * 直接造成最终伤害，不经过护甲/附魔减伤/无敌判定。
     */
    @Invoker("actuallyHurt")
    void invokeActuallyHurt(DamageSource source, float amount);
}
