package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 加速·追风者 - 疾跑时大幅降低饱食消耗。
 * <p>
 * 由 {@link PlayerTickDispatcher} 分发调用（已统一门禁）。速度加成改由
 * {@link ExplorationAbilityHandler} 同步 MOVEMENT_SPEED 属性（常驻，无图标），
 * 本类仅保留疾跑相关：饱食补偿 + 觉醒推开。
 * <p>
 * 觉醒：疾跑碰撞推开半径内敌对生物（Monster）。
 */
public class SwiftHandler {

    private static final Map<UUID, Long> lastPush = new HashMap<>();

    public static void onTick(Player player, IAdventureProgress progress) {
        if (!progress.isAbilityEnabled(AbilityIds.SWIFT)) return;

        // 水下额外加速（海豚祝福，无图标无粒子）- 常驻，不限疾跑
        if (player.isInWater()) {
            int dur = ModConfig.SWIFT_WATER_DURATION.get();
            MobEffectInstance dolphin = player.getEffect(MobEffects.DOLPHINS_GRACE);
            if (dolphin == null || dolphin.getDuration() < dur / 2) {
                player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, dur, 0, false, false, false));
            }
        }

        if (!player.isSprinting()) {
            lastPush.remove(player.getUUID());
            return;
        }

        // 饱食消耗补偿：抵消原版疾跑消耗的 reduction 比例（原版约 0.1/tick）
        float reduction = ModConfig.SWIFT_EXHAUSTION_REDUCTION.get().floatValue();
        if (reduction > 0.0F) {
            player.getFoodData().addExhaustion(-0.1F * reduction);
        }

        // 觉醒：推开半径内敌对生物
        if (!progress.isFullyUnlocked()) return;
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        long currentTime = player.level().getGameTime();
        long last = lastPush.getOrDefault(player.getUUID(), -1L);
        if (last != -1L && currentTime - last < 5) return;
        lastPush.put(player.getUUID(), currentTime);

        double radius = ModConfig.AWAKEN_SWIFT_PUSH_RADIUS.get();
        double strength = ModConfig.AWAKEN_SWIFT_PUSH_STRENGTH.get();
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        AABB box = new AABB(px - radius, py - radius, pz - radius, px + radius, py + radius, pz + radius);
        // 直接查 Monster 子类，避免把中立/被动生物也拉进 AABB 查询再过滤
        List<Monster> mobs = serverLevel.getEntitiesOfClass(Monster.class, box);
        for (Monster m : mobs) {
            if (m.isRemoved()) continue;
            double dx = m.getX() - px;
            double dz = m.getZ() - pz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.1 || dist > radius) continue;
            m.setDeltaMovement(m.getDeltaMovement().add(dx / dist * strength, 0.3, dz / dist * strength));
            m.hurtMarked = true;
        }
    }

    public static void onLogout(UUID uuid) {
        lastPush.remove(uuid);
    }
}
