package com.ayin90723.adventure_power.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link ServerLevel} 容器字段（SRG f_143243_ / f_143244_）的 Accessor 接口
 * （v1.4.9 容器重建链）。
 * <p>
 * {@code entityTickList}：tick 资格表（审计项 A6 / 重建第 4 步直塞——{@code add} 无
 * isRemoved 门禁，不必经 onTickingStart）。
 * {@code entityManager}：PESM 实体管理器（审计 A3/A4/A5 与重注册全链的入口）。
 * <p>
 * {@code players} / {@code chunkSource} 两个字段<b>不设</b> accessor——
 * {@code ServerLevel.players()}（public，直返字段本体）与 {@code getChunkSource()}
 * （public，协变返回 ServerChunkCache）均为公共 API（javap 核实），直接调用。
 */
@Mixin(ServerLevel.class)
public interface ServerLevelContainerAccessor {

    @Accessor("entityTickList")
    EntityTickList adventure_power$getEntityTickList();

    @Accessor("entityManager")
    PersistentEntitySectionManager<?> adventure_power$getEntityManager();
}
