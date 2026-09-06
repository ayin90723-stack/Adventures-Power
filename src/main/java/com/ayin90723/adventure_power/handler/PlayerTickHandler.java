package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.BuffExclusionManager;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import com.ayin90723.adventure_power.util.SyncUtil;
import com.ayin90723.adventure_power.network.NetworkHandler;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家 Tick 处理 - 开局安全网 + 周期性能力逻辑。
 * <p>
 * 从 PlayerTickDispatcher 分发调用（不再独立订阅 PlayerTickEvent）。处理：
 * <ul>
 *   <li>开局安全网（门禁前）：补发冒险饰品 + 自动激活冒险者</li>
 *   <li>Buff 延长（恩赐永驻，每 60 tick）</li>
 *   <li>环境免疫（每 tick 清火）</li>
 *   <li>受击坚韧（超时层数归零）</li>
 *   <li>庇护无敌过期清除</li>
 *   <li>不朽装备耐久守护（v1.4.9.2，每 tick 巡检 6 槽修满）</li>
 * </ul>
 * <p>
 * v1.4.0：移除「持有冒险的终点 → 自动全解锁」测试入口——终点已移出创造物品栏，
 * 全解锁能力迁移至 {@code /ap unlock all} 指令（op 2 专用）。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class PlayerTickHandler {

    private static final int BUFF_CHECK_INTERVAL = 60;
    private static final Map<UUID, Long> lastBuffCheck = new HashMap<>();

    /** 已执行过开局安全网的玩家（内存标记，避免每 tick 读 persistentData 的 NBT 查找） */
    private static final Set<UUID> VERIFIED_BEGIN_ITEM = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 开局安全网（门禁前，由 PlayerTickDispatcher 调用）。
     * 补发冒险饰品 + 自动激活冒险者（每玩家仅一次）。
     * 需对非冒险者执行，故在分发器门禁前调用。
     */
    public static void tickSafetyNet(Player player) {
        // 补发冒险饰品 + 自动激活冒险者（每玩家仅一次；内存标记 + persistentData 双保险）
        if (!VERIFIED_BEGIN_ITEM.contains(player.getUUID())) {
            VERIFIED_BEGIN_ITEM.add(player.getUUID());
            if (!player.getPersistentData().getBoolean(PersistentDataKeys.VERIFIED_BEGIN_ITEM_KEY)) {
                player.getPersistentData().putBoolean(PersistentDataKeys.VERIFIED_BEGIN_ITEM_KEY, true);
                CapabilityLifecycleHandler.giveAdventureBeginIfNeeded(player);
                CapabilityLifecycleHandler.checkAndActivateAdventurer(player);
            }
        }
    }

    /**
     * 门禁后业务（由 PlayerTickDispatcher 调用）：
     * Buff 延长 / 环境免疫 / 受击坚韧超时 / 庇护无敌过期。
     */
    public static void onTick(Player player, IAdventureProgress progress) {
        // Capability 时间戳字段（受击坚韧/庇护无敌/死亡抗拒）用维度 gameTime（由 shiftTimers
        // 跨维度平移）；lastBuffCheck 是静态 Map（不平移），限频基准改用服务器全局 tick 防跨维度冻结
        long currentTime = player.level().getGameTime();
        long serverTick = player.level().getServer() != null
            ? player.level().getServer().getTickCount() : currentTime;

        // Buff 延长（每 3 秒）
        if (progress.isAbilityEnabled(AbilityIds.PERPETUAL_BLESSING)) {
            long lastCheck = lastBuffCheck.getOrDefault(player.getUUID(), -1L);
            if (lastCheck == -1L) {
                lastBuffCheck.put(player.getUUID(), serverTick);
            } else if (serverTick - lastCheck >= BUFF_CHECK_INTERVAL) {
                lastBuffCheck.put(player.getUUID(), serverTick);
                extendBeneficialEffects(player);
            }
        } else {
            lastBuffCheck.remove(player.getUUID());
        }

        // 环境免疫：每 tick 清除火焰（先检查是否着火，避免无火时的不必要同步）
        if (progress.isAbilityEnabled(AbilityIds.ENV_IMMUNITY) && player.getRemainingFireTicks() > 0) {
            player.clearFire();
        }

        // 不朽装备耐久守护（v1.4.9.2）：能力激活时装备 Damage 字段的合法上升为零——
        // 原版全部损耗路径已被 LegacyHurtAndBreakMixin 在 hurtAndBreak/hurt 入口拦截，
        // 修复方向（经验修补/铁砧/砂轮）全部降向。因此任何非零 Damage 都是绕过损耗链的
        // 直接操纵（setDamageValue/NBT 直写，如"每秒磨损 1% 耐久"类效果），巡检直接修满。
        // 目标状态是常量（满耐久），无需快照对账；damage>0 才写，满耐久零写入零同步开销；
        // 服务端改 NBT 后背包 menu 的 broadcastChanges 自动推给客户端，无需手动发包
        if (progress.isAbilityEnabled(AbilityIds.UNDYING_GEAR)
                && ModConfig.UNDYING_GEAR_DURABILITY_GUARD.get()) {
            repairEquipmentDurability(player);
        }

        // 受击坚韧：超过 5 秒无受伤 -> 层数归零
        if (progress.isAbilityEnabled(AbilityIds.RESILIENCE)) {
            long lastHurt = progress.getLastHurtTime();
            if (lastHurt > 0 && currentTime - lastHurt >= ModConfig.RESILIENCE_RESET_TICKS.get()) {
                progress.setResilienceStacks(0);
                progress.setLastHurtTime(0);
            }
        }

        // 庇护无敌过期后清除（避免残留值，同步客户端和持久数据）
        if (progress.getSanctuaryInvulEnd() > 0 && currentTime >= progress.getSanctuaryInvulEnd()) {
            progress.setSanctuaryInvulEnd(0);
            SyncUtil.syncCapabilityToPersistent(player, progress);
            SyncUtil.syncToClient(player);
        }
    }

    /** 守护的 6 个标准槽位（Curios 饰品槽不纳入——饰品大多无耐久，复用型物品反而会被清坏） */
    private static final EquipmentSlot[] GUARDED_SLOTS = {
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /**
     * 耐久守护巡检：非零损伤直接修满（v1.4.9.2）。
     * 仅作用于有耐久上限的物品（getMaxDamage &gt; 0）——拿 Damage 字段当计时器/能量的
     * 复用型物品若未设 maxDamage 则天然免疫；设了 maxDamage 的复用型会被清零，
     * 已知边界，遇到再加物品黑名单（暂不做，最小修改）。
     */
    private static void repairEquipmentDurability(Player player) {
        for (EquipmentSlot slot : GUARDED_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.getMaxDamage() > 0 && stack.getDamageValue() > 0) {
                stack.setDamageValue(0);
            }
        }
    }

    private static void extendBeneficialEffects(Player player) {
        boolean extended = false;
        Set<String> excluded = BuffExclusionManager.getBuffExclusionSet(player);
        int minDuration = ModConfig.BUFF_MIN_DURATION.get();
        int extendAmount = ModConfig.BUFF_EXTEND_AMOUNT.get();
        int threshold = minDuration + extendAmount;
        for (MobEffectInstance effect : new ArrayList<>(player.getActiveEffects())) {
            if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                String effectId = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()).toString();
                if (excluded.contains(effectId)) continue;
                // 无限时长效果（duration=-1，如潮涌能量/部分模组永久 buff）不需要续期，
                // 否则会被降级为有限时长并每 60 tick 反复重建 + GLOW 粒子刷屏
                if (effect.getDuration() < 0) continue;
                if (effect.getDuration() < threshold) {
                    extended = true;
                    // 重建时携带 factorData（潮涌能量等环境效果数据），避免续期后丢失环境属性
                    // （隐藏效果链 1.20.1 无 getter，按原 6 参构造器行为重建时不保留）
                    player.addEffect(new MobEffectInstance(effect.getEffect(), threshold,
                        effect.getAmplifier(), effect.isAmbient(), effect.isVisible(), effect.showIcon(),
                        null, effect.getFactorData()));
                }
            }
        }
        if (extended && player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.GLOW,
                player.getX(), player.getY() + 1.5, player.getZ(), 15, 0.5, 0.5, 0.5, 0.1);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        lastBuffCheck.remove(uuid);
        VERIFIED_BEGIN_ITEM.remove(uuid);
        BuffExclusionManager.clearCache(uuid);
        MagnetHandler.onLogout(uuid);
        SwiftHandler.onLogout(uuid);
        // 网络包限频表登出清理（防长期服务器 UUID 累积，v1.4.0）
        NetworkHandler.clearCooldowns(uuid);
    }
}
