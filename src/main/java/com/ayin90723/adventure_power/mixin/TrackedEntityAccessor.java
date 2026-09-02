package com.ayin90723.adventure_power.mixin;

import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * 暴露 {@code ChunkMap$TrackedEntity.seenBy}（SRG f_140475_，
 * {@code Set<ServerPlayerConnection>}）的 Accessor 接口（v1.4.9 容器重建链）。
 * <p>
 * 目标类是包私有内部类，只能用 targets 字符串形式（v1.3.10 踩坑先例）。
 * <p>
 * <b>纯诊断用途</b>：seenBy 只装"正在看到该实体的其他玩家的连接"，玩家不追踪自己、
 * 单人局恒空——不参与健康判定（二轮评审），仅随审计日志输出观察者数，用于重建后
 * "客户端失联是否恢复"的排障参考。读取失败（refmap 不生效等）静默跳过，不影响主审计。
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {

    @Accessor("seenBy")
    Set<ServerPlayerConnection> adventure_power$getSeenBy();
}
