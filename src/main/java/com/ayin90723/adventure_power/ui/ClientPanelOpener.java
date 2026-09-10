package com.ayin90723.adventure_power.ui;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.client.Minecraft;

/**
 * P 键面板客户端打开动作（v1.4.9.4 dist 隔离拆出）。
 * <p>
 * 开面板代码原寄居在 {@code AdventureProgressCapability.tryOpenPendingScreen()} 内，
 * 以全限定名直接引用 Minecraft/AdventureMainScreen——该类是双端 {@code @EventBusSubscriber}
 * 注解类，专用服务器上会被 AutomaticEventSubscriber 以 initialize=true 强制加载，
 * 方法体里的客户端类引用在类链接阶段即触发 {@code Screen} 加载，被 RuntimeDistCleaner
 * 拒绝导致模组加载崩溃（GraalVM 21 专用服务器实报）。拆出本类后：双端类字节码零客户端
 * 引用，本类只在客户端代码路径（进度同步包 handler）被触达，服务端永不加载。
 */
public final class ClientPanelOpener {

    private ClientPanelOpener() {
    }

    /** 按 pendingScreen 类型打开对应标签的冒险统一面板（仅客户端调用，无玩家上下文时静默跳过） */
    public static void open(int type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (type == AdventureProgressCapability.PENDING_BUFF) {
            mc.setScreen(new AdventureMainScreen(AdventureMainScreen.Tab.BUFF));
        } else if (type == AdventureProgressCapability.PENDING_ABILITY) {
            mc.setScreen(new AdventureMainScreen());
        } else if (type == AdventureProgressCapability.PENDING_MILESTONE) {
            mc.setScreen(new AdventureMainScreen(AdventureMainScreen.Tab.MILESTONE));
        }
    }
}
