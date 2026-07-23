package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.util.SyncUtil;
import com.ayin90723.adventure_power.item.ModItems;
import com.ayin90723.adventure_power.milestone.Milestone;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 冒险饰品 NBT 工具 - 物品 NBT 扫描/同步/迁移/替换。
 * <p>
 * 管理冒险的开始/终点物品上的里程碑解锁状态 NBT，用于：
 * <ul>
 *   <li>Capability 数据丢失时从物品 NBT 兜底恢复（维度切换/登录/克隆）</li>
 *   <li>解锁里程碑时同步写入物品 NBT（数据三层备份之一）</li>
 *   <li>全解锁时将"冒险的开始"替换为"冒险的终点"</li>
 *   <li>旧版 AdventureStage NBT 迁移到新里程碑格式</li>
 * </ul>
 * <p>
 * 从 AdventureProgressCapability 拆出。syncCapabilityToPersistent/syncToClient/getAdventureProgress
 * 暂回调 AdventureProgressCapability（阶段2 提取 SyncUtil 后改调，消除循环依赖）。
 */
public final class AdventureItemNbtUtil {

    private AdventureItemNbtUtil() {}

    private static final String OLD_STAGE_KEY = PersistentDataKeys.OLD_STAGE_KEY;

    /** 检查玩家是否持有冒险的开始或终点（任意一个） */
    public static boolean playerHasAdventureItem(Player player) {
        return ItemInventoryHelper.hasAnyAdventureSlot(player,
            stack -> stack.is(ModItems.ADVENTURE_BEGIN.get()) || stack.is(ModItems.ADVENTURE_END.get()));
    }

    /** 检查玩家是否持有冒险的终点（仅终点，不包含开始） */
    public static boolean playerHasAdventureEnd(Player player) {
        return ItemInventoryHelper.hasAnyAdventureSlot(player,
            stack -> stack.is(ModItems.ADVENTURE_END.get()));
    }

    /**
     * 从全身物品（背包/盔甲/副手/Curios）中的冒险饰品 NBT 恢复 Capability 里程碑状态。
     * 用于维度切换/登录时 Capability 数据丢失的兜底恢复。
     *
     * @return true 表示成功恢复了至少一个里程碑
     */
    public static boolean recoverProgressFromItems(Player player, IAdventureProgress progress) {
        List<Milestone> all = MilestoneRegistry.getAll();
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

    private static void scanAllItemsForMilestones(Player player, boolean[] found) {
        List<Milestone> all = MilestoneRegistry.getAll();
        ItemInventoryHelper.forEachAdventureSlot(player, stack ->
            scanItemForMilestones(stack, all, found));
    }

    private static void scanItemForMilestones(ItemStack stack, List<Milestone> all, boolean[] found) {
        if (!stack.is(ModItems.ADVENTURE_BEGIN.get()) && !stack.is(ModItems.ADVENTURE_END.get())) return;
        migrateOldStage(stack);
        CompoundTag tag = stack.getOrCreateTag();
        for (int i = 0; i < all.size(); i++) {
            if (tag.getBoolean(PersistentDataKeys.milestoneNbtKey(all.get(i).id()))) {
                found[i] = true;
            }
        }
    }

    /** 将 Capability 里程碑状态同步写入玩家所有冒险饰品 NBT */
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

    /** 向指定冒险饰品写入里程碑解锁状态 NBT */
    public static void writeMilestonesToStack(ItemStack stack, IAdventureProgress progress) {
        CompoundTag tag = stack.getOrCreateTag();
        for (Milestone m : MilestoneRegistry.getAll()) {
            tag.putBoolean(PersistentDataKeys.milestoneNbtKey(m.id()), progress.isMilestoneUnlocked(m.id()));
        }
        stack.setTag(tag);
    }

    // ===== 旧 NBT 迁移 =====

    /** 清理玩家身上冒险饰品的旧 AdventureStage NBT（迁移到新里程碑格式） */
    public static void cleanOldStageNbt(Player player) {
        ItemInventoryHelper.forEachInventorySlot(player, stack -> {
            if (stack.is(ModItems.ADVENTURE_BEGIN.get()) || stack.is(ModItems.ADVENTURE_END.get()))
                migrateOldStage(stack);
        });
    }

    /** 旧 AdventureStage NBT 迁移到新里程碑格式 */
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

    // ===== 冒险饰品替换 =====

    /** 全里程碑达成时，将冒险的开始替换为冒险的终点。
     *  搜索顺序：Curios -> 背包 -> 盔甲 -> 副手 */
    public static void replaceBeginWithEnd(Player player) {
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
                Component.translatable("msg.adventure_power.curio_evolved"),
                false);
        }

        // 激活完全解锁
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            progress.activateFullyUnlocked();
            ScoreboardUtil.updateScoreboard(player, true);
            SyncUtil.syncCapabilityToPersistent(player, progress);
            SyncUtil.syncToClient(player);
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
}
