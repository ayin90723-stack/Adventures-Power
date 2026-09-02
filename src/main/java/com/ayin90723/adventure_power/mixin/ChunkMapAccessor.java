package com.ayin90723.adventure_power.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link ChunkMap#entityMap}（SRG f_140150_，
 * {@code Int2ObjectMap<ChunkMap$TrackedEntity>}）的 Accessor 接口（v1.4.9 容器重建链）。
 * <p>
 * 审计项 A7：追踪条目存在即算健康（玩家不追踪自己，seenBy 只装其他在线玩家，
 * 单人局恒空——"seenBy 非空"判据在默认单人环境恒假会造成无限重建循环，二轮评审修订）。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("entityMap")
    Int2ObjectMap<Object> adventure_power$getEntityMap();
}
