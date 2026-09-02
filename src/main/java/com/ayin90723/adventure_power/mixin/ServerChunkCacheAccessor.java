package com.ayin90723.adventure_power.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link ServerChunkCache#chunkMap}（SRG f_8325_）的 Accessor 接口
 * （v1.4.9 容器重建链）。
 * <p>
 * {@code ServerLevel.getChunkSource()} 协变返回 {@code ServerChunkCache}（公共 API），
 * 本 accessor 补最后一跳：chunkMap 字段包私有，追踪表 {@code ChunkMap.entityMap}
 * （审计项 A7）的入口。
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {

    @Accessor("chunkMap")
    ChunkMap adventure_power$getChunkMap();
}
