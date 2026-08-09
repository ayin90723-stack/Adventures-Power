package com.ayin90723.adventure_power.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link LivingEntity#dead}（SRG f_20890_）与 {@link LivingEntity#deathTime}
 * （SRG f_20919_）的 Accessor 接口。
 * <p>
 * 供 {@link TrueHealthMixin} 的 tick 存活性自检直接读/写死亡状态字段——
 * 这两个字段是 protected，且 Mixin @Shadow 字段的生产环境映射依赖 refmap 的
 * 字段通道（在 Mixin 0.8.5 + Forge 生产环境验证不可靠），改用已验证可用的
 * @Accessor 接口通道（与 {@link LootPoolAccessor} 同模式）。
 * <p>
 * 通过 Mixin 接口注入，使 {@link LivingEntity} 实现此接口，其他 Mixin 类
 * 通过 {@code ((LivingEntityFieldsAccessor) entity).adventure_power$...()} 访问。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityFieldsAccessor {

    @Accessor("dead")
    boolean adventure_power$isDead();

    @Accessor("dead")
    void adventure_power$setDead(boolean dead);

    @Accessor("deathTime")
    int adventure_power$getDeathTime();

    @Accessor("deathTime")
    void adventure_power$setDeathTime(int deathTime);
}
