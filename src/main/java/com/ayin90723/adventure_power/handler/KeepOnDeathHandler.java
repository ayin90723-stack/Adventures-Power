package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.item.ModItems;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.theillusivec4.curios.api.event.DropRulesEvent;
import top.theillusivec4.curios.api.type.capability.ICurio;

import java.util.Iterator;

/**
 * 冒险饰品死亡不掉落 — 冒险的开始 / 冒险的终点在玩家死亡时保留。
 * <p>
 * 玩家可自由取下（Q 丢弃、放入容器均不受影响），但无论物品位于 Curios 槽位还是
 * 背包/盔甲/副手，死亡时都不会掉落：
 * <ul>
 *   <li><b>Curios 槽位（佩戴）</b>：{@link DropRulesEvent} 注册 {@link ICurio.DropRule#ALWAYS_KEEP}，
 *       物品留在槽位不进掉落列表，Curios 的 playerClone 将槽位数据复制给重生玩家（保持佩戴）</li>
 *   <li><b>背包/盔甲/副手（取下）</b>：{@link LivingDropsEvent} 将饰品移出掉落列表，
 *       栈暂存死亡玩家 persistentData 的 {@code PlayerPersisted} 子 tag（Forge Clone
 *       自动复制给重生实体），{@link CapabilityLifecycleHandler#onPlayerClone} 在死亡
 *       Clone 时写回新玩家背包</li>
 * </ul>
 * <p>
 * <b>暂存恢复而非还回旧实体背包（审查修 P1#3，字节码实锤）</b>：1.20.1 死亡重生走
 * {@code respawn(player, false)}——{@code ServerPlayer.restoreFrom(player, false)} 在
 * keepInventory=false（原版默认）且非旁观者时<b>不复制物品栏</b>。还回旧实体背包的
 * 物品会滞留在被丢弃的旧实体上凭空消失；暂存到 PlayerPersisted 是唯一可靠通道。
 * <p>
 * 1.20.1 原版无 keepOnDeath NBT 机制（Inventory.dropAll 无条件清空全部槽位，已从字节码验证），
 * 故统一走事件层拦截。两处均为服务端事件，客户端无副作用。
 * 掉落事件被其他模组取消时（receiveCanceled 默认 false）本处理器不执行——饰品留在
 * 掉落列表由取消方（坟墓/墓碑类模组）接管。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class KeepOnDeathHandler {

    /** 是否为冒险饰品（冒险的开始 / 冒险的终点） */
    private static boolean isAdventureItem(ItemStack stack) {
        Item item = stack.getItem();
        return item == ModItems.ADVENTURE_BEGIN.get() || item == ModItems.ADVENTURE_END.get();
    }

    /** Curios 死亡掉落规则：冒险饰品一律保留在槽位（ALWAYS_KEEP 优先于 keepInventory/keepCurios 配置） */
    @SubscribeEvent
    public static void onDropRules(DropRulesEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof Player)) return;
        event.addOverride(KeepOnDeathHandler::isAdventureItem, ICurio.DropRule.ALWAYS_KEEP);
    }

    /** 玩家死亡掉落拦截：背包/盔甲/副手中的冒险饰品移出掉落列表，暂存 PlayerPersisted（重生 Clone 消费）。 */
    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (event.isCanceled()) return; // 被其他模组接管（坟墓/墓碑类）时不干预
        if (!(event.getEntity() instanceof Player player)) return;

        ListTag kept = new ListTag();
        Iterator<ItemEntity> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemEntity entity = it.next();
            if (!isAdventureItem(entity.getItem())) continue;
            kept.add(entity.getItem().save(new CompoundTag()));
            it.remove();
        }
        if (kept.isEmpty()) return;
        // 写入 PlayerPersisted 子 tag（Forge Clone 只自动复制该子 tag——设计约定 13）
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.put(PersistentDataKeys.KEEP_CURIO_STACK_KEY, kept);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /**
     * 消费暂存的饰品栈写回新玩家背包（由 {@code CapabilityLifecycleHandler.onPlayerClone}
     * 在 {@code wasDeath} 时调用——需访问 oldPlayer 的 PlayerPersisted，不依赖 Forge 的
     * 复制时序）。背包满时掉在重生点兜底，物品不消失。
     */
    static void restoreKeptCurioStacks(Player oldPlayer, Player newPlayer) {
        CompoundTag persisted = oldPlayer.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        ListTag kept = persisted.getList(PersistentDataKeys.KEEP_CURIO_STACK_KEY, Tag.TAG_COMPOUND);
        if (kept.isEmpty()) return;
        for (Tag t : kept) {
            if (!(t instanceof CompoundTag stackTag)) continue;
            ItemStack stack = ItemStack.of(stackTag);
            if (stack.isEmpty()) continue;
            if (!newPlayer.getInventory().add(stack)) {
                newPlayer.spawnAtLocation(stack);
            }
        }
        kept.clear();
        persisted.put(PersistentDataKeys.KEEP_CURIO_STACK_KEY, kept);
        oldPlayer.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }
}
