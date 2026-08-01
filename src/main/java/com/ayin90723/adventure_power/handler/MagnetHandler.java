package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 磁吸·万物引归 - 周期扫描半径内掉落物吸向玩家。
 * <p>
 * 由 {@link PlayerTickDispatcher} 分发调用（已统一门禁）。限频扫描降低
 * {@code getEntities} 开销；setPos lerp 朝玩家拉近，进入拾取半径触发
 * 原版 {@code playerTouch}（内部校验 pickupDelay）。
 * <p>
 * 觉醒：吸取半径×倍率 + 经验球(ExperienceOrb) 也吸入。
 */
public class MagnetHandler {

    /** 玩家上次扫描 gameTime（限频） */
    private static final Map<UUID, Long> lastScan = new HashMap<>();

    /** 门禁后业务（由 PlayerTickDispatcher 调用） */
    public static void onTick(Player player, IAdventureProgress progress) {
        if (!progress.isAbilityEnabled(AbilityIds.MAGNET)) return;

        long currentTime = player.level().getGameTime();
        int interval = ModConfig.MAGNET_SCAN_INTERVAL.get();
        long last = lastScan.getOrDefault(player.getUUID(), -1L);
        if (last != -1L && currentTime - last < interval) return;
        lastScan.put(player.getUUID(), currentTime);

        if (!(player.level() instanceof ServerLevel serverLevel)) return;

        Float radius = AbilityGate.value(progress, AbilityIds.MAGNET).orElse(null);
        if (radius == null) return;
        boolean awakened = progress.isFullyUnlocked();
        if (awakened) {
            radius *= ModConfig.AWAKEN_MAGNET_RADIUS_MULT.get().floatValue();
        }

        float pickupRadius = ModConfig.MAGNET_PICKUP_RADIUS.get().floatValue();
        // 朝玩家躯干中心吸引（+0.5 避免物品贴地）
        double px = player.getX();
        double py = player.getY() + 0.5;
        double pz = player.getZ();
        AABB box = new AABB(px - radius, py - radius, pz - radius,
                            px + radius, py + radius, pz + radius);

        List<ItemEntity> items = serverLevel.getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity item : items) {
            if (item.isRemoved()) continue;
            attract(item, player, px, py, pz, pickupRadius);
        }

        // 觉醒：经验球也吸入
        if (awakened && ModConfig.AWAKEN_MAGNET_INCLUDE_XP.get()) {
            List<ExperienceOrb> orbs = serverLevel.getEntitiesOfClass(ExperienceOrb.class, box);
            for (ExperienceOrb orb : orbs) {
                if (orb.isRemoved()) continue;
                attract(orb, player, px, py, pz, pickupRadius);
            }
        }
    }

    /** 朝玩家拉近；进入拾取半径触发原版 playerTouch（内部校验 pickupDelay） */
    private static void attract(Entity entity, Player player,
                                double px, double py, double pz, float pickupRadius) {
        double dx = px - entity.getX();
        double dy = py - entity.getY();
        double dz = pz - entity.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.05) return;

        if (dist < pickupRadius) {
            // 进入拾取半径：触发拾取（playerTouch public，多态到 ItemEntity/ExperienceOrb；pickupDelay 未到则原版忽略，下个 scan 再试）
            entity.playerTouch(player);
            return;
        }

        // 每 scan 朝玩家移动配置比例的距离（默认 30%，穿墙，磁吸特性）
        double factor = ModConfig.MAGNET_PULL_FACTOR.get();
        entity.setPos(entity.getX() + dx * factor,
                      entity.getY() + dy * factor,
                      entity.getZ() + dz * factor);
        entity.setDeltaMovement(0, 0, 0); // 清零惯性，避免重力干扰轨迹
        entity.hurtMarked = true;
    }

    /** 玩家登出清理（由 PlayerTickHandler.onPlayerLogout 调用） */
    public static void onLogout(UUID uuid) {
        lastScan.remove(uuid);
    }
}
