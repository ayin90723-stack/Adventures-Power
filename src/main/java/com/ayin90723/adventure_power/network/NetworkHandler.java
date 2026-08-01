package com.ayin90723.adventure_power.network;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.handler.PlayerStateHandler;
import com.ayin90723.adventure_power.util.BuffExclusionManager;
import com.ayin90723.adventure_power.util.SyncUtil;
import com.ayin90723.adventure_power.input.DoubleJumpHandler;
import com.ayin90723.adventure_power.skill.ActiveSkillHandler;
import com.ayin90723.adventure_power.ui.AdventureMainScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 网络包处理器。
 * 处理二段跳、Buff 管理、能力切换、冒险进度同步、主动技能等所有客户端↔服务端通信。
 */
public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("adventure_power", "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        // 0: AdventureSync — 服务端→客户端：冒险进度 Capability 同步
        INSTANCE.registerMessage(packetId++, AdventureSyncPacket.class,
            AdventureSyncPacket::encode, AdventureSyncPacket::decode,
            AdventureSyncPacket::handle);
        // 1: DoubleJump — 客户端→服务端：二段跳请求
        INSTANCE.registerMessage(packetId++, DoubleJumpPacket.class,
            DoubleJumpPacket::encode, DoubleJumpPacket::decode,
            DoubleJumpPacket::handle);
        // 2: BuffToggle — 客户端→服务端：切换 Buff 排除状态
        INSTANCE.registerMessage(packetId++, BuffTogglePacket.class,
            BuffTogglePacket::encode, BuffTogglePacket::decode,
            BuffTogglePacket::handle);
        // 3: BuffBlacklistSync — 双向：请求/同步 Buff 排除列表
        INSTANCE.registerMessage(packetId++, BuffBlacklistSyncPacket.class,
            BuffBlacklistSyncPacket::encode, BuffBlacklistSyncPacket::decode,
            BuffBlacklistSyncPacket::handle);
        // 4: AbilityToggle — 客户端→服务端：切换能力开关
        INSTANCE.registerMessage(packetId++, AbilityTogglePacket.class,
            AbilityTogglePacket::encode, AbilityTogglePacket::decode,
            AbilityTogglePacket::handle);
        // 5: AdventureSyncRequest — 客户端→服务端：请求重新同步 Capability
        INSTANCE.registerMessage(packetId++, AdventureSyncRequestPacket.class,
            AdventureSyncRequestPacket::encode, AdventureSyncRequestPacket::decode,
            AdventureSyncRequestPacket::handle);
        // 6: ActiveSkill — 客户端→服务端：释放主动技能
        INSTANCE.registerMessage(packetId++, ActiveSkillPacket.class,
            ActiveSkillPacket::encode, ActiveSkillPacket::decode,
            ActiveSkillPacket::handle);
        // 8 (id 7): SkillSwitch — 客户端→服务端：切换主动技能索引
        INSTANCE.registerMessage(packetId++, SkillSwitchPacket.class,
            SkillSwitchPacket::encode, SkillSwitchPacket::decode,
            SkillSwitchPacket::handle);
    }

    // ===== 发送方法 =====

    /** 客户端发送二段跳请求 */
    public static void sendDoubleJumpRequest() {
        INSTANCE.sendToServer(new DoubleJumpPacket());
    }

    /** 客户端请求同步排除列表 */
    public static void sendBuffBlacklistRequest() {
        INSTANCE.sendToServer(new BuffBlacklistSyncPacket(true));
    }

    /** 客户端发送 Buff 排除切换请求 */
    public static void sendBuffToggle(String effectId) {
        INSTANCE.sendToServer(new BuffTogglePacket(effectId));
    }

    /** 客户端发送能力切换请求 */
    public static void sendAbilityToggle(String id) {
        INSTANCE.sendToServer(new AbilityTogglePacket(id));
    }

    /** 客户端请求服务端重新同步冒险进度 Capability */
    public static void sendAdventureSyncRequest() {
        INSTANCE.sendToServer(new AdventureSyncRequestPacket());
    }

    /** 客户端请求释放主动技能（0=审判，1=庇护） */
    public static void sendActiveSkill(int skillIndex) {
        INSTANCE.sendToServer(new ActiveSkillPacket(skillIndex));
    }

    /** 客户端发送技能切换请求（0=审判，1=庇护），由服务端持久化后回同步 */
    public static void sendSkillSwitch(int skillIndex) {
        INSTANCE.sendToServer(new SkillSwitchPacket(skillIndex));
    }

    // ===== 辅助方法 =====

    /** 在服务端主线程执行，自动取发送者 ServerPlayer，为空则跳过 */
    private static void runOnServer(Supplier<NetworkEvent.Context> ctx, Consumer<ServerPlayer> action) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                action.accept(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // ===== 包定义 =====

    /** 客户端→服务端：二段跳请求 */
    public static class DoubleJumpPacket {
        public DoubleJumpPacket() {}
        public DoubleJumpPacket(FriendlyByteBuf buf) {}

        public static void encode(DoubleJumpPacket msg, FriendlyByteBuf buf) {}

        public static DoubleJumpPacket decode(FriendlyByteBuf buf) { return new DoubleJumpPacket(); }

        public static void handle(DoubleJumpPacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> DoubleJumpHandler.handleDoubleJump(player));
        }
    }

    /** 客户端→服务端：切换某个效果的排除状态 */
    public static class BuffTogglePacket {
        public final String effectId;

        public BuffTogglePacket(String effectId) { this.effectId = effectId; }

        public BuffTogglePacket(FriendlyByteBuf buf) { this.effectId = buf.readUtf(64); }

        public static void encode(BuffTogglePacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.effectId);
        }

        public static BuffTogglePacket decode(FriendlyByteBuf buf) {
            return new BuffTogglePacket(buf);
        }

        public static void handle(BuffTogglePacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> {
                if (AdventureProgressCapability.isAdventurer(player)
                    || AdventureProgressCapability.isFullyUnlocked(player)) {
                    BuffExclusionManager.toggleBuffExclusion(player, msg.effectId);
                    Set<String> updated = BuffExclusionManager.getBuffExclusionSet(player);
                    INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                        new BuffBlacklistSyncPacket(updated));
                }
            });
        }
    }

    /** 双向：request=true 客户端请求同步；request=false 服务端响应完整排除列表 */
    public static class BuffBlacklistSyncPacket {
        public final boolean request;
        public final Set<String> blacklist;

        /** 客户端请求同步 */
        public BuffBlacklistSyncPacket(boolean request) {
            this.request = request;
            this.blacklist = Set.of();
        }

        /** 服务端响应 */
        public BuffBlacklistSyncPacket(Set<String> blacklist) {
            this.request = false;
            this.blacklist = blacklist;
        }

        public BuffBlacklistSyncPacket(FriendlyByteBuf buf) {
            this.request = buf.readBoolean();
            int size = buf.readVarInt();
            Set<String> set = new HashSet<>();
            for (int i = 0; i < size; i++) set.add(buf.readUtf(64));
            this.blacklist = set;
        }

        public static void encode(BuffBlacklistSyncPacket msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.request);
            buf.writeVarInt(msg.blacklist.size());
            for (String s : msg.blacklist) buf.writeUtf(s);
        }

        public static BuffBlacklistSyncPacket decode(FriendlyByteBuf buf) {
            return new BuffBlacklistSyncPacket(buf);
        }

        public static void handle(BuffBlacklistSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            // 方向防御：request=true 只能由服务端处理（C2S），request=false 只能由客户端处理（S2C）
            // 语义与方向矛盾说明是伪造包，直接丢弃
            boolean toClient = ctx.get().getDirection() == NetworkDirection.PLAY_TO_CLIENT;
            if (msg.request == toClient) {
                ctx.get().setPacketHandled(true);
                return;
            }
            if (msg.request) {
                // 客户端→服务端：请求同步
                runOnServer(ctx, player -> {
                    Set<String> blacklist = BuffExclusionManager.getBuffExclusionSet(player);
                    INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                        new BuffBlacklistSyncPacket(blacklist));
                });
            } else {
                // 服务端→客户端：接收完整排除列表
                ctx.get().enqueueWork(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof AdventureMainScreen screen) {
                        screen.onSyncReceived(msg.blacklist);
                    }
                });
                ctx.get().setPacketHandled(true);
            }
        }
    }

    /** 服务端→客户端：冒险进度 Capability 同步 */
    public static class AdventureSyncPacket {
        private final CompoundTag data;

        public AdventureSyncPacket(CompoundTag data) {
            this.data = data;
        }

        public static void encode(AdventureSyncPacket msg, FriendlyByteBuf buf) {
            buf.writeNbt(msg.data);
        }

        public static AdventureSyncPacket decode(FriendlyByteBuf buf) {
            return new AdventureSyncPacket(buf.readNbt());
        }

        public static void handle(AdventureSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            // 方向防御：本包只能由客户端处理（S2C），专用服务器上执行会 NPE 崩溃
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_CLIENT || msg.data == null) {
                ctx.get().setPacketHandled(true);
                return;
            }
            ctx.get().enqueueWork(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    // 先提取里程碑注册表元数据初始化客户端 MilestoneRegistry（直接 NBT 构建，不经 JSON 中转）
                    if (msg.data.contains("_milestone_registry")) {
                        com.ayin90723.adventure_power.util.MilestoneRegistry.clientInitFromNbt(
                            msg.data.getCompound("_milestone_registry"));
                    }
                    mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(
                        progress -> progress.deserializeNBT(msg.data));
                    // 如果有等待同步后打开的屏幕，现在打开
                    AdventureProgressCapability.tryOpenPendingScreen();
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** 客户端→服务端：切换能力开关 */
    public static class AbilityTogglePacket {
        public final String id;

        public AbilityTogglePacket(String id) { this.id = id; }

        public AbilityTogglePacket(FriendlyByteBuf buf) { this.id = buf.readUtf(64); }

        public static void encode(AbilityTogglePacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.id);
        }

        public static AbilityTogglePacket decode(FriendlyByteBuf buf) {
            return new AbilityTogglePacket(buf);
        }

        public static void handle(AbilityTogglePacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> {
                if (AdventureProgressCapability.isAdventurer(player)
                    || AdventureProgressCapability.isFullyUnlocked(player)) {
                    AdventureProgressCapability.toggleAbility(player, msg.id);
                    SyncUtil.syncToClient(player);

                    // 翱翔 toggle 后立即同步 mayfly，不等下一 tick handler
                    if (AbilityIds.SOAR.equals(msg.id)) {
                        boolean enabled = AdventureProgressCapability.getAdventureProgress(player)
                            .map(p -> p.isAbilityEnabled(AbilityIds.SOAR)).orElse(false);
                        PlayerStateHandler.applySoarState(player, enabled);
                    }
                }
            });
        }
    }

    /** 客户端→服务端：请求重新同步冒险进度 Capability */
    public static class AdventureSyncRequestPacket {
        public AdventureSyncRequestPacket() {}

        public AdventureSyncRequestPacket(FriendlyByteBuf buf) {}

        public static void encode(AdventureSyncRequestPacket msg, FriendlyByteBuf buf) {}

        public static AdventureSyncRequestPacket decode(FriendlyByteBuf buf) {
            return new AdventureSyncRequestPacket(buf);
        }

        public static void handle(AdventureSyncRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> SyncUtil.syncToClient(player));
        }
    }

    /** 客户端→服务端：释放主动技能 */
    public static class ActiveSkillPacket {
        public final int skillIndex;

        public ActiveSkillPacket(int skillIndex) { this.skillIndex = skillIndex; }

        public ActiveSkillPacket(FriendlyByteBuf buf) { this.skillIndex = buf.readVarInt(); }

        public static void encode(ActiveSkillPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.skillIndex);
        }

        public static ActiveSkillPacket decode(FriendlyByteBuf buf) {
            return new ActiveSkillPacket(buf);
        }

        public static void handle(ActiveSkillPacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> ActiveSkillHandler.handleSkillRelease(player, msg.skillIndex));
        }
    }

    /** 客户端→服务端：切换主动技能索引（0=审判，1=庇护）——服务端持久化后回同步，解决切换被任意 sync 覆盖的问题 */
    public static class SkillSwitchPacket {
        public final int skillIndex;

        public SkillSwitchPacket(int skillIndex) { this.skillIndex = skillIndex; }

        public SkillSwitchPacket(FriendlyByteBuf buf) { this.skillIndex = buf.readVarInt(); }

        public static void encode(SkillSwitchPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.skillIndex);
        }

        public static SkillSwitchPacket decode(FriendlyByteBuf buf) {
            return new SkillSwitchPacket(buf);
        }

        public static void handle(SkillSwitchPacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> {
                if (AdventureProgressCapability.isAdventurer(player)
                    || AdventureProgressCapability.isFullyUnlocked(player)) {
                    AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                        if (progress.isAbilityEnabled(AbilityIds.ACTIVE_SKILL)) {
                            progress.setActiveSkillIndex(msg.skillIndex == 0 ? 0 : 1);
                        }
                    });
                }
                // 总是回发同步：接受时持久化确认，拒绝时（两端数据短暂不一致）
                // 让客户端乐观更新回滚到服务端真实状态，避免 HUD 索引永久偏离
                SyncUtil.syncToClient(player);
            });
        }
    }
}
