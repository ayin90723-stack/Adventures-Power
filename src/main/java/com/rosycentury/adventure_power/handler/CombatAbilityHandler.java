package com.rosycentury.adventure_power.handler;

import com.rosycentury.adventure_power.AdventurePower;
import com.rosycentury.adventure_power.ability.Ability;
import com.rosycentury.adventure_power.ability.AbilityRegistry;
import com.rosycentury.adventure_power.ability.ShadowKillAbility;
import com.rosycentury.adventure_power.ability.SoulQuenchAbility;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.effect.UndyingSlashEffect;
import com.rosycentury.adventure_power.util.FriendlyFireProtection;
import com.rosycentury.adventure_power.util.HealthUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗类能力效果处理器。
 * <p>
 * 处理 5 种战斗能力的实际效果：
 * <ul>
 *   <li>灵巧 (Agility) — LivingAttackEvent 中概率闪避</li>
 *   <li>伤害抗性 (DamageResist) — LivingHurtEvent 中全伤害减免</li>
 *   <li>淬魂之力 (SoulQuench) — 真实伤害（绕过护甲/抗性）</li>
 *   <li>影杀 (ShadowKill) — 攻击者侧影子血量 + 饱和式秒杀</li>
 *   <li>禁疗之触 (HealingBlock) — 对目标施加禁疗标记</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CombatAbilityHandler {

    // ==================== 影杀 — 影子血量 NBT 键 (攻击者侧存储) ====================
    private static final String NBT_SP_DATA = "AP_ShadowHP_Data";
    private static final String NBT_SP_TOTAL_HP = "totalHP";
    private static final String NBT_SP_SHADOW_HP = "shadowHP";
    private static final String NBT_SP_END_TIME = "endTime";

    /** 防重入：正在斩杀中的目标 */
    private static final Set<UUID> KILLING = ConcurrentHashMap.newKeySet();

    /** BossBar 缓存 */
    private static final Map<UUID, ServerBossEvent> SHADOW_HP_BARS = new ConcurrentHashMap<>();

    /** 影子血量过期清理 tick 计数器，每 200 tick（10秒）执行一次全局清理 */
    private static int shadowHpCleanupTick = 0;

    // ==================== 1. 灵巧 — 概率闪避 ====================

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.isCanceled()) return;
        LivingEntity receiver = event.getEntity();
        if (!(receiver instanceof Player player)) return;
        if (receiver.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer()) return;
            if (!progress.isAbilityEnabled("agility")) return;

            int milestones = progress.getUnlockedMilestoneCount();
            Ability ability = AbilityRegistry.get("agility");
            if (ability == null) return;

            float chance = ability.value(milestones) / 100.0f;
            if (progress.isFullyUnlocked()) {
                chance = Math.min(chance * com.rosycentury.adventure_power.config.ModConfig.AWAKEN_MULTIPLIER.get().floatValue(), 0.95f);
            }
            if (player.getRandom().nextFloat() < chance) {
                event.setCanceled(true);
                if (player.level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.WITCH,
                        player.getX(), player.getY() + 1.0, player.getZ(),
                        20, 0.4, 0.5, 0.4, 0.15);
                }
            }
        });
    }

    // ==================== 影杀辅助：实体死亡时清理残留 ====================

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

    // ==================== 2~5: LivingHurtEvent 入口 ====================

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;

        DamageSource source = event.getSource();

        // 受伤方能力
        handleDamageResist(event, target);

        // 攻击方能力（需攻击者为冒险者）
        if (source.getEntity() instanceof Player attacker) {
            if (FriendlyFireProtection.isOwnerTarget(attacker, target)) return;
            if (!AdventureProgressCapability.isAdventurer(attacker)) return;

            handleHealingBlock(event, target, attacker);
            handlePiercingGazeAwakened(event, target, attacker);
            handleSoulQuench(event, target, attacker);
            handleShadowKill(event, target, attacker);
        }
    }

    /** 破敌之眼觉醒：对无敌帧中的目标 +30% 伤害 */
    private static void handlePiercingGazeAwakened(LivingHurtEvent event, LivingEntity target, Player attacker) {
        if (target.invulnerableTime <= 0) return;
        com.rosycentury.adventure_power.capability.AdventureProgressCapability.getAdventureProgress(attacker)
            .ifPresent(progress -> {
                if (!progress.isFullyUnlocked()) return;
                if (!progress.isAbilityEnabled("piercing_gaze")) return;
                event.setAmount(event.getAmount() * 1.30f);
            });
    }

    // ==================== 2. 伤害抗性 — 全伤害减免 ====================

    private static void handleDamageResist(LivingHurtEvent event, LivingEntity target) {
        if (!(target instanceof Player player)) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer()) return;
            if (!progress.isAbilityEnabled("damage_resist")) return;

            int milestones = progress.getUnlockedMilestoneCount();
            Ability ability = AbilityRegistry.get("damage_resist");
            if (ability == null) return;

            float ratio = ability.value(milestones) / 100.0f;
            if (progress.isFullyUnlocked()) {
                ratio = Math.min(ratio * com.rosycentury.adventure_power.config.ModConfig.AWAKEN_MULTIPLIER.get().floatValue(), 0.95f);
            }
            event.setAmount(event.getAmount() * (1.0f - ratio));
        });
    }

    // ==================== 3. 淬魂之力 — 真实伤害 ====================

    /**
     * 淬魂之力：真实百分比伤害，绕过护甲/抗性/无敌帧。
     * <p>
     * 公式：额外伤害 = flatDamage + maxHP×hpRatio + currentHP×hpRatio
     * 通过自定义 DamageSource（bypasses_armor + bypasses_invulnerability +
     * bypasses_enchantments + bypasses_cooldown）施加，若 hurt() 被外部
     * mod 拦截则通过 HealthUtil 直写血量兜底。
     */
    private static void handleSoulQuench(LivingHurtEvent event, LivingEntity target, Player attacker) {
        AdventureProgressCapability.getAdventureProgress(attacker).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("soul_quench")) return;

            // 跳过自身造成的穿透伤害，防递归
            if (isInternalSource(event.getSource())) return;

            int milestones = progress.getUnlockedMilestoneCount();
            Ability raw = AbilityRegistry.get("soul_quench");
            if (!(raw instanceof SoulQuenchAbility ability)) return;

            float flatDamage = ability.flatDamage(milestones);
            float hpRatio = ability.hpRatio(milestones);

            float extraDamage = flatDamage
                + target.getMaxHealth() * hpRatio
                + target.getHealth() * hpRatio;

            if (UndyingSlashEffect.isActive(target)) {
                extraDamage *= 1.5f;
            }

            // 觉醒：斩杀线 — 目标低于阈值 HP 时伤害翻倍
            if (progress.isFullyUnlocked()) {
                float threshold = (float) (double) com.rosycentury.adventure_power.config.ModConfig.AWAKEN_SOUL_QUENCH_EXECUTE_THRESHOLD.get();
                if (target.getHealth() <= target.getMaxHealth() * threshold) {
                    extraDamage *= 2.0F;
                }
            }

            if (extraDamage <= 0.0F) return;

            // 构建穿透伤害类型：绕过护甲/无敌/附魔保护/攻击冷却
            var key = ResourceKey.create(Registries.DAMAGE_TYPE,
                new ResourceLocation("adventure_power", "soul_strike"));
            var registry = target.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
            var holder = registry.getHolderOrThrow(key);
            var source = new DamageSource(holder, null, attacker);

            float healthBefore = target.getHealth();
            target.hurt(source, extraDamage);
            float actualDealt = healthBefore - target.getHealth();

            // 清零无敌帧：hurt() 后原版会将 invulnerableTime 设为 10
            target.invulnerableTime = 0;

            // 兜底：hurt() 被外部 mod（Boss 限伤/硬上限等）拦截 → 直写血量
            float epsilon = Math.max(0.01F, extraDamage * 0.01F);
            if (target.isAlive() && actualDealt < extraDamage - epsilon) {
                float correctedHealth = Math.max(healthBefore - extraDamage, 0.0F);
                HealthUtil.setAllHealthLikeRaw(target, correctedHealth);
                if (correctedHealth <= 0.0F) {
                    target.invulnerableTime = 0;
                    target.setLastHurtByMob(attacker);
                    target.setLastHurtByPlayer(attacker);
                    attacker.awardKillScore(target, 1, target.level().damageSources().mobAttack(attacker));
                    setDeathScoreNegativeOne(target);  // 防止 die() 内部重复计数
                    target.die(source);
                }
            }
        });
    }

    // ==================== 4. 影杀 — 影子血量 + 饱和式秒杀 ====================

    /**
     * 影杀：在攻击者 NBT 中维护每个目标的独立影子血量，每次攻击按
     * (flatDamage + maxHP×hpRatio) 削减。影子血量归零时触发饱和式秒杀。
     * <p>
     * 影子血量存储在<b>攻击者（玩家）</b>侧 persistentData，而非目标侧——
     * Boss 无法通过 NBT 清理/阶段切换等手段逃脱。
     */
    private static void handleShadowKill(LivingHurtEvent event, LivingEntity target, Player attacker) {
        if (target instanceof Player) return;  // PVP 无效
        if (!target.isAlive()) return;

        AdventureProgressCapability.getAdventureProgress(attacker).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("shadow_kill")) return;

            // 跳过内部穿透伤害，防重入
            if (isInternalSource(event.getSource())) return;
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
            List<String> expiredKeys = new ArrayList<>();
            for (String uuidKey : shadowData.getAllKeys()) {
                CompoundTag entry = shadowData.getCompound(uuidKey);
                if (entry.getLong(NBT_SP_END_TIME) <= gameTime) {
                    expiredKeys.add(uuidKey);
                }
            }
            for (String uuidKey : expiredKeys) {
                shadowData.remove(uuidKey);
                removeShadowHPBossBarByUUID(uuidKey);
            }

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
            entry.putLong(NBT_SP_END_TIME, gameTime + 6000L); // 5 分钟过期
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

                DamageSource killSource = event.getSource();
                event.setCanceled(true);  // 取消原事件伤害，避免与下面 hurt() 叠加

                target.setLastHurtByMob(attacker);
                target.setLastHurtByPlayer(attacker);
                target.invulnerableTime = 0;

                // ★ 第一层：走标准 hurt() 管线
                KILLING.add(target.getUUID());
                try {
                    target.hurt(killSource, Float.MAX_VALUE);
                } finally {
                    KILLING.remove(target.getUUID());
                }

                // ★ 第二层兜底：hurt() 被 Boss 拦截 → 饱和式秒杀
                if (target.isAlive()) {
                    saturationKill(target, killSource, attacker);
                }

                // 觉醒：影杀 AOE 爆炸
                if (progress.isFullyUnlocked()) {
                    shadowKillAoe(attacker, target, totalHP);
                }
            }
        });
    }

    /** 饱和式秒杀 — 当 hurt() / die() 全部被拦截时的最终手段 */
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
    }

    // ==================== 影杀辅助：BossBar ====================

    private static void updateShadowHPBossBar(LivingEntity target, Player player,
                                               float shadowHP, float totalHP) {
        if (!(player instanceof ServerPlayer sp)) return;
        float progress = totalHP > 0.0F ? Math.max(0.0F, Math.min(1.0F, shadowHP / totalHP)) : 0.0F;
        String nameShadow = String.format("%.1f", shadowHP);
        String nameTotal = String.format("%.1f", totalHP);

        ServerBossEvent bar = SHADOW_HP_BARS.computeIfAbsent(target.getUUID(), uuid -> {
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
        ServerBossEvent bar = SHADOW_HP_BARS.remove(target.getUUID());
        if (bar != null) {
            bar.removeAllPlayers();
        }
    }

    private static void removeShadowHPBossBarByUUID(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            ServerBossEvent bar = SHADOW_HP_BARS.remove(uuid);
            if (bar != null) bar.removeAllPlayers();
        } catch (IllegalArgumentException ignored) {}
    }

    /** 觉醒影杀 AOE：对斩杀目标周围实体施加影子血量削减 */
    private static void shadowKillAoe(Player attacker, LivingEntity killed, float baseTotalHP) {
        double radius = com.rosycentury.adventure_power.config.ModConfig.AWAKEN_SHADOW_KILL_AOE_RADIUS.get();
        float ratio = (float) (double) com.rosycentury.adventure_power.config.ModConfig.AWAKEN_SHADOW_KILL_AOE_RATIO.get();
        int maxTargets = com.rosycentury.adventure_power.config.ModConfig.AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS.get();

        AABB aabb = killed.getBoundingBox().inflate(radius);
        java.util.List<LivingEntity> nearby = killed.level().getEntitiesOfClass(LivingEntity.class, aabb,
            e -> e != attacker && e != killed && e.isAlive()
                && !(e instanceof Player)
                && e instanceof net.minecraft.world.entity.monster.Monster);

        int count = 0;
        CompoundTag playerData = attacker.getPersistentData();
        CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
        long gameTime = attacker.level().getGameTime();

        for (LivingEntity target : nearby) {
            if (count >= maxTargets) break;

            float totalHP = target.getMaxHealth();
            float aoeReduction = totalHP * ratio;

            String targetKey = target.getUUID().toString();
            float existingShadow;
            if (shadowData.contains(targetKey)) {
                CompoundTag entry = shadowData.getCompound(targetKey);
                existingShadow = entry.getFloat(NBT_SP_SHADOW_HP);
            } else {
                existingShadow = totalHP;
            }
            float newShadow = Math.max(0.0F, existingShadow - aoeReduction);

            CompoundTag entry = new CompoundTag();
            entry.putFloat(NBT_SP_TOTAL_HP, totalHP);
            entry.putFloat(NBT_SP_SHADOW_HP, newShadow);
            entry.putLong(NBT_SP_END_TIME, gameTime + 6000L);
            shadowData.put(targetKey, entry);

            updateShadowHPBossBar(target, attacker, newShadow, totalHP);
            count++;

            if (killed.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.SCULK_SOUL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    10, 0.5, 0.5, 0.5, 0.02);
            }
        }
        if (!shadowData.isEmpty()) {
            playerData.put(NBT_SP_DATA, shadowData);
        }
    }

    /** 将实体的 deathScore 设为 -1，阻止 die() 内部重复调用 awardKillScore */
    private static void setDeathScoreNegativeOne(LivingEntity target) {
        try {
            java.lang.reflect.Field f = LivingEntity.class.getDeclaredField("f_20920_");
            f.setAccessible(true);
            f.setInt(target, -1);
        } catch (NoSuchFieldException e) {
            try {
                java.lang.reflect.Field f = LivingEntity.class.getDeclaredField("deathScore");
                f.setAccessible(true);
                f.setInt(target, -1);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /** 内部穿透伤害检测 — 防递归 */
    private static boolean isInternalSource(DamageSource source) {
        String msgId = source.getMsgId();
        return "soul_strike".equals(msgId) || "judgment".equals(msgId);
    }

    // ==================== 影杀辅助：定时全局清理过期影子血量 ====================

    /**
     * 每 200 tick（10 秒）遍历所有在线玩家，清理 persistentData 中过期的影子血量条目
     * 以及对应的 BossBar。防止玩家长时间不攻击导致过期数据/残血条堆积。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent event) {
        if (event.phase != Phase.END) return;

        shadowHpCleanupTick++;
        if (shadowHpCleanupTick < 200) return;
        shadowHpCleanupTick = 0;

        MinecraftServer server = event.getServer();
        if (server == null) return;

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            CompoundTag playerData = sp.getPersistentData();
            CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
            if (shadowData.isEmpty()) continue;

            long gameTime = sp.level().getGameTime();
            List<String> expired = new ArrayList<>();
            for (String uuidKey : shadowData.getAllKeys()) {
                CompoundTag entry = shadowData.getCompound(uuidKey);
                if (entry.getLong(NBT_SP_END_TIME) <= gameTime) {
                    expired.add(uuidKey);
                }
            }

            if (!expired.isEmpty()) {
                for (String uuidKey : expired) {
                    shadowData.remove(uuidKey);
                    try {
                        UUID uuid = UUID.fromString(uuidKey);
                        ServerBossEvent bar = SHADOW_HP_BARS.remove(uuid);
                        if (bar != null) bar.removeAllPlayers();
                    } catch (IllegalArgumentException ignored) {}
                }
                if (shadowData.isEmpty()) {
                    playerData.remove(NBT_SP_DATA);
                } else {
                    playerData.put(NBT_SP_DATA, shadowData);
                }
            }
        }
    }

    /**
     * 玩家登出时清理该玩家关联的所有影子血量 BossBar，
     * 防止 BossBar 持有的 ServerPlayer 引用在玩家离线后变为无效，造成内存泄漏。
     * 影子血量数据本身保留在 persistentData 中，下次登录时继续使用。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CompoundTag playerData = event.getEntity().getPersistentData();
        CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
        for (String uuidKey : shadowData.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(uuidKey);
                ServerBossEvent bar = SHADOW_HP_BARS.remove(uuid);
                if (bar != null) bar.removeAllPlayers();
            } catch (IllegalArgumentException ignored) {}
        }
    }

    // ==================== 5. 禁疗之触 — 攻击施加禁疗 ====================

    private static void handleHealingBlock(LivingHurtEvent event, LivingEntity target, Player attacker) {
        // 跳过内部穿透伤害，防止淬魂等内部 hurt() 刷新禁疗计时器
        if (isInternalSource(event.getSource())) return;

        AdventureProgressCapability.getAdventureProgress(attacker).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("healing_block")) return;

            int milestones = progress.getUnlockedMilestoneCount();
            Ability ability = AbilityRegistry.get("healing_block");
            if (ability == null) return;

            int durationSeconds = (int) ability.value(milestones);
            if (progress.isFullyUnlocked()) {
                durationSeconds = (int) Math.ceil(durationSeconds * com.rosycentury.adventure_power.config.ModConfig.AWAKEN_MULTIPLIER.get());
            }
            int durationTicks = durationSeconds * 20;
            UndyingSlashEffect.apply(target, durationTicks);
        });
    }
}
