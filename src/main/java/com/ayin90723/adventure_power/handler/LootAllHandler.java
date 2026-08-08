package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.FriendlyFireProtection;
import com.ayin90723.adventure_power.util.PiercingGazeUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collection;
import java.util.List;

/**
 * 满载而归 - 击杀生物后，在原版掉落基础上额外按掉落表"每样一份"追加掉落。
 * <p>
 * 监听 {@link LivingDropsEvent}（不清空原版 drops，仅追加），通过两个 {@link ThreadLocal}
 * 标志位驱动 3 个 Mixin 协作：
 * <ul>
 *   <li>{@link #BYPASS} - 额外滚取期间为 true：
 *       <ul>
 *         <li>{@code LootPoolEntryContainerMixin} 让 canRun 总返回 true（无视 entry 级条件）</li>
 *         <li>{@code LootPoolMixin} 拦截 addRandomItems 改为遍历所有 entries（无视 pool 级条件 + 每样一份）</li>
 *       </ul>
 *   </li>
 *   <li>{@link #AWAKEN} - 觉醒取最大期间为 true：
 *       {@code LootContextBuilderMixin} 注入 {@code ConstantMaxRandomSource}（SetItemCount 取 max）</li>
 * </ul>
 * <p>
 * 标志位隔离确保原版掉落流程（BYPASS=false）完全不受影响。
 * <p>
 * 边界：Forge GlobalLootModifier (GLM) 类掉落独立于 loot table，本能力无法覆盖。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class LootAllHandler {

    /** 满载而归额外滚取期间 >0（计数器，支持嵌套），供 canRun / addRandomItems Mixin 识别 */
    public static final ThreadLocal<Integer> BYPASS = ThreadLocal.withInitial(() -> 0);

    /** 觉醒取最大数量期间 >0（计数器），供 LootContextBuilderMixin 识别 */
    public static final ThreadLocal<Integer> AWAKEN = ThreadLocal.withInitial(() -> 0);

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        // 仅玩家击杀触发（killer 为 ServerPlayer，便于传 LAST_DAMAGE_PLAYER）。
        // 用 resolveAttacker 追溯发射者：弹射物击杀（弓箭/弩/三叉戟）的 getEntity()
        // 是弹射物本身，与 MilestoneTriggerManager.onPlayerFirstKill 的追溯逻辑保持一致
        if (!(PiercingGazeUtil.resolveAttacker(event.getSource()) instanceof ServerPlayer player)) return;

        // 服务端门禁
        var progress = AbilityGate.getActiveProgress(player, AbilityIds.LOOT_ALL).orElse(null);
        if (progress == null) return;

        // 友伤保护：不对玩家自己驯服的生物生效
        if (FriendlyFireProtection.isOwnerTarget(player, entity)) return;

        // 取 loot table（玩家等无掉落表实体跳过——1.20.1 返回 BuiltInLootTables.EMPTY 而非 null）
        ResourceLocation lootTableId = entity.getLootTable();
        // 1.20.1 原版无掉落表实体返回 EMPTY 而非 null；第三方覆写 getLootTable 返回 null 时也跳过
        if (lootTableId == null || BuiltInLootTables.EMPTY.equals(lootTableId)) return;
        ServerLevel level = (ServerLevel) entity.level();
        LootTable table = level.getServer().getLootData().getLootTable(lootTableId);

        // 构造 LootParams：复刻原版击杀上下文，使熔炼 function / killed_by_player 等按原版判断
        // 1.20.1 抢夺通过 LootContext.getLootingModifier() -> ForgeHooks.getLootingLevel(THIS_ENTITY, KILLER_ENTITY, ...)
        // 自动取 KILLER_ENTITY(玩家)的抢夺附魔等级，无需单独的 LOOTING_ENTITY 参数
        LootParams params = new LootParams.Builder(level)
            .withParameter(LootContextParams.THIS_ENTITY, entity)
            .withParameter(LootContextParams.ORIGIN, entity.position())
            .withParameter(LootContextParams.DAMAGE_SOURCE, event.getSource())
            .withOptionalParameter(LootContextParams.KILLER_ENTITY, player)
            .withOptionalParameter(LootContextParams.DIRECT_KILLER_ENTITY, event.getSource().getDirectEntity())
            .withOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER, player)
            .create(LootContextParamSets.ENTITY);

        // 觉醒判定：fullyUnlocked + 配置开启取最大 -> AWAKEN 标志 + 觉醒份数
        boolean awakened = progress.isFullyUnlocked() && ModConfig.LOOT_ALL_AWAKENED_MAX_COUNT.get();
        int copies = awakened ? ModConfig.LOOT_ALL_AWAKENED_COPIES.get() : ModConfig.LOOT_ALL_COPIES.get();
        if (copies <= 0) return;

        // 额外滚取：标志位驱动 Mixin 绕过条件 + 遍历 entries + 觉醒取最大
        Collection<ItemEntity> drops = event.getDrops();
        Vec3 pos = entity.position();
        // 保存-恢复前值：防止 getRandomItems 内部触发嵌套 LivingDropsEvent 时，
        // 内层 finally 清掉外层标志位，导致外层剩余 copies 轮次失效
        int prevBypass = BYPASS.get();
        int prevAwaken = AWAKEN.get();
        int maxItems = ModConfig.LOOT_ALL_MAX_ITEMS.get();
        int generated = 0;
        BYPASS.set(prevBypass + 1);
        AWAKEN.set(prevAwaken + (awakened ? 1 : 0));
        try {
            lootLoop:
            for (int i = 0; i < copies; i++) {
                for (ItemStack stack : table.getRandomItems(params)) {
                    if (stack.isEmpty()) continue;
                    // 额外掉落过滤（黑名单/唱片/头颅，配置默认关闭 = 不过滤，保持原行为）
                    if (isFiltered(stack)) continue;
                    ItemEntity itemEntity = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
                    // 随机散落速度，避免所有额外掉落叠在同一点
                    itemEntity.setDeltaMovement(
                        (level.random.nextDouble() - 0.5) * 0.3,
                        0.3 + level.random.nextDouble() * 0.1,
                        (level.random.nextDouble() - 0.5) * 0.3
                    );
                    drops.add(itemEntity);
                    // 总数量上限：防极端 copies 配置单 tick 生成过多实体卡服
                    if (++generated >= maxItems) break lootLoop;
                }
            }
        } finally {
            BYPASS.set(prevBypass);
            AWAKEN.set(prevAwaken);
        }
    }

    /**
     * 满载而归额外掉落过滤（v1.3.7）：黑名单 / 唱片 / 头颅，三项独立配置。
     * <p>
     * 仅过滤额外掉落，原版掉落流程不受影响。三个开关默认关闭（不过滤），
     * 保持与旧版本完全一致的行为——需要过滤的玩家自行开启。
     * <ul>
     *   <li>黑名单：物品注册 ID 列表（`loot_all_blacklist`，如 "minecraft:player_head"）</li>
     *   <li>唱片：`loot_all_drop_music_discs`（false = 过滤所有 {@link RecordItem}，含模组唱片）</li>
     *   <li>头颅：`loot_all_drop_skulls`（false = 过滤 skulls 物品 tag（数据包扩展点）+ 注册名 *_skull、*_head 后缀）</li>
     * </ul>
     */
    private static boolean isFiltered(ItemStack stack) {
        // ① 黑名单（物品注册 ID）
        List<? extends String> blacklist = ModConfig.LOOT_ALL_BLACKLIST.get();
        if (!blacklist.isEmpty()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && blacklist.contains(id.toString())) {
                return true;
            }
        }
        // ② 唱片：1.20.1 类名为 RecordItem（原版 + 模组唱片统一拦截，不依赖注册名）
        if (!ModConfig.LOOT_ALL_DROP_MUSIC_DISCS.get() && stack.getItem() instanceof RecordItem) {
            return true;
        }
        // ③ 头颅：skulls 物品 tag 为准（原版 6 种头），注册名后缀兜底（模组头，1.20.1 无 SkullItem 类）
        if (!ModConfig.LOOT_ALL_DROP_SKULLS.get() && isSkull(stack)) {
            return true;
        }
        return false;
    }

    /** 头颅物品 tag（minecraft:skulls，1.20.1 原版无此 tag，由数据包定义时生效——仅作扩展点） */
    private static final TagKey<Item> SKULLS_TAG = TagKey.create(Registries.ITEM, new ResourceLocation("minecraft:skulls"));

    /** 头颅判断：minecraft:skulls 物品 tag（数据包扩展点，1.20.1 原版无此 tag）为主，
     *  注册名 *_skull / *_head 后缀兜底（覆盖原版 6 种头与大部分模组头） */
    private static boolean isSkull(ItemStack stack) {
        if (stack.is(SKULLS_TAG)) return true;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        String path = id.getPath();
        return path.endsWith("_skull") || path.endsWith("_head");
    }
}
