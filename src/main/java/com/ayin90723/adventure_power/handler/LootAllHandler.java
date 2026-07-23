package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.FriendlyFireProtection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;

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

    /** 满载而归额外滚取期间为 true，供 canRun / addRandomItems Mixin 识别 */
    public static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 觉醒取最大数量期间为 true，供 LootContextBuilderMixin 识别 */
    public static final ThreadLocal<Boolean> AWAKEN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        // 仅玩家击杀触发（killer 为 ServerPlayer，便于传 LAST_DAMAGE_PLAYER）
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;

        // 服务端门禁
        var progress = AbilityGate.getActiveProgress(player, "loot_all").orElse(null);
        if (progress == null) return;

        // 友伤保护：不对玩家自己驯服的生物生效
        if (FriendlyFireProtection.isOwnerTarget(player, entity)) return;

        // 取 loot table（玩家等无 loot table 的实体跳过）
        ResourceLocation lootTableId = entity.getLootTable();
        if (lootTableId == null) return;
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
        boolean prevBypass = BYPASS.get();
        boolean prevAwaken = AWAKEN.get();
        int maxItems = ModConfig.LOOT_ALL_MAX_ITEMS.get();
        int generated = 0;
        BYPASS.set(true);
        AWAKEN.set(awakened);
        try {
            lootLoop:
            for (int i = 0; i < copies; i++) {
                for (ItemStack stack : table.getRandomItems(params)) {
                    if (stack.isEmpty()) continue;
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
}
