package com.ayin90723.adventure_power.util;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 物品栏遍历工具类。
 * <p>
 * 消除 AdventureProgressCapability 中重复的"背包+盔甲+副手+Curios"遍历样板，
 * 提供统一的遍历方法。
 */
public final class ItemInventoryHelper {

    private ItemInventoryHelper() {}

    /**
     * 遍历玩家物品栏的三个列表（背包/盔甲/副手），
     * 对每个列表整体执行指定操作。
     * <p>
     * 适用于需要对列表进行批量操作的场景（如 {@code syncStackList}），
     * 调用方自行决定如何遍历每个列表中的 ItemStack。
     *
     * @param player 目标玩家
     * @param action 对每个列表执行的操作
     */
    public static void forEachInventoryList(Player player, Consumer<NonNullList<ItemStack>> action) {
        action.accept(player.getInventory().items);
        action.accept(player.getInventory().armor);
        action.accept(player.getInventory().offhand);
    }

    /**
     * 遍历玩家物品栏的三个列表（背包/盔甲/副手）中每个非空 ItemStack。
     * <p>
     * 不包含 Curios 饰品槽。适用于只需要扫描背包/盔甲/副手的场景。
     *
     * @param player 目标玩家
     * @param action 对每个非空 ItemStack 执行的操作
     */
    public static void forEachInventorySlot(Player player, Consumer<ItemStack> action) {
        forEachInventoryList(player, list -> {
            for (ItemStack stack : list) {
                if (!stack.isEmpty()) {
                    action.accept(stack);
                }
            }
        });
    }

    /**
     * 遍历玩家所有冒险相关槽位中每个非空 ItemStack：
     * <ul>
     *   <li>背包（{@code inventory.items}）</li>
     *   <li>盔甲（{@code inventory.armor}）</li>
     *   <li>副手（{@code inventory.offhand}）</li>
     *   <li>Curios 饰品槽（所有已解锁的槽位类型）</li>
     * </ul>
     *
     * @param player 目标玩家
     * @param action 对每个非空 ItemStack 执行的操作
     */
    public static void forEachAdventureSlot(Player player, Consumer<ItemStack> action) {
        forEachInventorySlot(player, action);
        forEachCuriosSlot(player, (handler, slot, stack) -> action.accept(stack));
    }

    /**
     * 判断玩家所有冒险相关槽位（背包 + 盔甲 + 副手 + Curios）中
     * 是否存在满足指定条件的物品。
     * <p>
     * 用于 {@code playerHasAdventureItem} / {@code playerHasAdventureEnd}
     * 等只需要判断存在性的场景。短路返回。
     *
     * @param player    目标玩家
     * @param predicate 判断条件
     * @return 是否存在匹配的物品
     */
    public static boolean hasAnyAdventureSlot(Player player, Predicate<ItemStack> predicate) {
        // 背包 + 盔甲 + 副手
        for (NonNullList<ItemStack> list : new NonNullList[]{
                player.getInventory().items,
                player.getInventory().armor,
                player.getInventory().offhand}) {
            for (ItemStack stack : list) {
                if (predicate.test(stack)) return true;
            }
        }
        // Curios
        return CuriosApi.getCuriosInventory(player).resolve()
                .map(inv -> {
                    for (var entry : inv.getCurios().entrySet()) {
                        var handler = entry.getValue();
                        for (int i = 0; i < handler.getStacks().getSlots(); i++) {
                            if (predicate.test(handler.getStacks().getStackInSlot(i))) return true;
                        }
                    }
                    return false;
                })
                .orElse(false);
    }

    /**
     * 遍历 Curios 所有饰品槽，携带 handler 引用和槽位索引以便写回操作。
     * <p>
     * 适用于需要调用 {@code handler.getStacks().setStackInSlot(i, stack)} 的场景，
     * 如同步 NBT 写回、替换物品等。
     *
     * @param player 目标玩家
     * @param action 对每个非空 ItemStack 执行的操作，参数为 (handler, slotIndex, stack)
     */
    public static void forEachCuriosSlot(Player player, CuriosSlotConsumer action) {
        CuriosApi.getCuriosInventory(player).resolve().ifPresent(inv ->
                inv.getCurios().forEach((id, handler) -> {
                    for (int i = 0; i < handler.getStacks().getSlots(); i++) {
                        ItemStack stack = handler.getStacks().getStackInSlot(i);
                        if (!stack.isEmpty()) {
                            action.accept(handler, i, stack);
                        }
                    }
                })
        );
    }

    /**
     * Curios 槽位遍历的函数式接口。
     * 参数：(handler, slotIndex, stack)，支持写回操作。
     */
    @FunctionalInterface
    public interface CuriosSlotConsumer {
        void accept(ICurioStacksHandler handler, int slot, ItemStack stack);
    }
}