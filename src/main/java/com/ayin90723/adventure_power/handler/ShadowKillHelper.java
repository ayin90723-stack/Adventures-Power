package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.ability.ShadowKillAbility;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.DamageUtil;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 影杀 (ShadowKill) 能力辅助类。
 * <p>
 * 影杀机制：攻击者在自身 NBT 中维护每个目标的独立"影子血量"，
 * 每次攻击按 (固定值 + 最大生命值 x 比例) 削减影子血量。
 * 影子血量归零时触发饱和式秒杀，绕过一切防御手段直接斩杀目标。
 * 觉醒后，斩杀触发 AOE 链式斩杀：对周围怪物按比例削减影子血量，归零者继续斩杀。
 * <p>
 * 影子血量存储在 <b>攻击者（玩家）</b>侧 persistentData，而非目标侧，
 * 使 Boss 无法通过 NBT 清理/阶段切换等手段逃脱。
 * 每个攻击者对每个目标拥有独立的 BossBar 显示影子血量进度。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ShadowKillHelper {

    // ==================== 影杀 — 影子血量 NBT 键 (攻击者侧存储) ====================
    private static final String NBT_SP_DATA = PersistentDataKeys.SHADOW_HP_DATA;
    private static final String NBT_SP_TOTAL_HP = PersistentDataKeys.SHADOW_HP_TOTAL;
    private static final String NBT_SP_SHADOW_HP = PersistentDataKeys.SHADOW_HP_CURRENT;
    private static final String NBT_SP_END_TIME = PersistentDataKeys.SHADOW_HP_END_TIME;

    /** 防重入：正在斩杀中的目标 */
    private static final Set<UUID> KILLING = ConcurrentHashMap.newKeySet();

    /**
     * 每攻击者对每目标的影子血量 BossBar：attacker UUID -> target UUID -> bar。
     * <p>多人独立：A、B 同打一 Boss 各有独立 bar 显示各自影子血量，互不干扰；
     * 玩家登出只清自己的 bar，不影响他人；target 死亡时清所有攻击者对该 target 的 bar。</p>
     */
    private static final Map<UUID, Map<UUID, ServerBossEvent>> SHADOW_HP_BARS = new ConcurrentHashMap<>();

    /** 影子血量过期清理 tick 计数器，每 N tick 执行一次全局清理 */
    private static int shadowHpCleanupTick = 0;

    // ==================== 影杀主入口 ====================

    /**
     * 处理影杀：削减攻击者对目标的影子血量，归零时触发斩杀。
     *
     * @param event    LivingHurtEvent 事件
     * @param target   受伤实体
     * @param attacker 攻击玩家
     */
    public static void handleShadowKill(LivingHurtEvent event, LivingEntity target, Player attacker) {
        if (target instanceof Player) return;  // PVP 无效
        if (!target.isAlive()) return;

        AbilityGate.getActiveProgress(attacker, "shadow_kill").ifPresent(progress -> {

            // 跳过内部穿透伤害，防重入
            if (DamageUtil.isInternalSource(event.getSource())) return;
            if (KILLING.contains(target.getUUID())) return;

            int milestones = progress.getUnlockedMilestoneCount();
            Ability raw = AbilityRegistry.get("shadow_kill");
            if (!(raw instanceof ShadowKillAbility ability)) return;

            float flatDamage = ability.flatDamage();
            float hpRatio = ability.hpRatio();

            // 从攻击者侧读取影子血量
            CompoundTag playerData = attacker.getPersistentData();
            CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
            long gameTime = attacker.level().getGameTime();

            // 懒清理过期条目
            cleanupExpiredShadowData(shadowData, gameTime);

            String targetKey = target.getUUID().toString();
            float totalHP, shadowHP;
            boolean isNew = false;

            if (shadowData.contains(targetKey)) {
                CompoundTag entry = shadowData.getCompound(targetKey);
                totalHP = entry.getFloat(NBT_SP_TOTAL_HP);
                shadowHP = entry.getFloat(NBT_SP_SHADOW_HP);
            } else {
                totalHP = target.getMaxHealth();
                shadowHP = totalHP;
                isNew = true;
            }

            // 削减影子血量
            float damage = flatDamage + totalHP * hpRatio;
            shadowHP = Math.max(0.0F, shadowHP - damage);

            // 写回攻击者侧 NBT
            CompoundTag entry = new CompoundTag();
            entry.putFloat(NBT_SP_TOTAL_HP, totalHP);
            entry.putFloat(NBT_SP_SHADOW_HP, shadowHP);
            entry.putLong(NBT_SP_END_TIME, gameTime + com.ayin90723.adventure_power.config.ModConfig.SHADOW_KILL_DATA_EXPIRE_TICKS.get());
            shadowData.put(targetKey, entry);
            playerData.put(NBT_SP_DATA, shadowData);

            // 更新 BossBar
            updateShadowHPBossBar(target, attacker, shadowHP, totalHP);

            // 粒子反馈
            if (target.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    target.getX(), target.getY() + target.getBbHeight() * 0.7, target.getZ(),
                    5, 0.3, 0.3, 0.3, 0.02);
                if (isNew) {
                    sl.sendParticles(ParticleTypes.SCULK_SOUL,
                        target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                        30, 0.8, 0.8, 0.8, 0.05);
                }
            }

            // 影子血量归零 → 饱和式秒杀
            if (shadowHP <= 0.0F) {
                shadowData.remove(targetKey);
                if (shadowData.isEmpty()) {
                    playerData.remove(NBT_SP_DATA);
                } else {
                    playerData.put(NBT_SP_DATA, shadowData);
                }
                removeShadowHPBossBar(target);

                event.setCanceled(true);  // 取消原事件伤害，避免与下面 hurt() 叠加
                target.setLastHurtByMob(attacker);
                target.setLastHurtByPlayer(attacker);
                executeShadowKill(target, attacker, event.getSource());

                // 觉醒：影杀 AOE 爆炸
                if (progress.isFullyUnlocked()) {
                    shadowKillAoe(attacker, target);
                }
            }
        });
    }

    // ==================== 饱和式秒杀 ====================

    /**
     * 饱和式秒杀 — 当 hurt() / die() 全部被拦截时的最终手段。
     * 通过多层移除链逐层递增，确保无 Boss 可拦截。
     */
    private static void saturationKill(LivingEntity target, DamageSource source, LivingEntity attacker) {
        Level level = target.level();
        if (!(level instanceof ServerLevel serverLevel)) return;

        // ① 直写血量归零 — 写入所有血量条目（原版 + 自定义），直写 DataItem.value 绕一切 setHealth() 覆写
        HealthUtil.setAllHealthLikeRaw(target, 0.0F);

        // ② 强制掉落全套装备
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack equipment = target.getItemBySlot(slot);
            if (!equipment.isEmpty()) {
                target.spawnAtLocation(equipment.copy());
                target.setItemSlot(slot, ItemStack.EMPTY);
            }
        }

        // ③ 反射调用 dropAllDeathLoot（触发战利品表 / LivingDropsEvent / LootModifier）
        try {
            java.lang.reflect.Method dropAll = LivingEntity.class
                .getDeclaredMethod("m_6668_", DamageSource.class);
            dropAll.setAccessible(true);
            dropAll.invoke(target, source);
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Method dropAll = LivingEntity.class
                    .getDeclaredMethod("dropAllDeathLoot", DamageSource.class);
                dropAll.setAccessible(true);
                dropAll.invoke(target, source);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ④ 手动 post LivingDeathEvent（墓碑/任务模组可正常处理）
        MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(target, source));

        // ⑤ 善后清理
        target.unRide();
        target.ejectPassengers();

        // ⑥ 五重移除链 — 逐层递增，确保无 Boss 可拦截
        target.remove(Entity.RemovalReason.KILLED);                             // 标准路径
        target.remove(Entity.RemovalReason.DISCARDED);                          // 双保险
        HealthUtil.removeDirect(target, Entity.RemovalReason.KILLED);           // 反射 remove() — 绕过 Java 覆写
        HealthUtil.setRemovedFieldDirect(target, Entity.RemovalReason.KILLED);  // 字段直写 — 绕过一切 Mixin
        // 第5层：CHANGED_DIMENSION 兜底 — 部分 Boss 的 Mixin 仅拦截 KILLED/DISCARDED
        HealthUtil.setRemovedDirect(target, Entity.RemovalReason.CHANGED_DIMENSION);

        // ⑦ 客户端同步 — 强制通知所有追踪此实体的玩家其已被移除
        net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket packet =
            new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(target.getId());
        serverLevel.getChunkSource().broadcast(target, packet);

        // ⑧ 内部结构抹除 — 从 EntityLookup/EntityTickList/EntitySection 中直接删除实体
        HealthUtil.eradicateFromWorld(target);
    }

    // ==================== 影杀辅助：BossBar ====================

    private static void updateShadowHPBossBar(LivingEntity target, Player player,
                                               float shadowHP, float totalHP) {
        if (!(player instanceof ServerPlayer sp)) return;
        float progress = totalHP > 0.0F ? Math.max(0.0F, Math.min(1.0F, shadowHP / totalHP)) : 0.0F;
        String nameShadow = String.format("%.1f", shadowHP);
        String nameTotal = String.format("%.1f", totalHP);

        // 双层 computeIfAbsent：每个攻击者对每个目标拥有独立 BossBar，多人同打一 Boss 互不覆盖
        ServerBossEvent bar = SHADOW_HP_BARS
            .computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
            .computeIfAbsent(target.getUUID(), uuid -> {
                ServerBossEvent b = new ServerBossEvent(
                    Component.translatable("ability.adventure_power.shadow_kill.bar", nameShadow, nameTotal),
                    BossBarColor.PURPLE, BossBarOverlay.PROGRESS);
                b.setVisible(true);
                return b;
            });
        bar.setName(Component.translatable("ability.adventure_power.shadow_kill.bar", nameShadow, nameTotal));
        bar.setProgress(progress);
        bar.addPlayer(sp);
    }

    private static void removeShadowHPBossBar(LivingEntity target) {
        // target 死亡/斩杀：遍历所有攻击者，移除他们对该 target 的 BossBar
        UUID targetUuid = target.getUUID();
        for (Map<UUID, ServerBossEvent> inner : SHADOW_HP_BARS.values()) {
            ServerBossEvent bar = inner.remove(targetUuid);
            if (bar != null) {
                bar.removeAllPlayers();
            }
        }
    }

    /** 清理 shadowData 中所有已过期的条目及其 BossBar */
    private static void cleanupExpiredShadowData(CompoundTag shadowData, long gameTime) {
        java.util.List<String> expired = new java.util.ArrayList<>();
        for (String uuidKey : shadowData.getAllKeys()) {
            CompoundTag entry = shadowData.getCompound(uuidKey);
            if (entry.getLong(NBT_SP_END_TIME) <= gameTime) {
                expired.add(uuidKey);
            }
        }
        for (String uuidKey : expired) {
            shadowData.remove(uuidKey);
            removeShadowHPBossBarByUUID(uuidKey);
        }
    }

    private static void removeShadowHPBossBarByUUID(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            // 过期清理：遍历所有攻击者，移除他们对该 target 的 BossBar
            for (Map<UUID, ServerBossEvent> inner : SHADOW_HP_BARS.values()) {
                ServerBossEvent bar = inner.remove(uuid);
                if (bar != null) bar.removeAllPlayers();
            }
        } catch (IllegalArgumentException ignored) {}
    }

    // ==================== 觉醒影杀 AOE ====================

    /**
     * 觉醒影杀 AOE：对斩杀目标周围实体施加影子血量削减，归零时触发斩杀。
     */
    private static void shadowKillAoe(Player attacker, LivingEntity killed) {
        double radius = com.ayin90723.adventure_power.config.ModConfig.AWAKEN_SHADOW_KILL_AOE_RADIUS.get();
        float ratio = com.ayin90723.adventure_power.config.ModConfig.AWAKEN_SHADOW_KILL_AOE_RATIO.get().floatValue();
        int maxTargets = com.ayin90723.adventure_power.config.ModConfig.AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS.get();

        AABB aabb = killed.getBoundingBox().inflate(radius);
        java.util.List<LivingEntity> nearby = killed.level().getEntitiesOfClass(LivingEntity.class, aabb,
            e -> e != attacker && e != killed && e.isAlive()
                && !(e instanceof Player)
                && e instanceof net.minecraft.world.entity.monster.Monster);

        int count = 0;
        CompoundTag playerData = attacker.getPersistentData();
        CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
        long gameTime = attacker.level().getGameTime();

        // 懒清理过期条目（与 handleShadowKill 保持一致）
        cleanupExpiredShadowData(shadowData, gameTime);

        long expireTicks = com.ayin90723.adventure_power.config.ModConfig.SHADOW_KILL_DATA_EXPIRE_TICKS.get();
        for (LivingEntity target : nearby) {
            if (count >= maxTargets) break;

            String targetKey = target.getUUID().toString();
            float totalHP, existingShadow;
            if (shadowData.contains(targetKey)) {
                // 已有条目：保留原始 totalHP 快照，不被目标当前 maxHealth 变化污染
                CompoundTag oldEntry = shadowData.getCompound(targetKey);
                totalHP = oldEntry.getFloat(NBT_SP_TOTAL_HP);
                existingShadow = oldEntry.getFloat(NBT_SP_SHADOW_HP);
            } else {
                totalHP = target.getMaxHealth();
                existingShadow = totalHP;
            }
            float aoeReduction = totalHP * ratio;
            float newShadow = Math.max(0.0F, existingShadow - aoeReduction);

            // 粒子反馈
            if (killed.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.SCULK_SOUL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    10, 0.5, 0.5, 0.5, 0.02);
            }

            // 影子血量归零 → 触发斩杀
            if (newShadow <= 0.0F) {
                shadowData.remove(targetKey);
                removeShadowHPBossBar(target);
                executeShadowKill(target, attacker, attacker.damageSources().playerAttack(attacker));
                count++;
                continue;
            }

            // 正常削减：写回 NBT
            CompoundTag entry = new CompoundTag();
            entry.putFloat(NBT_SP_TOTAL_HP, totalHP);
            entry.putFloat(NBT_SP_SHADOW_HP, newShadow);
            entry.putLong(NBT_SP_END_TIME, gameTime + expireTicks);
            shadowData.put(targetKey, entry);

            updateShadowHPBossBar(target, attacker, newShadow, totalHP);
            count++;
        }
        if (!shadowData.isEmpty()) {
            playerData.put(NBT_SP_DATA, shadowData);
        } else {
            playerData.remove(NBT_SP_DATA);
        }
    }

    // ==================== 执行斩杀 ====================

    /**
     * 执行影杀斩杀：清无敌帧 → hurt(Float.MAX_VALUE) → saturationKill 兜底。
     * NBT 清理和 BossBar 移除由调用方负责。
     */
    private static void executeShadowKill(LivingEntity target, Player attacker, DamageSource killSource) {
        CombatAbilityHandler.clearHurtTime(target);
        target.invulnerableTime = 0;
        KILLING.add(target.getUUID());
        try {
            // 用 maxHealth×10 替代 Float.MAX_VALUE：足够秒杀任何 Boss，
            // 又不会让其他模组做 amount×ratio 时溢出为 Infinity/NaN 导致异常/卡死
            target.hurt(killSource, target.getMaxHealth() * 10F);
        } finally {
            KILLING.remove(target.getUUID());
        }
        if (target.isAlive()) {
            saturationKill(target, killSource, attacker);
        }
    }

    // ==================== 事件处理器 ====================

    /** 任意实体死亡时，遍历所有在线玩家清理该目标的影子血量数据和 BossBar */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;

        removeShadowHPBossBar(target);
        String targetKey = target.getUUID().toString();
        for (Player onlinePlayer : target.level().players()) {
            CompoundTag playerData = onlinePlayer.getPersistentData();
            CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
            if (shadowData.contains(targetKey)) {
                shadowData.remove(targetKey);
                if (shadowData.isEmpty()) {
                    playerData.remove(NBT_SP_DATA);
                } else {
                    playerData.put(NBT_SP_DATA, shadowData);
                }
            }
        }
    }

    /**
     * 每 N tick 遍历所有在线玩家，清理 persistentData 中过期的影子血量条目
     * 以及对应的 BossBar。防止玩家长时间不攻击导致过期数据/残血条堆积。
     * 周期由 ModConfig.SHADOW_KILL_CLEANUP_INTERVAL 控制。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent event) {
        if (event.phase != Phase.END) return;

        int interval = com.ayin90723.adventure_power.config.ModConfig.SHADOW_KILL_CLEANUP_INTERVAL.get();
        shadowHpCleanupTick++;
        if (shadowHpCleanupTick < interval) return;
        shadowHpCleanupTick = 0;

        MinecraftServer server = event.getServer();
        if (server == null) return;

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            CompoundTag playerData = sp.getPersistentData();
            CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
            if (shadowData.isEmpty()) continue;

            long gameTime = sp.level().getGameTime();
            cleanupExpiredShadowData(shadowData, gameTime);

            if (shadowData.isEmpty()) {
                playerData.remove(NBT_SP_DATA);
            } else {
                playerData.put(NBT_SP_DATA, shadowData);
            }
        }
    }

    /**
     * 玩家登出时清理该玩家关联的所有影子血量 BossBar，
     * 防止 BossBar 持有的 ServerPlayer 引用在玩家离线后变为无效，造成内存泄漏。
     * 影子血量数据本身保留在 persistentData 中，下次登录时继续使用。
     * <p>多人安全：仅移除该攻击者自己的 bar（attacker UUID 为外层 key），
     * 不影响其他玩家对同一目标的 bar。</p>
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Map<UUID, ServerBossEvent> inner = SHADOW_HP_BARS.remove(event.getEntity().getUUID());
        if (inner != null) {
            for (ServerBossEvent bar : inner.values()) {
                bar.removeAllPlayers();
            }
        }
    }
}
