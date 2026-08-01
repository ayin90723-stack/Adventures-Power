package com.ayin90723.adventure_power.capability;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.AdventureItemNbtUtil;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.ScoreboardUtil;
import com.ayin90723.adventure_power.util.SyncUtil;
import com.ayin90723.adventure_power.network.NetworkHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import org.jetbrains.annotations.Nullable;

/**
 * 冒险进度 Capability - 注册/附加/查询/里程碑解锁入口。
 * <p>
 * 原 945 行 GOD 类已拆分，本类现仅保留 Capability 核心：
 * <ul>
 *   <li>Capability 注册与附加（{@link #onAttachCapabilities}）</li>
 *   <li>进度查询（{@link #getAdventureProgress}/{@link #isAdventurer}/{@link #isFullyUnlocked}/{@link #isAbilityAvailable}/{@link #toggleAbility}）</li>
 *   <li>KNOWN_ABILITIES 能力注册表（有序，面板显示顺序）</li>
 *   <li>{@link #grantMilestone} 里程碑解锁唯一入口</li>
 *   <li>pendingScreen 按键面板打开机制</li>
 *   <li>恩赐永驻觉醒（正面效果不可驱散）</li>
 * </ul>
 * 已拆出职责：物品 NBT（{@link AdventureItemNbtUtil}）、同步（{@link SyncUtil}）、
 * 计分板（{@link ScoreboardUtil}）、死亡抗拒（{@link com.ayin90723.adventure_power.handler.DeathDefyHandler}）、
 * 每 tick 处理（{@link com.ayin90723.adventure_power.handler.PlayerTickHandler}）、
 * 生命周期（{@link com.ayin90723.adventure_power.handler.CapabilityLifecycleHandler}）。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class AdventureProgressCapability {

    /** Capability 声明 - Forge 自动注册 */
    public static final Capability<IAdventureProgress> CAPABILITY =
        CapabilityManager.get(new CapabilityToken<>(){});

    /** 等待同步完成后打开的屏幕类型（客户端用） */
    public static final int PENDING_NONE = 0;
    public static final int PENDING_BUFF = 1;
    public static final int PENDING_ABILITY = 2;
    public static final int PENDING_MILESTONE = 3;
    private static int pendingScreen = PENDING_NONE;

    /** 客户端请求同步并计划打开屏幕 */
    public static void requestSyncAndOpenScreen(int screenType) {
        pendingScreen = screenType;
        NetworkHandler.sendAdventureSyncRequest();
    }

    /** 同步包到达后，如有等待中的屏幕则打开 */
    public static void tryOpenPendingScreen() {
        if (pendingScreen == PENDING_NONE) return;
        int type = pendingScreen;
        pendingScreen = PENDING_NONE;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            if (type == PENDING_BUFF) {
                mc.setScreen(new com.ayin90723.adventure_power.ui.AdventureMainScreen(com.ayin90723.adventure_power.ui.AdventureMainScreen.Tab.BUFF));
            } else if (type == PENDING_ABILITY) {
                mc.setScreen(new com.ayin90723.adventure_power.ui.AdventureMainScreen());
            } else if (type == PENDING_MILESTONE) {
                mc.setScreen(new com.ayin90723.adventure_power.ui.AdventureMainScreen(com.ayin90723.adventure_power.ui.AdventureMainScreen.Tab.MILESTONE));
            }
        }
    }

    /** 能力注册表（有序，面板按此顺序显示）。从 AbilityRegistry 注册顺序生成，避免重复维护。 */
    public static final Map<String, Component> KNOWN_ABILITIES = new LinkedHashMap<>();
    static {
        for (var ability : com.ayin90723.adventure_power.ability.AbilityRegistry.ALL.values()) {
            KNOWN_ABILITIES.put(ability.id(), ability.name());
        }
    }

    /** 检查指定能力在当前玩家进度下是否可用（基于里程碑数） */
    public static boolean isAbilityAvailable(Player player, String id) {
        if (!KNOWN_ABILITIES.containsKey(id)) return false;
        if (AbilityRegistry.get(id) == null) return false;
        return getAdventureProgress(player).map(p -> p.isAbilityUnlocked(id)).orElse(false);
    }

    /** Capability 附加资源 ID */
    private static final ResourceLocation ID =
        new ResourceLocation(AdventurePower.MODID, "adventure_progress");

    // ===== 辅助：获取 Capability =====

    public static Optional<IAdventureProgress> getAdventureProgress(Player player) {
        return player.getCapability(CAPABILITY).resolve();
    }

    public static boolean isAdventurer(Player player) {
        return getAdventureProgress(player).map(IAdventureProgress::isAdventurer).orElse(false);
    }

    public static boolean isFullyUnlocked(Player player) {
        return getAdventureProgress(player).map(IAdventureProgress::isFullyUnlocked).orElse(false);
    }

    /** 服务端切换能力开关，返回新状态（true=启用）。仅允许注册表中的能力 ID，且必须是已解锁能力 */
    public static boolean toggleAbility(Player player, String id) {
        if (!KNOWN_ABILITIES.containsKey(id)) return false;
        return getAdventureProgress(player).map(p -> {
            // 门禁：未解锁的能力不允许切换（恶意客户端可写入任意 ID，否则解锁后默认禁用）
            if (!p.isAbilityUnlocked(id)) return false;
            boolean enabled = p.toggleAbility(id);
            // true_health 关闭时重置备份血量，防止重新激活时过时备份
            // 被误判为"非法降血"导致血量恢复（如关闭期间玩家受伤，备份冻结在旧值）
            if (AbilityIds.TRUE_HEALTH.equals(id) && !enabled) {
                p.setBackupHealth(0.0F);
            }
            return enabled;
        }).orElse(false);
    }

    // ===== Attach =====

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof Player)) return;

        AdventureProgress backend = new AdventureProgress();
        LazyOptional<IAdventureProgress> lazy = LazyOptional.of(() -> backend);

        event.addCapability(ID, new ICapabilitySerializable<CompoundTag>() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
                if (cap == CAPABILITY) {
                    return lazy.cast();
                }
                return LazyOptional.empty();
            }

            @Override
            public CompoundTag serializeNBT() {
                return backend.serializeNBT();
            }

            @Override
            public void deserializeNBT(CompoundTag nbt) {
                backend.deserializeNBT(nbt);
            }
        });
    }

    // ===== 里程碑解锁（统一入口，由 AdvancementEventHandler 调用） =====

    /**
     * 授予里程碑并同步所有数据。幂等--已解锁的里程碑不会重复处理。
     * <p>
     * 此方法是里程碑解锁的<b>唯一入口</b>。无论触发源是 Forge 事件、原版成就
     * 还是网络同步，最终都通过此方法完成 Capability 更新 + NBT 同步 + 客户端通知。
     */
    public static void grantMilestone(ServerPlayer player, String milestoneId) {
        getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer()) return;
            if (progress.isMilestoneUnlocked(milestoneId)) return;

            progress.unlockMilestone(milestoneId);
            // 翱翔飞行立即同步：不等 PlayerStateHandler 下一 tick，
            // 避免两处 TickEvent.Phase.END handler 执行顺序不确定导致的竞态
            if (progress.isAbilityEnabled(AbilityIds.SOAR) && !player.getAbilities().mayfly
                && !player.getAbilities().instabuild && !player.isSpectator()) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }
            SyncUtil.syncCapabilityToPersistent(player, progress);
            AdventureItemNbtUtil.syncAllAdventureItemNbt(player, progress);
            SyncUtil.syncToClient(player);

            ServerLevel level = player.serverLevel();
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
            level.sendParticles(ParticleTypes.END_ROD,
                player.getX(), player.getY() + 1.5, player.getZ(),
                30, 0.5, 0.5, 0.5, 0.1);
            // 内置里程碑走 lang 键（zh/en 翻译），数据包自定义里程碑用 JSON name 兜底，
            // 避免显示原始 key 或把中文 name 硬塞给英文玩家
            Milestone m = MilestoneRegistry.getById(milestoneId);
            net.minecraft.network.chat.MutableComponent milestoneName = m != null
                ? Component.translatableWithFallback("milestone.adventure_power." + milestoneId, m.name())
                : Component.translatable("milestone.adventure_power." + milestoneId);
            player.displayClientMessage(milestoneName.withStyle(ChatFormatting.GREEN), true);

            // ★ 全部里程碑达成 -> 冒险的开始 自动替换为 冒险的终点 + 觉醒级联
            // 不限制最后一个里程碑必须是 ELYTRA，适应任意解锁顺序
            activateFinalStageIfReady(player, progress);
        });
    }

    /**
     * 全部里程碑达成时的第 11 阶段「冒险者的觉醒」级联（幂等）。
     * <p>
     * 所有解锁路径（grantMilestone / catchUpMissedMilestones）必须走此方法，
     * 否则 10/10 里程碑但觉醒永不激活、终点饰品永不替换，且无自愈路径。
     */
    public static void activateFinalStageIfReady(ServerPlayer player, IAdventureProgress progress) {
        if (progress.areAllMilestonesUnlocked() && !progress.isFullyUnlocked()) {
            AdventureItemNbtUtil.replaceBeginWithEnd(player);
            progress.activateFullyUnlocked();
            ScoreboardUtil.updateScoreboard(player, true);
            SyncUtil.syncCapabilityToPersistent(player, progress);
            SyncUtil.syncToClient(player);
        }
    }

    // ===== 恩赐永驻觉醒：正面效果无法被驱散 =====

    /**
     * 觉醒后正面效果无法被驱散。监听 MobEffectEvent.Remove，覆盖 removeEffect / removeAllEffects /
     * cureEffects(牛奶桶) 三条主动移除路径；自然过期走独立的 MobEffectEvent.Expired，不受影响。
     * 仅对 BENEFICIAL（正面）效果取消移除，负面/中性照常可清。
     */
    @SubscribeEvent
    public static void onBeneficialEffectRemove(MobEffectEvent.Remove event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (!(entity instanceof Player player)) return;
        getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled(AbilityIds.PERPETUAL_BLESSING)) return;
            MobEffect effect = event.getEffect();
            if (effect != null && effect.getCategory() == MobEffectCategory.BENEFICIAL) {
                event.setCanceled(true);
            }
        });
    }
}
