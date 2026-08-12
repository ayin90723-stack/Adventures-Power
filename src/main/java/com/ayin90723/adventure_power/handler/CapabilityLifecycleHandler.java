package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.item.ModItems;
import com.ayin90723.adventure_power.util.AdventureItemNbtUtil;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import com.ayin90723.adventure_power.util.ScoreboardUtil;
import com.ayin90723.adventure_power.util.SyncUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * Capability 生命周期处理 - 登录/克隆/维度切换的数据恢复与首次发放。
 * <p>
 * 从 AdventureProgressCapability 拆出。处理：
 * <ul>
 *   <li>{@link #onPlayerClone} 死亡/跨维度克隆时从 persistentData / 旧 Capability / 物品 NBT 三层恢复</li>
 *   <li>{@link #onPlayerLogin} 登录时旧数据迁移 + 兜底恢复 + 首次发放冒险饰品</li>
 *   <li>{@link #onDimensionChange} 维度切换兜底恢复</li>
 *   <li>{@link #giveAdventureBeginIfNeeded} 补发冒险的开始/终点</li>
 *   <li>{@link #checkAndActivateAdventurer} 持有冒险饰品但未激活时自动激活</li>
 * </ul>
 * give/check 为 public 供 PlayerTickHandler 开局安全网调用。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class CapabilityLifecycleHandler {

    // ===== Clone（死亡/跨维度） =====

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player oldPlayer = event.getOriginal();
        Player newPlayer = event.getEntity();

        // 恢复策略（优先级：活跃 Capability > persistentData 快照 > 物品 NBT）：
        // persistentData 快照只在登录/解锁等时机写入，登录后必然过期
        // （能力开关、备份血量、受击坚韧层数等变更点不写快照），若优先用它恢复
        // 会回滚玩家最近设置（能力开关被重置、backupHealth 回退触发 TrueHealth 误判）。
        // 旧 Capability 才是活跃数据；先 reviveCaps() 处理死亡时 LazyOptional 失效的情况。
        oldPlayer.reviveCaps();
        CompoundTag saved = oldPlayer.getCapability(AdventureProgressCapability.CAPABILITY)
            .map(IAdventureProgress::serializeNBT).orElse(new CompoundTag());
        if (saved.isEmpty()) {
            // Capability 未附加/已失效 -> 回退 persistentData 快照
            saved = oldPlayer.getPersistentData().getCompound(PersistentDataKeys.ADVENTURE_PROGRESS);
        }

        if (!saved.isEmpty()) {
            final CompoundTag data = saved;
            newPlayer.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(newCap ->
                newCap.deserializeNBT(data));
        }

        // 时间基准平移：ServerLevel.getGameTime() 每维度独立，跨维度（含死亡重生到异维度）
        // 后 Capability 中的计时字段与当前维度时钟失配——主世界触发的死亡抗拒冷却进入
        // 时间更小的下界会被"延长"、下界进主世界则冷却失效；受击坚韧 lastHurtTime 错位
        // 会导致层数永不过期。按新旧维度时间差平移各 end 字段（同维度 delta=0 无操作）。
        long timeDelta = newPlayer.level().getGameTime() - oldPlayer.level().getGameTime();
        if (timeDelta != 0) {
            newPlayer.getCapability(AdventureProgressCapability.CAPABILITY)
                .ifPresent(p -> shiftTimers(p, timeDelta));
        }

        // 维度切换（非死亡 clone）保留影杀影子血量进度；死亡清零为刻意设计（重生后重新积累）
        if (!event.isWasDeath()) {
            CompoundTag oldShadowData = oldPlayer.getPersistentData().getCompound(PersistentDataKeys.SHADOW_HP_DATA);
            if (!oldShadowData.isEmpty()) {
                newPlayer.getPersistentData().put(PersistentDataKeys.SHADOW_HP_DATA, oldShadowData);
            }
        }
        // survive_night「本夜已度过」标记转移（v1.4.0 落盘后需随 Clone 转移：
        // 死亡 respawn 新实体内存 persistentData 为磁盘旧值，用旧玩家内存值覆盖，
        // 防夜间存活后立刻死亡导致标记丢失。v1.4.0 二次审查：无条件覆盖——
        // 旧玩家已消费（内存 key 移除）但磁盘未保存时，contains 判定会漏写，
        // 新玩家从磁盘继承旧 true 标记导致残留）
        newPlayer.getPersistentData().putBoolean(PersistentDataKeys.SURVIVE_NIGHT_KEY,
            oldPlayer.getPersistentData().getBoolean(PersistentDataKeys.SURVIVE_NIGHT_KEY));
        // 影杀影子血量过期时间同样平移（攻击者 level 切换后过期判定基准变化——
        // 不平移会导致下界积累的影子血量进主世界立即过期，或主世界的永不过期）
        if (timeDelta != 0) {
            CompoundTag shadowData = newPlayer.getPersistentData().getCompound(PersistentDataKeys.SHADOW_HP_DATA);
            if (!shadowData.isEmpty()) {
                for (String key : shadowData.getAllKeys()) {
                    CompoundTag entry = shadowData.getCompound(key);
                    long end = entry.getLong(PersistentDataKeys.SHADOW_HP_END_TIME);
                    if (end > 0) {
                        entry.putLong(PersistentDataKeys.SHADOW_HP_END_TIME, end + timeDelta);
                    }
                }
                newPlayer.getPersistentData().put(PersistentDataKeys.SHADOW_HP_DATA, shadowData);
            }
        }

        // 兜底：以上均未恢复 -> 扫描物品 NBT。
        // 统一扫 oldPlayer（event.getOriginal()）：1.20.1 的 restoreFrom 在 Clone 事件前
        // 已完成物品栏复制（死亡 respawn 时 newPlayer 物品栏非空），但维度切换路径
        // newPlayer 物品栏为空——oldPlayer 在事件时点物品栏始终完整，两路径均正确
        newPlayer.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                AdventureItemNbtUtil.recoverProgressFromItems(oldPlayer, progress);
            }
            if (progress.isAdventurer() || progress.isFullyUnlocked()) {
                SyncUtil.syncCapabilityToPersistent(newPlayer, progress);
            }
            // v1.4.0：物品 NBT 兜底恢复激活 fullyUnlocked 时同步计分板——
            // 否则"capability 丢失 + 死亡"深边界下计分板滞留 0 直到下次登录
            if (progress.isFullyUnlocked()) {
                ScoreboardUtil.updateScoreboard(newPlayer, true);
            }
        });

        // 死亡/换维度后若持有冒险饰品但未激活，自动激活
        checkAndActivateAdventurer(newPlayer);


        // 重生/穿越末地后客户端 Capability 不会自动同步，
        // 需要手动推送 AdventureSyncPacket 确保面板和 tooltip 状态正确
        SyncUtil.syncToClient(newPlayer);
    }

    // ===== Respawn（重生完成）=====

    /** 玩家重生完成后补推 Capability 同步。
     *  onPlayerClone 时发的同步包可能因客户端未完成 respawn 而未应用到新玩家，
     *  导致刚解锁的能力（如初尝败绩解锁的 void_step）不立即生效，需开关能力才同步。
     *  PlayerRespawnEvent 在重生完成后触发，客户端已 ready，此时同步可靠。 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerRespawnEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SyncUtil.syncToClient(player);
    }

    // ===== 首次发放 + 旧数据迁移 =====

    @SubscribeEvent
    public static void onPlayerLogin(PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            // 1. 始终尝试从持久数据恢复（如果还没恢复的话）
            boolean hasOldPersistent = player.getPersistentData().contains(PersistentDataKeys.OLD_UNLOCKED_KEY);
            if (hasOldPersistent) {
                if (player.getPersistentData().getBoolean(PersistentDataKeys.OLD_UNLOCKED_KEY)) {
                    progress.activateFullyUnlocked();
                    ScoreboardUtil.updateScoreboard(player, true);
                }
                player.getPersistentData().remove(PersistentDataKeys.OLD_UNLOCKED_KEY);
            }

            // 2. Forge 可能未反序列化 capability -> 从 PERSISTENT_KEY 兜底恢复
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                CompoundTag saved = player.getPersistentData().getCompound(PersistentDataKeys.ADVENTURE_PROGRESS);
                if (!saved.isEmpty()) {
                    progress.deserializeNBT(saved);
                }
            }

            // 3. 如果 Capability 中还没有 adventurer 状态，扫描物品 NBT 尝试恢复
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                AdventureItemNbtUtil.recoverProgressFromItems(player, progress);
            }

            // 4. 清理旧 AdventureStage NBT（可能残留在物品上）
            AdventureItemNbtUtil.cleanOldStageNbt(player);

            // 5. 计分板写 0/1（未解锁写 0，便于命令方块区分「未解锁」与「从未初始化」）
            ScoreboardUtil.updateScoreboard(player, progress.isFullyUnlocked());

            SyncUtil.syncCapabilityToPersistent(player, progress);
        });

        giveAdventureBeginIfNeeded(player);
        checkAndActivateAdventurer(player);


        // 已激活冒险者登录：补跑成就追赶（catchUpMissedMilestones 仅由 checkAndActivateAdventurer
        // 在首次激活时调用，已激活玩家登录/数据包 /reload 新增 advancement 里程碑后
        // 不会重发成就事件——此处兜底，幂等（已解锁跳过））
        if (player instanceof ServerPlayer sp) {
            AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                if (progress.isAdventurer() || progress.isFullyUnlocked()) {
                    AdvancementEventHandler.catchUpMissedMilestones(sp);
                }
            });
        }

        SyncUtil.syncToClient(player);
    }

    /** 时间基准平移：把 Capability 中所有 gameTime 时间戳字段按新旧维度时间差平移。
     *  仅平移 >0 的激活字段（0 = 未激活，平移无意义）。
     *  注意：休养生息/恩赐永驻等 handler 的静态 Map 缓存（脱战/续期检查）为短时缓存，
     *  跨维度后自愈（最多一次检查提前/延迟），无需平移。 */
    private static void shiftTimers(IAdventureProgress p, long delta) {
        if (p.getDeathDefyInvulEnd() > 0) p.setDeathDefyInvulEnd(p.getDeathDefyInvulEnd() + delta);
        if (p.getDeathDefyCooldownEnd() > 0) p.setDeathDefyCooldownEnd(p.getDeathDefyCooldownEnd() + delta);
        if (p.getJudgmentCooldownEnd() > 0) p.setJudgmentCooldownEnd(p.getJudgmentCooldownEnd() + delta);
        if (p.getSanctuaryCooldownEnd() > 0) p.setSanctuaryCooldownEnd(p.getSanctuaryCooldownEnd() + delta);
        if (p.getSanctuaryInvulEnd() > 0) p.setSanctuaryInvulEnd(p.getSanctuaryInvulEnd() + delta);
        if (p.getActiveSkillGcdEnd() > 0) p.setActiveSkillGcdEnd(p.getActiveSkillGcdEnd() + delta);
        if (p.getLastHurtTime() > 0) p.setLastHurtTime(p.getLastHurtTime() + delta);
    }

    /** 补发冒险的开始/终点（若玩家应持有但丢失）。public 供 PlayerTickHandler 开局安全网调用 */
    public static void giveAdventureBeginIfNeeded(Player player) {
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isFullyUnlocked()) {
                // 完全解锁 -> 应持有冒险的终点：已持有终点则 OK；持有「开始」则升级替换
                //（迁移/恢复路径的玩家仍持开始不升级的自愈，与 grantMilestone 级联行为一致）；
                // 都无则补发终点
                if (AdventureItemNbtUtil.playerHasAdventureEnd(player)) return;
                if (AdventureItemNbtUtil.playerHasAdventureItem(player)) {
                    AdventureItemNbtUtil.replaceBeginWithEnd(player);
                    return;
                }
                ItemStack endStack = new ItemStack(ModItems.ADVENTURE_END.get());
                AdventureItemNbtUtil.writeMilestonesToStack(endStack, progress);
                if (!player.getInventory().add(endStack)) {
                    player.spawnAtLocation(endStack);
                }
                return;
            }

            if (progress.isAdventurer()) {
                // 冒险者 -> 应持有冒险的开始，丢失则补发
                if (AdventureItemNbtUtil.playerHasAdventureItem(player)) return;
                ItemStack beginStack = new ItemStack(ModItems.ADVENTURE_BEGIN.get());
                AdventureItemNbtUtil.writeMilestonesToStack(beginStack, progress);
                if (!player.getInventory().add(beginStack)) {
                    player.spawnAtLocation(beginStack);
                }
                return;
            }

            // 未激活冒险者 -> 首次发放（用 GOT_BEGIN_KEY 防止重复）
            CompoundTag persistent = player.getPersistentData();
            if (persistent.getBoolean(PersistentDataKeys.GOT_BEGIN_KEY)) return;
            if (AdventureItemNbtUtil.playerHasAdventureItem(player)) {
                persistent.putBoolean(PersistentDataKeys.GOT_BEGIN_KEY, true);
                return;
            }

            ItemStack stack = new ItemStack(ModItems.ADVENTURE_BEGIN.get());
            boolean added = player.getInventory().add(stack);
            if (!added) {
                player.spawnAtLocation(stack);
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(
                        Component.translatable("msg.adventure_power.inventory_full")
                            .withStyle(ChatFormatting.GOLD), false);
                }
            } else if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(
                    Component.translatable("msg.adventure_power.got_begin")
                        .withStyle(ChatFormatting.GOLD), false);
            }
            persistent.putBoolean(PersistentDataKeys.GOT_BEGIN_KEY, true);
        });
    }

    /** 若玩家已佩戴冒险饰品但尚未激活冒险者，自动授予根成就。public 供 PlayerTickHandler 调用 */
    public static void checkAndActivateAdventurer(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isAdventurer()) return;
            // 版本飞升可能导致 fullyUnlocked=true 但 adventurer=false（数据不一致），
            // 此时直接补激活 adventurer，避免 onLivingHurt 等只查 isAdventurer 的门禁误拦。
            if (progress.isFullyUnlocked()) {
                progress.activateAdventurer();
                SyncUtil.syncCapabilityToPersistent(player, progress);
                SyncUtil.syncToClient(player);
                return;
            }
            if (AdventureItemNbtUtil.playerHasAdventureItem(player)) {
                progress.activateAdventurer();
                SyncUtil.syncCapabilityToPersistent(player, progress);
                AdventureItemNbtUtil.syncAllAdventureItemNbt(player, progress);
                SyncUtil.syncToClient(player);
                AdvancementEventHandler.catchUpMissedMilestones(sp);
            }
        });
    }

    // ===== 维度切换兜底 =====

    @SubscribeEvent
    public static void onDimensionChange(PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        // 时间基准平移：维度切换（传送门/末地出口）不触发 PlayerEvent.Clone（1.20.1 仅死亡
        // respawn 触发 Clone），但 ServerLevel.getGameTime() 每维度独立——CD/无敌/受击坚韧等
        // 计时字段与当前维度时钟失配（主世界触发进下界被延长、反向秒失效），需按新旧维度时间差平移。
        // PlayerChangedDimensionEvent 双向触发（已从 binpatched jar 字节码验证：changeDimension
        // normal 分支与 teleportTo（m_8999_，末地出口 credits 路径）各 fire 一次），
        // 故进末地/出末地/传送门均在事件内平移，无需额外 tick 检测
        if (player.getServer() != null && event.getFrom() != null) {
            ServerLevel fromLevel = player.getServer().getLevel(event.getFrom());
            if (fromLevel != null) {
                long timeDelta = player.level().getGameTime() - fromLevel.getGameTime();
                if (timeDelta != 0) {
                    player.getCapability(AdventureProgressCapability.CAPABILITY)
                        .ifPresent(p -> shiftTimers(p, timeDelta));
                    // 影杀影子血量过期时间同理平移（攻击者维度切换后过期判定基准变化）
                    CompoundTag shadowData = player.getPersistentData().getCompound(PersistentDataKeys.SHADOW_HP_DATA);
                    if (!shadowData.isEmpty()) {
                        for (String key : shadowData.getAllKeys()) {
                            CompoundTag entry = shadowData.getCompound(key);
                            long end = entry.getLong(PersistentDataKeys.SHADOW_HP_END_TIME);
                            if (end > 0) {
                                entry.putLong(PersistentDataKeys.SHADOW_HP_END_TIME, end + timeDelta);
                            }
                        }
                        player.getPersistentData().put(PersistentDataKeys.SHADOW_HP_DATA, shadowData);
                    }
                }
            }
        }

        // 维度切换兜底恢复：PlayerEvent.Clone 在部分场景不触发，
        // 此处从 persistent data / 物品 NBT 恢复 Capability 状态
        //（注：reviveCaps() 仅置 valid 标志，不重新触发 AttachCapabilitiesEvent——
        // 旧 CapabilityDispatcher 原样保留，此处恢复的是持久化快照而非空实例）
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                // 首选从 persistent data 恢复（跨维度可靠保留）
                CompoundTag saved = player.getPersistentData().getCompound(PersistentDataKeys.ADVENTURE_PROGRESS);
                if (!saved.isEmpty()) {
                    progress.deserializeNBT(saved);
                }
            }
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
                // persistent data 也没有 -> 扫描物品 NBT
                AdventureItemNbtUtil.recoverProgressFromItems(player, progress);
            }
            SyncUtil.syncCapabilityToPersistent(player, progress);
            SyncUtil.syncToClient(player);

            // 维度切换后检查是否需要激活冒险者（兜底）
            checkAndActivateAdventurer(player);
        });


        // 下界/凋零/监守者/末影龙/鞘翅等里程碑已由 Advancement 系统处理，
        // 无需在此手动检测。
    }
}
