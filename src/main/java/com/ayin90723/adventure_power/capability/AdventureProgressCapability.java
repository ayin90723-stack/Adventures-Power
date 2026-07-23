package com.ayin90723.adventure_power.capability;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.handler.AdvancementEventHandler;
import com.ayin90723.adventure_power.item.ModItems;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import com.ayin90723.adventure_power.network.NetworkHandler;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.ItemInventoryHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;

@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class AdventureProgressCapability {

    /** Capability 声明 — Forge 自动注册 */
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

    /** 能力注册表（有序，面板按此顺序显示） */
    public static final Map<String, Component> KNOWN_ABILITIES = new LinkedHashMap<>();
    static {
        KNOWN_ABILITIES.put("agility", Component.translatable("ability.adventure_power.agility"));
        KNOWN_ABILITIES.put("digging_power", Component.translatable("ability.adventure_power.digging_power"));
        KNOWN_ABILITIES.put("perpetual_blessing", Component.translatable("ability.adventure_power.perpetual_blessing"));
        KNOWN_ABILITIES.put("void_step", Component.translatable("ability.adventure_power.void_step"));
        KNOWN_ABILITIES.put("rapid_recovery", Component.translatable("ability.adventure_power.rapid_recovery"));
        KNOWN_ABILITIES.put("soul_bind", Component.translatable("ability.adventure_power.soul_bind"));
        KNOWN_ABILITIES.put("knockback_resist", Component.translatable("ability.adventure_power.knockback_resist"));
        KNOWN_ABILITIES.put("damage_resist", Component.translatable("ability.adventure_power.damage_resist"));
        KNOWN_ABILITIES.put("extended_reach", Component.translatable("ability.adventure_power.extended_reach"));
        KNOWN_ABILITIES.put("undying_gear", Component.translatable("ability.adventure_power.undying_gear"));
        KNOWN_ABILITIES.put("fortune_favor", Component.translatable("ability.adventure_power.fortune_favor"));
        KNOWN_ABILITIES.put("env_immunity", Component.translatable("ability.adventure_power.env_immunity"));
        KNOWN_ABILITIES.put("lifesteal", Component.translatable("ability.adventure_power.lifesteal"));
        KNOWN_ABILITIES.put("healing_block", Component.translatable("ability.adventure_power.healing_block"));
        KNOWN_ABILITIES.put("vitality", Component.translatable("ability.adventure_power.vitality"));
        KNOWN_ABILITIES.put("resilience", Component.translatable("ability.adventure_power.resilience"));
        KNOWN_ABILITIES.put("purified_soul", Component.translatable("ability.adventure_power.purified_soul"));
        KNOWN_ABILITIES.put("loot_all", Component.translatable("ability.adventure_power.loot_all"));
        KNOWN_ABILITIES.put("soar", Component.translatable("ability.adventure_power.soar"));
        KNOWN_ABILITIES.put("soul_quench", Component.translatable("ability.adventure_power.soul_quench"));
        KNOWN_ABILITIES.put("piercing_gaze", Component.translatable("ability.adventure_power.piercing_gaze"));
        KNOWN_ABILITIES.put("death_defy", Component.translatable("ability.adventure_power.death_defy"));
        KNOWN_ABILITIES.put("shadow_kill", Component.translatable("ability.adventure_power.shadow_kill"));
        KNOWN_ABILITIES.put("true_health", Component.translatable("ability.adventure_power.true_health"));
        KNOWN_ABILITIES.put("reject_manip", Component.translatable("ability.adventure_power.reject_manip"));
        KNOWN_ABILITIES.put("active_skill", Component.translatable("ability.adventure_power.active_skill"));
    }

    /** 检查指定能力在当前玩家进度下是否可用（基于里程碑数） */
    public static boolean isAbilityAvailable(Player player, String id) {
        if (!KNOWN_ABILITIES.containsKey(id)) return false;
        if (AbilityRegistry.get(id) == null) return false;
        return getAdventureProgress(player).map(p -> {
            for (Milestone m : MilestoneRegistry.getAll()) {
                if (p.isMilestoneUnlocked(m.id()) && m.abilities().contains(id)) return true;
            }
            return false;
        }).orElse(false);
    }

    /** Capability 附加资源 ID */
    private static final ResourceLocation ID =
        new ResourceLocation(AdventurePower.MODID, "adventure_progress");

    /** 持久数据根键 */
    private static final String PERSISTENT_KEY = PersistentDataKeys.ADVENTURE_PROGRESS;

    /** 首次发放标记键 */
    public static final String GOT_BEGIN_KEY = PersistentDataKeys.GOT_BEGIN_KEY;

    /** 旧永久解锁标记键（迁移后清除） */
    private static final String OLD_UNLOCKED_KEY = PersistentDataKeys.OLD_UNLOCKED_KEY;

    /** 计分板目标名 */
    private static final String UNLOCK_OBJECTIVE = "adventure_power_unlock";

    /** Buff 延长间隔（tick） */
    private static final int BUFF_CHECK_INTERVAL = 60;

    /** 上次 Buff 检查时间缓存 */
    private static final Map<UUID, Long> lastBuffCheck = new HashMap<>();

    /** 已通过开局发放安全网验证的玩家（避免每 tick 重复扫描背包） */
    private static final Set<UUID> verifiedBeginItem = new HashSet<>();

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

    /** 服务端切换能力开关，返回新状态（true=启用）。仅允许注册表中的能力 ID */
    public static boolean toggleAbility(Player player, String id) {
        if (!KNOWN_ABILITIES.containsKey(id)) return false;
        return getAdventureProgress(player).map(p -> {
            boolean enabled = p.toggleAbility(id);
            // true_health 关闭时重置备份血量，防止重新激活时过时备份
            // 被误判为"非法降血"导致血量恢复（如关闭期间玩家受伤，备份冻结在旧值）
            if ("true_health".equals(id) && !enabled) {
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

    // ===== Clone（死亡/跨维度） =====

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player oldPlayer = event.getOriginal();
        Player newPlayer = event.getEntity();

        // 恢复策略：persistentData 是 Entity 自身的 NBT，不依赖 Capability 生命周期，
        // 死亡/跨维度后依然可读。优先用它恢复，其次尝试 Capability 直读，最后扫描物品。
        CompoundTag saved = oldPlayer.getPersistentData().getCompound(PERSISTENT_KEY);

        if (saved.isEmpty()) {
            // persistentData 为空 → 尝试从旧 Capability 读取
            // 先 reviveCaps() 因为死亡时 invalidateCaps() 可能已使 LazyOptional 失效
            oldPlayer.reviveCaps();
            saved = oldPlayer.getCapability(CAPABILITY)
                .map(IAdventureProgress::serializeNBT).orElse(new CompoundTag());
        }

        if (!saved.isEmpty()) {
            final CompoundTag data = saved;
            newPlayer.getCapability(CAPABILITY).ifPresent(newCap ->
                newCap.deserializeNBT(data));
        }

        // 兜底：以上均未恢复 → 扫描物品 NBT
        newPlayer.getCapability(CAPABILITY).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                recoverProgressFromItems(newPlayer, progress);
            }
            if (progress.isAdventurer() || progress.isFullyUnlocked()) {
                syncCapabilityToPersistent(newPlayer, progress);
            }
        });

        // 死亡/换维度后若持有冒险饰品但未激活，自动激活
        checkAndActivateAdventurer(newPlayer);

        // 重生/穿越末地后客户端 Capability 不会自动同步，
        // 需要手动推送 AdventureSyncPacket 确保面板和 tooltip 状态正确
        syncToClient(newPlayer);
    }

    // ===== 首次发放 + 旧数据迁移 =====

    @SubscribeEvent
    public static void onPlayerLogin(PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        getAdventureProgress(player).ifPresent(progress -> {
            // 1. 始终尝试从持久数据恢复（如果还没恢复的话）
            boolean hasOldPersistent = player.getPersistentData().contains(OLD_UNLOCKED_KEY);
            if (hasOldPersistent) {
                if (player.getPersistentData().getBoolean(OLD_UNLOCKED_KEY)) {
                    progress.activateFullyUnlocked();
                    updateScoreboard(player, true);
                }
                player.getPersistentData().remove(OLD_UNLOCKED_KEY);
            }

            // 2. Forge 可能未反序列化 capability → 从 PERSISTENT_KEY 兜底恢复
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                CompoundTag saved = player.getPersistentData().getCompound(PERSISTENT_KEY);
                if (!saved.isEmpty()) {
                    progress.deserializeNBT(saved);
                }
            }

            // 3. 如果 Capability 中还没有 adventurer 状态，扫描物品 NBT 尝试恢复
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                recoverProgressFromItems(player, progress);
            }

            // 4. 清理旧 AdventureStage NBT（可能残留在物品上）
            cleanOldStageNbt(player);

            syncCapabilityToPersistent(player, progress);
        });

        giveAdventureBeginIfNeeded(player);
        checkAndActivateAdventurer(player);
        syncToClient(player);
    }

    private static void cleanOldStageNbt(Player player) {
        ItemInventoryHelper.forEachInventorySlot(player, stack -> {
            if (stack.is(ModItems.ADVENTURE_BEGIN.get()) || stack.is(ModItems.ADVENTURE_END.get()))
                migrateOldStage(stack);
        });
    }

    private static void giveAdventureBeginIfNeeded(Player player) {
        getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isFullyUnlocked()) {
                // 完全解锁 → 应持有冒险的终点，丢失则补发
                if (playerHasAdventureItem(player)) return;
                ItemStack endStack = new ItemStack(ModItems.ADVENTURE_END.get());
                writeMilestonesToStack(endStack, progress);
                if (!player.getInventory().add(endStack)) {
                    player.spawnAtLocation(endStack);
                }
                return;
            }

            if (progress.isAdventurer()) {
                // 冒险者 → 应持有冒险的开始，丢失则补发
                if (playerHasAdventureItem(player)) return;
                ItemStack beginStack = new ItemStack(ModItems.ADVENTURE_BEGIN.get());
                writeMilestonesToStack(beginStack, progress);
                if (!player.getInventory().add(beginStack)) {
                    player.spawnAtLocation(beginStack);
                }
                return;
            }

            // 未激活冒险者 → 首次发放（用 GOT_BEGIN_KEY 防止重复）
            CompoundTag persistent = player.getPersistentData();
            if (persistent.getBoolean(GOT_BEGIN_KEY)) return;
            if (playerHasAdventureItem(player)) {
                persistent.putBoolean(GOT_BEGIN_KEY, true);
                return;
            }

            ItemStack stack = new ItemStack(ModItems.ADVENTURE_BEGIN.get());
            boolean added = player.getInventory().add(stack);
            if (!added) {
                player.spawnAtLocation(stack);
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(
                        Component.literal("§e你的物品栏已满，§6冒险的开始§e掉落在地上了！捡起并佩戴以开启冒险")
                            .withStyle(ChatFormatting.GOLD), false);
                }
            } else if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(
                    Component.literal("§e你收到了一份 §6冒险的开始 §e— 佩戴它以开启冒险")
                        .withStyle(ChatFormatting.GOLD), false);
            }
            persistent.putBoolean(GOT_BEGIN_KEY, true);
        });
    }

    /** 若玩家已佩戴冒险饰品但尚未激活冒险者，自动授予根成就 */
    private static void checkAndActivateAdventurer(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isAdventurer()) return;
            // 版本飞升可能导致 fullyUnlocked=true 但 adventurer=false（数据不一致），
            // 此时直接补激活 adventurer，避免 onLivingHurt 等只查 isAdventurer 的门禁误拦。
            if (progress.isFullyUnlocked()) {
                progress.activateAdventurer();
                syncCapabilityToPersistent(player, progress);
                syncToClient(player);
                return;
            }
            if (playerHasAdventureItem(player)) {
                progress.activateAdventurer();
                syncCapabilityToPersistent(player, progress);
                syncAllAdventureItemNbt(player, progress);
                syncToClient(player);
                AdvancementEventHandler.catchUpMissedMilestones(sp);
            }
        });
    }

    private static boolean playerHasAdventureItem(Player player) {
        return ItemInventoryHelper.hasAnyAdventureSlot(player,
            stack -> stack.is(ModItems.ADVENTURE_BEGIN.get()) || stack.is(ModItems.ADVENTURE_END.get()));
    }

    /** 检查玩家是否持有冒险的终点（仅终点，不包含开始） */
    private static boolean playerHasAdventureEnd(Player player) {
        return ItemInventoryHelper.hasAnyAdventureSlot(player,
            stack -> stack.is(ModItems.ADVENTURE_END.get()));
    }

    // ===== NBT 扫描与同步 =====

    private static void scanAllItemsForMilestones(Player player, boolean[] found) {
        java.util.List<Milestone> all = MilestoneRegistry.getAll();
        ItemInventoryHelper.forEachAdventureSlot(player, stack ->
            scanItemForMilestones(stack, all, found));
    }

    private static void scanItemForMilestones(ItemStack stack, java.util.List<Milestone> all, boolean[] found) {
        if (!stack.is(ModItems.ADVENTURE_BEGIN.get()) && !stack.is(ModItems.ADVENTURE_END.get())) return;
        migrateOldStage(stack);
        CompoundTag tag = stack.getOrCreateTag();
        for (int i = 0; i < all.size(); i++) {
            if (tag.getBoolean(PersistentDataKeys.milestoneNbtKey(all.get(i).id()))) {
                found[i] = true;
            }
        }
    }

    /**
     * 从全身物品（背包/盔甲/副手/Curios）中的冒险饰品 NBT 恢复 Capability 里程碑状态。
     * 用于维度切换/登录时 Capability 数据丢失的兜底恢复。
     *
     * @return true 表示成功恢复了至少一个里程碑
     */
    private static boolean recoverProgressFromItems(Player player, IAdventureProgress progress) {
        java.util.List<Milestone> all = MilestoneRegistry.getAll();
        boolean[] found = new boolean[all.size()];
        scanAllItemsForMilestones(player, found);
        boolean anyMilestone = false;
        for (int i = 0; i < all.size(); i++) {
            if (found[i]) {
                progress.unlockMilestone(all.get(i).id());
                anyMilestone = true;
            }
        }
        if (anyMilestone) {
            progress.activateAdventurer();
            // 如果所有里程碑都已恢复，也恢复 fullyUnlocked
            if (progress.getUnlockedMilestoneCount() >= all.size()) {
                progress.activateFullyUnlocked();
            }
            syncAllAdventureItemNbt(player, progress);
        }
        return anyMilestone;
    }

    /**
     * Capability → persistent data 显式同步。
     * 注意：Forge 通过 ICapabilitySerializable 也会自动持久化，此处是额外的安全措施，
     * 确保在 Capability 自动保存周期之前关键数据已被写入。
     */
    public static void syncCapabilityToPersistent(Player player, IAdventureProgress progress) {
        player.getPersistentData().put(PERSISTENT_KEY, progress.serializeNBT());
    }

    public static void syncAllAdventureItemNbt(Player player, IAdventureProgress progress) {
        ItemInventoryHelper.forEachInventoryList(player, list -> syncStackList(list, progress));
        ItemInventoryHelper.forEachCuriosSlot(player, (handler, i, stack) -> {
            if (stack.is(ModItems.ADVENTURE_BEGIN.get()) || stack.is(ModItems.ADVENTURE_END.get())) {
                writeMilestonesToStack(stack, progress);
                handler.getStacks().setStackInSlot(i, stack);
            }
        });
    }

    private static void syncStackList(NonNullList<ItemStack> list, IAdventureProgress progress) {
        for (ItemStack stack : list) {
            if (!stack.isEmpty() && (stack.is(ModItems.ADVENTURE_BEGIN.get()) || stack.is(ModItems.ADVENTURE_END.get()))) {
                writeMilestonesToStack(stack, progress);
            }
        }
    }

    /** 向指定玩家同步冒险进度 Capability 数据到客户端（含里程碑注册表元数据） */
    public static void syncToClient(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        getAdventureProgress(player).ifPresent(progress -> {
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

    public static void writeMilestonesToStack(ItemStack stack, IAdventureProgress progress) {
        CompoundTag tag = stack.getOrCreateTag();
        for (Milestone m : MilestoneRegistry.getAll()) {
            tag.putBoolean(PersistentDataKeys.milestoneNbtKey(m.id()), progress.isMilestoneUnlocked(m.id()));
        }
        stack.setTag(tag);
    }

    // ===== 旧 NBT 迁移 =====

    private static final String OLD_STAGE_KEY = PersistentDataKeys.OLD_STAGE_KEY;

    public static void migrateOldStage(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(OLD_STAGE_KEY)) {
            int oldStage = tag.getInt(OLD_STAGE_KEY);
            if (oldStage >= 1) tag.putBoolean(PersistentDataKeys.milestoneNbtKey("nether"), true);
            if (oldStage >= 2) tag.putBoolean(PersistentDataKeys.milestoneNbtKey("wither"), true);
            if (oldStage >= 3) tag.putBoolean(PersistentDataKeys.milestoneNbtKey("warden"), true);
            if (oldStage >= 4) tag.putBoolean(PersistentDataKeys.milestoneNbtKey("dragon"), true);
            if (oldStage >= 5) tag.putBoolean(PersistentDataKeys.milestoneNbtKey("elytra"), true);
            tag.remove(OLD_STAGE_KEY);
            stack.setTag(tag);
        }
    }

    // ===== 计分板 =====

    public static void updateScoreboard(Player player, boolean unlocked) {
        if (!(player instanceof ServerPlayer)) return;
        Scoreboard sb = player.getScoreboard();
        Objective obj = sb.getObjective(UNLOCK_OBJECTIVE);
        if (obj == null) {
            obj = sb.addObjective(UNLOCK_OBJECTIVE, ObjectiveCriteria.DUMMY,
                Component.literal("MME Adventure"), ObjectiveCriteria.RenderType.INTEGER);
        }
        sb.getOrCreatePlayerScore(player.getScoreboardName(), obj).setScore(unlocked ? 1 : 0);
    }

    // ===== 里程碑解锁（统一入口，由 AdvancementEventHandler 调用） =====

    /**
     * 授予里程碑并同步所有数据。幂等——已解锁的里程碑不会重复处理。
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
            if (progress.isAbilityEnabled("soar") && !player.getAbilities().mayfly
                && !player.getAbilities().instabuild && !player.isSpectator()) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }
            syncCapabilityToPersistent(player, progress);
            syncAllAdventureItemNbt(player, progress);
            syncToClient(player);

            ServerLevel level = player.serverLevel();
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
            level.sendParticles(ParticleTypes.END_ROD,
                player.getX(), player.getY() + 1.5, player.getZ(),
                30, 0.5, 0.5, 0.5, 0.1);
            player.displayClientMessage(
                Component.translatable("milestone.adventure_power." + milestoneId).withStyle(ChatFormatting.GREEN), true);

            // ★ 全部里程碑达成 → 冒险的开始 自动替换为 冒险的终点 + 终极成就
            // 不限制最后一个里程碑必须是 ELYTRA，适应任意解锁顺序
            if (progress.areAllMilestonesUnlocked() && !progress.isFullyUnlocked()) {
                replaceBeginWithEnd(player);
                progress.activateFullyUnlocked();
                updateScoreboard(player, true);
                syncCapabilityToPersistent(player, progress);
                syncToClient(player);
            }
        });
    }

    // ===== 冒险饰品替换 =====

    /** 全里程碑达成时，将冒险的开始替换为冒险的终点。
     *  搜索顺序：Curios → 背包 → 盔甲 → 副手 */
    private static void replaceBeginWithEnd(Player player) {
        ItemStack endItem = new ItemStack(ModItems.ADVENTURE_END.get());

        // 1. 优先搜索 Curios 槽位（正常佩戴位置）
        boolean[] replaced = {false};
        ItemInventoryHelper.forEachCuriosSlot(player, (handler, i, stack) -> {
            if (!replaced[0] && stack.is(ModItems.ADVENTURE_BEGIN.get())) {
                replaceStack(stack, endItem);
                handler.getStacks().setStackInSlot(i, endItem);
                replaced[0] = true;
            }
        });

        // 2. 兜底搜索背包/盔甲/副手
        if (!replaced[0]) {
            ItemInventoryHelper.forEachInventoryList(player, list -> {
                if (!replaced[0]) {
                    replaced[0] = replaceInList(list, endItem);
                }
            });
        }

        if (replaced[0]) {
            player.displayClientMessage(
                Component.translatable("msg.adventure_power.curio_evolved").withStyle(ChatFormatting.GOLD),
                false);
        }

        // 激活完全解锁
        getAdventureProgress(player).ifPresent(progress -> {
            progress.activateFullyUnlocked();
            updateScoreboard(player, true);
            syncCapabilityToPersistent(player, progress);
            syncToClient(player);
        });
    }

    /** 在物品列表中查找冒险的开始并替换 */
    private static boolean replaceInList(NonNullList<ItemStack> list, ItemStack endItem) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).is(ModItems.ADVENTURE_BEGIN.get())) {
                replaceStack(list.get(i), endItem);
                list.set(i, endItem);
                return true;
            }
        }
        return false;
    }

    /** 将旧物品的 NBT 迁移到新物品 */
    private static void replaceStack(ItemStack oldStack, ItemStack newStack) {
        CompoundTag oldTag = oldStack.getOrCreateTag();
        CompoundTag newTag = newStack.getOrCreateTag();
        for (String key : oldTag.getAllKeys()) {
            newTag.put(key, oldTag.get(key));
        }
    }

    // ===== 里程碑触发事件 =====

    @SubscribeEvent
    public static void onDimensionChange(PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        // 维度切换兜底恢复：PlayerEvent.Clone 在部分场景不触发，
        // 或 reviveCaps() 导致 AttachCapabilitiesEvent 重新创建空实例，
        // 此处从 persistent data / 物品 NBT 恢复 Capability 状态
        getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                // 首选从 persistent data 恢复（跨维度可靠保留）
                CompoundTag saved = player.getPersistentData().getCompound(PERSISTENT_KEY);
                if (!saved.isEmpty()) {
                    progress.deserializeNBT(saved);
                }
            }
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                // persistent data 也没有 → 扫描物品 NBT
                recoverProgressFromItems(player, progress);
            }
            syncCapabilityToPersistent(player, progress);
            syncToClient(player);

            // 维度切换后检查是否需要激活冒险者（兜底）
            checkAndActivateAdventurer(player);
        });

        // 下界/凋零/监守者/末影龙/鞘翅等里程碑已由 Advancement 系统处理，
        // 无需在此手动检测。
    }

    // ===== 死亡抗拒 =====

    /**
     * 死亡抗拒：监听玩家死亡事件（HIGHEST 优先级），在能力启用且冷却结束时取消死亡、
     * 回满血，并进入无敌状态。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled("death_defy")) return;

            long currentTime = player.level().getGameTime();
            if (progress.getDeathDefyCooldownEnd() > currentTime) return; // 冷却中

            // 取消死亡
            event.setCanceled(true);

            // 清除所有有害药水效果（必须在 setHealth 之前，否则含降 maxHealth 的效果会导致回不满血）
            player.getActiveEffects().stream()
                .filter(e -> e.getEffect().getCategory() == MobEffectCategory.HARMFUL)
                .map(MobEffectInstance::getEffect)
                .collect(java.util.stream.Collectors.toList())
                .forEach(player::removeEffect);
            player.clearFire();

            // 双轨恢复：取 getMaxHealth() 和 20 的较大值
            // 上限 — 支持 Vitality 等能力提升的最大生命值
            // 下限 — 防止外部模组（如亚波伦结界）临时污染 maxHealth 导致复活后血量过低
            float restoreHealth = Math.max(20.0F, player.getMaxHealth());
            HealthUtil.setHealthDirect(player, restoreHealth);
            player.setHealth(restoreHealth);

            // 写入无敌和冷却结束时间
            long invulEnd = currentTime + ModConfig.DEATH_DEFY_INVUL_DURATION.get();
            long cooldownEnd = currentTime + ModConfig.DEATH_DEFY_COOLDOWN_DURATION.get();
            progress.setDeathDefyInvulEnd(invulEnd);
            progress.setDeathDefyCooldownEnd(cooldownEnd);

            syncCapabilityToPersistent(player, progress);
            syncToClient(player);

            // 觉醒：触发死亡抗拒时自动释放一次免费旅者审判
            if (progress.isFullyUnlocked() && progress.isAbilityEnabled("active_skill")) {
                com.ayin90723.adventure_power.skill.ActiveSkillHandler.executeJudgment(
                    (net.minecraft.server.level.ServerPlayer) player);
            }

            if (player.level() instanceof ServerLevel serverLevel) {
                serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
                serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    player.getX(), player.getY() + 1.5, player.getZ(),
                    50, 0.5, 0.5, 0.5, 0.3);
            }
        });
    }

    // ===== 每 Tick 处理（Buff延长 / 环境免疫 / 受击坚韧 / 冒险者激活 / 终点全解锁） =====

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent event) {
        if (event.phase != Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;

        // 开局安全网：补发冒险饰品（若丢失）+ 自动激活冒险者
        if (!verifiedBeginItem.contains(player.getUUID())) {
            verifiedBeginItem.add(player.getUUID());
            giveAdventureBeginIfNeeded(player);
            checkAndActivateAdventurer(player);
        }

        var progressOpt = getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        long currentTime = player.level().getGameTime();

        // 测试便捷入口：持有冒险的终点 → 自动全解锁（每 tick 检查，已解锁则跳过）
        if (!progress.isFullyUnlocked() && playerHasAdventureEnd(player)) {
            if (!progress.isAdventurer()) {
                progress.activateAdventurer();
            }
            for (Milestone m : MilestoneRegistry.getAll()) {
                progress.unlockMilestone(m.id());
            }
            progress.activateFullyUnlocked();
            updateScoreboard(player, true);
            syncCapabilityToPersistent(player, progress);
            syncAllAdventureItemNbt(player, progress);
            syncToClient(player);

            // 翱翔飞行立即同步：fullyUnlocked 不等 PlayerStateHandler 下一 tick，
            // 避免两处 TickEvent.Phase.END handler 执行顺序不确定导致的竞态——
            // 若 PlayerStateHandler 先执行，soar 的 else 分支会剥离 mayfly 并发送
            // mayfly=false 到客户端，之后此处再发送 mayfly=true 覆盖（TCP 保序）。
            if (progress.isAbilityEnabled("soar") && !player.getAbilities().mayfly
                && !player.getAbilities().instabuild && !player.isSpectator()) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }

            // 所有里程碑已在上面解锁，无需再授予成就
        }

        // 门禁：非冒险者跳过后续的每 tick 能力处理（Buff 延长/环境免疫/受击坚韧/庇护过期）
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

        // Buff 延长（每 3 秒）
        if (progress.isAbilityEnabled("perpetual_blessing")) {
            long lastCheck = lastBuffCheck.getOrDefault(player.getUUID(), -1L);
            if (lastCheck == -1L) {
                lastBuffCheck.put(player.getUUID(), currentTime);
            } else if (currentTime - lastCheck >= BUFF_CHECK_INTERVAL) {
                lastBuffCheck.put(player.getUUID(), currentTime);
                extendBeneficialEffects(player);
            }
        } else {
            lastBuffCheck.remove(player.getUUID());
        }

        // 环境免疫：每 tick 清除火焰
        if (progress.isAbilityEnabled("env_immunity")) {
            player.clearFire();
        }

        // 受击坚韧：超过 5 秒无受伤 → 层数归零
        if (progress.isAbilityEnabled("resilience")) {
            long lastHurt = progress.getLastHurtTime();
            if (lastHurt > 0 && currentTime - lastHurt >= ModConfig.RESILIENCE_RESET_TICKS.get()) {
                progress.setResilienceStacks(0);
                progress.setLastHurtTime(0);
            }
        }

        // 庇护无敌过期后清除（避免残留值，同步客户端和持久数据）
        if (progress.getSanctuaryInvulEnd() > 0 && currentTime >= progress.getSanctuaryInvulEnd()) {
            progress.setSanctuaryInvulEnd(0);
            syncCapabilityToPersistent(player, progress);
            syncToClient(player);
        }
    }

    private static void extendBeneficialEffects(Player player) {
        boolean extended = false;
        Set<String> excluded = getBuffExclusionSet(player);
        int minDuration = ModConfig.BUFF_MIN_DURATION.get();
        int extendAmount = ModConfig.BUFF_EXTEND_AMOUNT.get();
        int threshold = minDuration + extendAmount;
        for (MobEffectInstance effect : new ArrayList<>(player.getActiveEffects())) {
            if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                String effectId = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()).toString();
                if (excluded.contains(effectId)) continue;
                if (effect.getDuration() < threshold) {
                    extended = true;
                    player.addEffect(new MobEffectInstance(effect.getEffect(), threshold,
                        effect.getAmplifier(), effect.isAmbient(), effect.isVisible(), effect.showIcon()));
                }
            }
        }
        if (extended && player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.GLOW,
                player.getX(), player.getY() + 1.5, player.getZ(), 15, 0.5, 0.5, 0.5, 0.1);
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
            if (!progress.isAbilityEnabled("perpetual_blessing")) return;
            MobEffect effect = event.getEffect();
            if (effect != null && effect.getCategory() == MobEffectCategory.BENEFICIAL) {
                event.setCanceled(true);
            }
        });
    }

    // ===== Buff 排除管理 =====

    public static final String BUFF_BLACKLIST_KEY = PersistentDataKeys.BUFF_BLACKLIST_KEY;

    public static void toggleBuffExclusion(Player player, String effectId) {
        CompoundTag root = player.getPersistentData();
        CompoundTag blacklist = root.getCompound(BUFF_BLACKLIST_KEY);
        if (blacklist.getBoolean(effectId)) {
            blacklist.remove(effectId);
        } else {
            blacklist.putBoolean(effectId, true);
        }
        if (blacklist.isEmpty()) {
            root.remove(BUFF_BLACKLIST_KEY);
        } else {
            root.put(BUFF_BLACKLIST_KEY, blacklist);
        }
    }

    /**
     * 获取玩家排除列表。每次调用从 persistent data 重新解析（集合通常很小，性能影响可忽略）。
     * 若未来排除列表显著增长，可在 AdventureProgress Capability 中缓存。
     */
    public static Set<String> getBuffExclusionSet(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(BUFF_BLACKLIST_KEY)) return Set.of();
        CompoundTag blacklist = root.getCompound(BUFF_BLACKLIST_KEY);
        Set<String> set = new HashSet<>();
        for (String key : blacklist.getAllKeys()) {
            if (blacklist.getBoolean(key)) set.add(key);
        }
        return set;
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerLoggedOutEvent event) {
        lastBuffCheck.remove(event.getEntity().getUUID());
        verifiedBeginItem.remove(event.getEntity().getUUID());
    }

    // ===== Tooltip 门禁提示 =====
    // TODO: 新模组不再使用附魔门禁，改为冒险饰品 tooltip 显示已解锁能力

    /* 旧附魔 tooltip 代码已移除——能力现在是玩家内在的，不绑装备
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) { ... }
    */
}
