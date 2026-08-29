package com.ayin90723.adventure_power.network;

import com.ayin90723.adventure_power.util.AbilityGate;
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

    /** 玩家登出时清理全部限频表（v1.4.0：防长期服务器 UUID 累积；由 PlayerTickHandler.onPlayerLogout 调用） */
    public static void clearCooldowns(java.util.UUID uuid) {
        AbilityTogglePacket.TOGGLE_COOLDOWN.remove(uuid);
        AdventureSyncRequestPacket.SYNC_REQUEST_COOLDOWN.remove(uuid);
        SkillSwitchPacket.SWITCH_COOLDOWN.remove(uuid);
        ActiveSkillPacket.SKILL_COOLDOWN.remove(uuid);
        // 审查修 P3#2/P3#3：新增限频表同步清理
        BuffTogglePacket.BUFF_TOGGLE_COOLDOWN.remove(uuid);
        DoubleJumpPacket.JUMP_COOLDOWN.remove(uuid);
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
            // 审查修 P3#3：补 5 tick 限频——恶意客户端在地面刷空包时每 tick 落地清零
            // AIR_JUMPED 标记，每包都付全价 resolve + 校验 + 施力 + 粒子（无任何节流）
            runOnServer(ctx, player -> {
                var server = player.getServer();
                if (server == null) return;
                long now = server.getTickCount();
                java.util.UUID uuid = player.getUUID();
                Long last = JUMP_COOLDOWN.get(uuid);
                if (last != null && now - last < 5) return;
                JUMP_COOLDOWN.put(uuid, now);
                DoubleJumpHandler.handleDoubleJump(player);
            });
        }

        /** 二段跳请求限频表（审查修 P3#3；登出清理走 clearCooldowns） */
        static final java.util.Map<java.util.UUID, Long> JUMP_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    }

    /** 客户端→服务端：切换某个效果的排除状态 */
    public static class BuffTogglePacket {
        /** Buff 切换限频表（审查修 P3#2：本包带 persistentData 写 + 回包放大，与其他
         *  C2S 包同基准 5 tick 限频；登出清理走 clearCooldowns） */
        static final java.util.Map<java.util.UUID, Long> BUFF_TOGGLE_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();

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
                // 门禁补全：黑名单只对恩赐永驻有意义，需能力启用；effectId 必须是已注册效果，
                // 否则任意字符串会永久写入 persistentData 撑大 NBT
                var server = player.getServer();
                if (server != null) {
                    // 审查修 P3#2：5 tick 限频（每包 persistentData 读写 + 回包放大）
                    long now = server.getTickCount();
                    java.util.UUID uuid = player.getUUID();
                    Long last = BUFF_TOGGLE_COOLDOWN.get(uuid);
                    if (last != null && now - last < 5) return;
                    BUFF_TOGGLE_COOLDOWN.put(uuid, now);
                }
                if (!AdventureProgressCapability.isAdventurer(player)
                    && !AdventureProgressCapability.isFullyUnlocked(player)) return;
                var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
                if (progressOpt.isEmpty()) return;
                if (!AbilityGate.isActive(progressOpt.get(), AbilityIds.PERPETUAL_BLESSING)) return;
                if (net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.containsKey(
                        net.minecraft.resources.ResourceLocation.tryParse(msg.effectId))) {
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
            // 解码钳制（按方向区分）：
            //  C2S 请求（request=true）：黑名单应为空，钳 128 防伪造包把 size 撑到 VarInt 上限
            //    （2.68 亿）OOM 服务端——decode 在网络线程先于 handle 的方向防御执行，必须钳制；
            //  S2C 响应（request=false）：服务端发的完整排除列表，大型整合包效果数可能超 128，
            //    钳 2048 仅防异常包，不截断合法数据
            int cap = this.request ? 128 : 2048;
            if (size > cap) size = cap;
            Set<String> set = new HashSet<>();
            for (int i = 0; i < size; i++) set.add(buf.readUtf(64));
            this.blacklist = set;
        }

        public static void encode(BuffBlacklistSyncPacket msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.request);
            // 对称限长（v1.4.0 审查修复）：与 decode 的 readUtf(64) 对齐——外部途径
            // （手改存档 NBT / 旧版本数据）写入的超长 key 在此过滤，否则服务端
            // 无界编码成功、客户端 readUtf(64) 解码抛异常，该玩家进入"进服即踢"循环
            int count = 0;
            for (String s : msg.blacklist) {
                if (s != null && s.length() <= 64) count++;
            }
            buf.writeVarInt(count);
            for (String s : msg.blacklist) {
                if (s != null && s.length() <= 64) buf.writeUtf(s, 64);
            }
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
                // 客户端→服务端：请求同步（v1.4.0 审查修复补限频：每次处理都读
                // persistentData + 回发全量列表，与 AdventureSyncRequestPacket 的
                // 放大场景相同——复用其 20 tick 冷却表，语义一致且登出清理已覆盖）
                runOnServer(ctx, player -> {
                    long now = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getTickCount();
                    Long last = AdventureSyncRequestPacket.SYNC_REQUEST_COOLDOWN.get(player.getUUID());
                    if (last != null && now - last < 20) return; // 1s 限频，静默丢弃
                    AdventureSyncRequestPacket.SYNC_REQUEST_COOLDOWN.put(player.getUUID(), now);
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

        /** 限频表：玩家 UUID -> 上次处理请求的服务端全局 tick。
         *  每次 toggle 服务端都会回发全量 Capability NBT（含里程碑元数据，KB 级），
         *  恶意客户端快速来回 toggle 会放大服务器→客户端流量——限 5 tick（0.25s）一次
         *  （间隔不影响正常 UI 点击节奏）。用全局 tick 而非维度 gameTime：跨维度基准错位。 */
        static final java.util.Map<java.util.UUID, Long> TOGGLE_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();

        public static void handle(AbilityTogglePacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> {
                long now = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getTickCount();
                Long last = TOGGLE_COOLDOWN.get(player.getUUID());
                if (last != null && now - last < 5) return; // 限频，静默丢弃
                if (TOGGLE_COOLDOWN.size() > 2048) {
                    TOGGLE_COOLDOWN.values().removeIf(t -> now - t > 6000); // 5 分钟超时清理
                }
                TOGGLE_COOLDOWN.put(player.getUUID(), now);
                if (AdventureProgressCapability.isAdventurer(player)
                    || AdventureProgressCapability.isFullyUnlocked(player)) {
                    AdventureProgressCapability.toggleAbility(player, msg.id);
                    SyncUtil.syncToClient(player);
                    // v1.4.0 审查修复：补 persistentData 第二层同步——toggle 是此前唯一
                    // 遗漏该同步的变更路径，若 toggle 后服务器崩溃且 ForgeCaps 恰未落盘，
                    // 登录恢复会读过期快照，手动关闭的能力被"复活"（三层备份约定 7 缺口）
                    AdventureProgressCapability.getAdventureProgress(player)
                        .ifPresent(p -> SyncUtil.syncCapabilityToPersistent(player, p));

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

    /** 客户端→服务端：冒险进度同步请求（服务端限频 1s 一次后回发全量 Capability） */
    public static class AdventureSyncRequestPacket {
        public AdventureSyncRequestPacket() {}

        public AdventureSyncRequestPacket(FriendlyByteBuf buf) {}

        public static void encode(AdventureSyncRequestPacket msg, FriendlyByteBuf buf) {}

        public static AdventureSyncRequestPacket decode(FriendlyByteBuf buf) {
            return new AdventureSyncRequestPacket(buf);
        }

        /** 限频表：玩家 UUID -> 上次处理请求的服务端全局 tick（ServerLifecycleHooks）。
         *  每次处理都会回发全量 Capability NBT（含里程碑元数据，KB 级），
         *  恶意客户端刷请求会放大服务器→客户端流量——限 1s（20 tick）一次。
         *  用全局 tick 而非维度 gameTime：1.20.1 每维度计时独立，跨维度会基准错位。
         *  put 时顺带清理超时条目（防长期服务器 UUID 累积） */
        static final java.util.Map<java.util.UUID, Long> SYNC_REQUEST_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();

        public static void handle(AdventureSyncRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> {
                long now = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getTickCount();
                Long last = SYNC_REQUEST_COOLDOWN.get(player.getUUID());
                if (last != null && now - last < 20) return; // 1s 限频，静默丢弃
                if (SYNC_REQUEST_COOLDOWN.size() > 2048) {
                    SYNC_REQUEST_COOLDOWN.values().removeIf(t -> now - t > 6000); // 5 分钟超时清理
                }
                SYNC_REQUEST_COOLDOWN.put(player.getUUID(), now);
                SyncUtil.syncToClient(player);
            });
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

        /** 限频表：与 AbilityTogglePacket 同理——每次释放都会执行服务端 AABB 实体查询
         *  （collectJudgmentTargets），恶意刷包放大查询开销（效果本身有 CD/GCD 强校验，
         *  伪造包无法绕过冷却，这里只防查询放大）——限 5 tick 一次 */
        static final java.util.Map<java.util.UUID, Long> SKILL_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();

        public static void handle(ActiveSkillPacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> {
                long now = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getTickCount();
                Long last = SKILL_COOLDOWN.get(player.getUUID());
                if (last != null && now - last < 5) return; // 限频，静默丢弃
                if (SKILL_COOLDOWN.size() > 2048) {
                    SKILL_COOLDOWN.values().removeIf(t -> now - t > 6000); // 5 分钟超时清理
                }
                SKILL_COOLDOWN.put(player.getUUID(), now);
                ActiveSkillHandler.handleSkillRelease(player, msg.skillIndex);
            });
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

        /** 限频表：与 AbilityTogglePacket 同理——每次切换都会回发全量同步，限 5 tick 一次 */
        static final java.util.Map<java.util.UUID, Long> SWITCH_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();

        public static void handle(SkillSwitchPacket msg, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                ctx.get().setPacketHandled(true);
                return;
            }
            runOnServer(ctx, player -> {
                long now = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getTickCount();
                Long last = SWITCH_COOLDOWN.get(player.getUUID());
                if (last != null && now - last < 5) return; // 限频，静默丢弃
                if (SWITCH_COOLDOWN.size() > 2048) {
                    SWITCH_COOLDOWN.values().removeIf(t -> now - t > 6000); // 5 分钟超时清理
                }
                SWITCH_COOLDOWN.put(player.getUUID(), now);
                if (AdventureProgressCapability.isAdventurer(player)
                    || AdventureProgressCapability.isFullyUnlocked(player)) {
                    AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                        if (progress.isAbilityEnabled(AbilityIds.ACTIVE_SKILL)) {
                            progress.setActiveSkillIndex(msg.skillIndex == 0 ? 0 : 1);
                            // 回发同步（v1.4.0 审查修复：移入门禁内）——接受时持久化确认，
                            // 拒绝时（两端数据短暂不一致）让客户端乐观更新回滚到服务端
                            // 真实状态，避免 HUD 索引永久偏离。未激活玩家客户端不会
                            // 做乐观更新，回滚理由不成立，回发 KB 级 NBT 纯浪费流量
                            SyncUtil.syncToClient(player);
                        }
                    });
                }
            });
        }
    }
}
