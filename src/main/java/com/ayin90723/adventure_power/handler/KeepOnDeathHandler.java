package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.item.ModItems;
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
 *   <li><b>背包/盔甲/副手（取下）</b>：{@link LivingDropsEvent} 将饰品掉落实体移出掉落列表并还回
 *       死亡玩家背包（dropAll 已清空全部槽位，必有空位；add 失败则保留掉落，物品不会凭空消失），
 *       重生时 restoreFrom 复制物品栏恢复</li>
 * </ul>
 * <p>
 * 1.20.1 原版无 keepOnDeath NBT 机制（Inventory.dropAll 无条件清空全部槽位，已从字节码验证），
 * 故统一走事件层拦截。两处均为服务端事件，客户端无副作用。
 * 注意：掉落事件被取消时（其他模组接管）原版会丢弃整个掉落列表，此处理器仍执行，
 * 确保饰品还回背包而不是随列表消失。
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

    /** 玩家死亡掉落拦截：背包/盔甲/副手中的冒险饰品移出掉落列表，还回背包 */
    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        Iterator<ItemEntity> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemEntity entity = it.next();
            if (!isAdventureItem(entity.getItem())) continue;
            // 先还回背包再移除掉落实体：dropAll 已清空全部槽位，add 必然成功；
            // 极端情况（其他模组中途塞回物品）add 失败时保留掉落，避免物品消失
            if (player.getInventory().add(entity.getItem())) {
                it.remove();
            }
        }
    }
}
