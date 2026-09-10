package com.ayin90723.adventure_power.network;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.ui.AdventureMainScreen;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.util.Set;

/**
 * 网络包客户端应用动作（v1.4.9.4 dist 隔离拆出）。
 * <p>
 * 两个 S2C 包（Buff 黑名单同步 / 冒险进度同步）的 handler lambda 原直接引用
 * Minecraft/AdventureMainScreen/LocalPlayer——包类在服务端注册网络时即被加载，
 * 字节码里的客户端类引用与 {@code AdventureProgressCapability} 同属一类 dist 隐患
 * （专用服务器类链接期可能被触发解析）。拆出本类后：NetworkHandler 字节码零客户端
 * 引用，本类只在客户端方向的 handler 执行路径被触达，服务端永不加载。
 */
public final class ClientPacketActions {

    private ClientPacketActions() {
    }

    /** Buff 黑名单同步包（S2C）：面板已打开时刷新其黑名单页 */
    public static void applyBuffBlacklist(Set<String> blacklist) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AdventureMainScreen screen) {
            screen.onSyncReceived(blacklist);
        }
    }

    /** 冒险进度同步包（S2C）：里程碑注册表元数据初始化 + 进度反序列化 + 待开面板兜底 */
    public static void applyAdventureSync(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // 先提取里程碑注册表元数据初始化客户端 MilestoneRegistry（直接 NBT 构建，不经 JSON 中转）
        if (data.contains("_milestone_registry")) {
            MilestoneRegistry.clientInitFromNbt(data.getCompound("_milestone_registry"));
        }
        mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(
            progress -> progress.deserializeNBT(data));
        // 如果有等待同步后打开的屏幕，现在打开
        AdventureProgressCapability.tryOpenPendingScreen();
    }
}
