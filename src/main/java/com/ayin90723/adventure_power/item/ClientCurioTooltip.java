package com.ayin90723.adventure_power.item;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * 饰品 tooltip 客户端辅助（v1.4.9.4 dist 隔离拆出）。
 * <p>
 * {@code AdventureCurioItem.addMilestoneLines} 原直接调用
 * {@code Minecraft.getInstance().player}——物品类在服务端注册物品时即被加载，
 * 字节码里的客户端类引用与 {@code AdventureProgressCapability} 同属一类 dist 隐患
 * （专用服务器类链接期可能被触发解析）。拆出本类后：物品类字节码零客户端引用，
 * 本类只在客户端 tooltip 装配路径被触达，服务端永不加载。
 */
public final class ClientCurioTooltip {

    private ClientCurioTooltip() {
    }

    /** 本地玩家（tooltip 进度行装配用；无玩家上下文场景返回 null） */
    public static Player localPlayer() {
        return Minecraft.getInstance().player;
    }
}
