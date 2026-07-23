package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.network.NetworkHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

/**
 * 数据同步工具 - Capability 持久化同步 + 客户端同步。
 * <p>
 * 从 AdventureProgressCapability 拆出，被 grantMilestone / 各 handler / AdventureItemNbtUtil 等共用，
 * 消除散落的 sync 调用样板。
 */
public final class SyncUtil {

    private static final String PERSISTENT_KEY = PersistentDataKeys.ADVENTURE_PROGRESS;

    private SyncUtil() {}

    /**
     * Capability -> persistent data 显式同步。
     * 注意：Forge 通过 ICapabilitySerializable 也会自动持久化，此处是额外的安全措施，
     * 确保在 Capability 自动保存周期之前关键数据已被写入。
     */
    public static void syncCapabilityToPersistent(Player player, IAdventureProgress progress) {
        player.getPersistentData().put(PERSISTENT_KEY, progress.serializeNBT());
    }

    /** 向指定玩家同步冒险进度 Capability 数据到客户端（含里程碑注册表元数据） */
    public static void syncToClient(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            CompoundTag syncData = progress.serializeNBT();
            // 附带里程碑注册表元数据给客户端（用于 tooltip 渲染 + 能力可用性判断）
            List<Milestone> all = MilestoneRegistry.getAll();
            CompoundTag registryMeta = new CompoundTag();
            registryMeta.putInt("count", all.size());
            for (int i = 0; i < all.size(); i++) {
                Milestone m = all.get(i);
                CompoundTag mTag = new CompoundTag();
                mTag.putString("id", m.id());
                mTag.putString("name", m.name());
                // 传递 abilities 列表，让客户端能正确判断能力可用性
                CompoundTag abilitiesTag = new CompoundTag();
                List<String> abilityList = m.abilities();
                for (int j = 0; j < abilityList.size(); j++) {
                    abilitiesTag.putString("a_" + j, abilityList.get(j));
                }
                abilitiesTag.putInt("count", abilityList.size());
                mTag.put("abilities", abilitiesTag);
                // 传递 advancement 和 trigger，供客户端 UI 显示解锁条件
                if (m.advancement() != null) mTag.putString("advancement", m.advancement().toString());
                if (m.trigger() != null) {
                    CompoundTag trigTag = new CompoundTag();
                    trigTag.putString("type", m.trigger().type());
                    if (m.trigger().y() != null) trigTag.putInt("y", m.trigger().y());
                    if (m.trigger().entity() != null) trigTag.putString("entity", m.trigger().entity().toString());
                    mTag.put("trigger", trigTag);
                }
                registryMeta.put("m_" + i, mTag);
            }
            syncData.put("_milestone_registry", registryMeta);
            NetworkHandler.INSTANCE.send(
                PacketDistributor.PLAYER.with(() -> sp),
                new NetworkHandler.AdventureSyncPacket(syncData)
            );
        });
    }
}
