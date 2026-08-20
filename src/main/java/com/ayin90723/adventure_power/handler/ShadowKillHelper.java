package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.mojang.logging.LogUtils;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.ability.ShadowKillAbility;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.util.DamageUtil;
import com.ayin90723.adventure_power.util.probe.BloodWriteEngine;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.HealthUtil;
import com.ayin90723.adventure_power.config.ModConfig;
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

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    // ==================== 影杀 — 影子血量 NBT 键 (攻击者侧存储) ====================
    private static final String NBT_SP_DATA = PersistentDataKeys.SHADOW_HP_DATA;
    private static final String NBT_SP_TOTAL_HP = PersistentDataKeys.SHADOW_HP_TOTAL;
    private static final String NBT_SP_SHADOW_HP = PersistentDataKeys.SHADOW_HP_CURRENT;
    private static final String NBT_SP_END_TIME = PersistentDataKeys.SHADOW_HP_END_TIME;

    /** 防重入：正在斩杀中的目标 */
    private static final Set<UUID> KILLING = ConcurrentHashMap.newKeySet();

    /**
     * 同 tick 去重：防止原版 post + Layer 0 postHurtEvent 导致影杀调两次（双倍削减）。
     * 按 (attacker, target) 复合 key——多人同 tick 攻击同一目标时，
     * 不同攻击者的影子血量削减互不跳过，只防同一攻击者的重复结算。
     */
    private static final Set<String> SHADOW_KILL_TICKED = ConcurrentHashMap.newKeySet();

    /** target 有效性检测宽限：跨维度传送时短暂不在任何维度，避免误清。
     *  复合 key = attacker:target（v1.3.6）：多人同打一目标时各攻击者独立计数——
     *  共享计数会被同周期多个攻击者各自 +1 叠加，1 个周期被当成 2 个周期误清影子血量 */
    private static final Map<String, Integer> MISSING_TARGET_TICKS = new ConcurrentHashMap<>();

    /** MISSING 计数复合 key：attacker:target（String 版，target 直接取 shadowData 的 uuidKey，免解析） */
    private static String missingKey(UUID attackerId, String targetUuidStr) {
        return attackerId + ":" + targetUuidStr;
    }

    private static String missingKey(UUID attackerId, UUID targetId) {
        return attackerId + ":" + targetId;
    }

    /** dropAllDeathLoot 反射缓存（m_6668_ = dropAllDeathLoot(DamageSource)） */
    private static final java.lang.reflect.Method DROP_ALL_DEATH_LOOT =
        HealthUtil.reflectMethod(LivingEntity.class, "m_6668_", "dropAllDeathLoot", DamageSource.class);

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
    public static void handleShadowKill(LivingHurtEvent event, LivingEntity target, Player attacker, IAdventureProgress progress) {
        if (target instanceof Player) return;  // PVP 无效
        if (!target.isAlive()) return;

        // 跳过内部穿透伤害，防重入
        if (DamageUtil.isInternalSource(event.getSource())) return;
        if (KILLING.contains(target.getUUID())) return;
        // 同 tick 去重：原版 LivingHurtEvent + Layer 0 postHurtEvent 可能同 tick 触发两次
        // （复合 key：同 attacker 同 target 才去重，不影响多人各削各的）
        if (!SHADOW_KILL_TICKED.add(attacker.getUUID() + ":" + target.getUUID())) return;

        Ability raw = AbilityRegistry.get(AbilityIds.SHADOW_KILL);
        if (!(raw instanceof ShadowKillAbility ability)) return;

        // 影杀为固定值能力（无成长），指令解锁后数值天然一致，无需 count 平移
        float flatDamage = ability.flatDamage();
        float hpRatio = ability.hpRatio();

        // 从攻击者侧读取影子血量
        CompoundTag playerData = attacker.getPersistentData();
        CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
        long gameTime = attacker.level().getGameTime();

        // 懒清理过期条目
        cleanupExpiredShadowData(shadowData, gameTime, attacker.getUUID());

        String targetKey = target.getUUID().toString();
        float totalHP, shadowHP;
        boolean isNew = false;

        CompoundTag entry;
        if (shadowData.contains(targetKey)) {
            entry = shadowData.getCompound(targetKey);
            totalHP = entry.getFloat(NBT_SP_TOTAL_HP);
            shadowHP = entry.getFloat(NBT_SP_SHADOW_HP);
        } else {
            totalHP = target.getMaxHealth();
            shadowHP = totalHP;
            isNew = true;
            entry = new CompoundTag();
        }

        // 削减影子血量
        float damage = flatDamage + totalHP * hpRatio;
        shadowHP = Math.max(0.0F, shadowHP - damage);
        DebugLog.shadowKill("[影杀] target={} 影子血量 {} → {}（伤害 {}，{}）",
            target, shadowHP + damage, shadowHP, damage, isNew ? "新目标" : "续削");

        // 写回攻击者侧 NBT（复用 entry，已有条目不需新建）
        entry.putFloat(NBT_SP_TOTAL_HP, totalHP);
        entry.putFloat(NBT_SP_SHADOW_HP, shadowHP);
        entry.putLong(NBT_SP_END_TIME, gameTime + ModConfig.SHADOW_KILL_DATA_EXPIRE_TICKS.get());
        if (isNew) {
            shadowData.put(targetKey, entry);
        }
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
            DebugLog.shadowKill("[影杀] 影子归零 → 饱和式秒杀 target={} attacker={}", target, attacker);
            shadowData.remove(targetKey);
            MISSING_TARGET_TICKS.remove(missingKey(attacker.getUUID(), target.getUUID()));
            if (shadowData.isEmpty()) {
                playerData.remove(NBT_SP_DATA);
            } else {
                playerData.put(NBT_SP_DATA, shadowData);
            }
            removeShadowHPBossBar(target);

            event.setCanceled(true);  // 取消原事件伤害，避免与下面 hurt() 叠加。
            // 取舍（v1.4.0 审查确认）：cancel 使嗜血的 LOW 优先级攻击吸血跳过本次（不收
            // canceled 事件）——影杀为必杀一击，吸血意义让位于击杀回馈（LivingDeathEvent
            // 仍触发，RecoveryHandler 的击杀回馈正常结算），刻意不在此手动补吸血
            target.setLastHurtByMob(attacker);
            target.setLastHurtByPlayer(attacker);
            executeShadowKill(target, attacker);

            // 觉醒：影杀 AOE 爆炸
            if (progress.isFullyUnlocked()) {
                shadowKillAoe(attacker, target);
            }
        }
    }

    // ==================== 饱和式秒杀 ====================

    /**
     * 饱和式秒杀 — 当 hurt() / die() 全部被拦截时的最终手段。
     * 通过多层移除链逐层递增，确保无 Boss 可拦截。
     * <p>
     * v1.4.0 审查修复：分段异常保护——本方法运行在 LivingHurtEvent 分发链上，任一层
     * 未捕获异常会中断同事件的其他监听器且后续层全部跳过（Boss 停留"0 血未移除"
     * 中间态）。各段独立降级捕获：归零段/战利品段失败不阻断移除段；移除段（⑥~⑨）
     * 逐层捕获，保证任一层失败其余层仍执行。
     */
    private static void saturationKill(LivingEntity target, DamageSource source, LivingEntity attacker) {
        Level level = target.level();
        if (!(level instanceof ServerLevel serverLevel)) return;

        // ① 五层改血引擎归零（v1.4.2：淬魂强化同款，处决语义 writeValue=0）——
        //     L1 setter 扫描 → L2 对象图插针（亚波伦 FloatWrapped/灵梦 CombatProgress，WritePath 缓存）
        //     → L3 类静态容器（GraeMod UomWither CACHE 型）→ L4 广义写路径 → DataItem 兜底。
        //     与淬魂共享 per-class 探针缓存（影子血条磨完首次处决探测，后续直接快路径）；
        //     全层失败时引擎内部 raw 兜底等价于原 setHealthLikeAny 行为
        // ①b 全 float 同步数据保险丝 — 砧板之刃[神]同款：清空所有 float 同步条目，
        //     覆盖"不联动 getHealth 的隐藏 float 条目"（插针发现不了的旁路）。
        //     目标即将被删除，副作用（缩放/动画清零）随实体消失
        try {
            BloodWriteEngine.execute(target, 0.0F);
            HealthUtil.zeroAllSynchedFloats(target);
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 归零段失败（①①b），继续移除链 target={}", target, e);
        }

        // ② 强制掉落全套装备 + ③ 反射调用 dropAllDeathLoot（触发战利品表 /
        //     LivingDropsEvent / LootModifier）+ ④ 手动 post LivingDeathEvent（墓碑/任务
        //     模组可正常处理）+ ⑤ 善后清理 —— 战利品/事件段失败不阻断移除
        try {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack equipment = target.getItemBySlot(slot);
                if (!equipment.isEmpty()) {
                    target.spawnAtLocation(equipment.copy());
                    target.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
            if (DROP_ALL_DEATH_LOOT != null) {
                DROP_ALL_DEATH_LOOT.invoke(target, source);
            }
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 战利品段失败（②③），继续移除链 target={}", target, e);
        }
        try {
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(target, source));
            target.unRide();
            target.ejectPassengers();
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 死亡事件/善后段失败（④⑤），继续移除链 target={}", target, e);
        }

        // ⑥ 五重移除链 — 逐层递增，确保无 Boss 可拦截（逐层捕获：任一层失败其余层仍执行）
        try {
            target.remove(Entity.RemovalReason.KILLED);                             // 标准路径
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 移除层1失败 target={}", target, e);
        }
        try {
            target.remove(Entity.RemovalReason.DISCARDED);                          // 双保险
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 移除层2失败 target={}", target, e);
        }
        try {
            HealthUtil.removeDirect(target, Entity.RemovalReason.KILLED);           // 反射 remove() — 绕过 Java 覆写
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 移除层3失败 target={}", target, e);
        }
        try {
            HealthUtil.setRemovedFieldDirect(target, Entity.RemovalReason.KILLED);  // 字段直写 — 绕过一切 Mixin
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 移除层4失败 target={}", target, e);
        }
        // 第5层：CHANGED_DIMENSION 兜底 — 部分 Boss 的 Mixin 仅拦截 KILLED/DISCARDED
        try {
            HealthUtil.setRemovedDirect(target, Entity.RemovalReason.CHANGED_DIMENSION);
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 移除层5失败 target={}", target, e);
        }

        // ⑦ 客户端同步 — 强制通知所有追踪此实体的玩家其已被移除
        try {
            net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket packet =
                new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(target.getId());
            serverLevel.getChunkSource().broadcast(target, packet);
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 客户端移除包发送失败（⑦） target={}", target, e);
        }

        // ⑧ 内部结构抹除 — 从 EntityLookup/EntityTickList/EntitySection 中直接删除实体
        try {
            HealthUtil.eradicateFromWorld(target);
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 容器抹除失败（⑧） target={}", target, e);
        }

        // ⑨ 最终确认 — 若防护 Boss 在移除链中清除了标记（极端场景），兜底重写 removalReason
        try {
            if (!target.isRemoved()) {
                DebugLog.shadowKill("[影杀] 移除标记被清除，兜底重写 target={}", target);
                HealthUtil.setRemovedFieldDirect(target, Entity.RemovalReason.KILLED);
            }
            DebugLog.shadowKill("[影杀] 饱和式秒杀完成 target={} removed={} reason={}",
                target, target.isRemoved(), target.getRemovalReason());
        } catch (Exception e) {
            LOGGER.error("[ShadowKill] 兜底确认失败（⑨） target={}", target, e);
        }
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
    private static void cleanupExpiredShadowData(CompoundTag shadowData, long gameTime, UUID attackerId) {
        java.util.List<String> expired = new java.util.ArrayList<>();
        for (String uuidKey : shadowData.getAllKeys()) {
            CompoundTag entry = shadowData.getCompound(uuidKey);
            if (entry.getLong(NBT_SP_END_TIME) <= gameTime) {
                expired.add(uuidKey);
            }
        }
        for (String uuidKey : expired) {
            shadowData.remove(uuidKey);
            MISSING_TARGET_TICKS.remove(missingKey(attackerId, uuidKey));
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
        double radius = ModConfig.AWAKEN_SHADOW_KILL_AOE_RADIUS.get();
        float ratio = ModConfig.AWAKEN_SHADOW_KILL_AOE_RATIO.get().floatValue();
        int maxTargets = ModConfig.AWAKEN_SHADOW_KILL_AOE_MAX_TARGETS.get();

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
        cleanupExpiredShadowData(shadowData, gameTime, attacker.getUUID());

        long expireTicks = ModConfig.SHADOW_KILL_DATA_EXPIRE_TICKS.get();
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
                MISSING_TARGET_TICKS.remove(missingKey(attacker.getUUID(), target.getUUID()));
                removeShadowHPBossBar(target);
                executeShadowKill(target, attacker);
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
     * 执行影杀斩杀：清无敌帧 → hurt(内部 shadow_kill 伤害源) → saturationKill 兜底。
     * <p>
     * 使用内部伤害源而非普通玩家源：斩杀伤害不会重新触发嗜血吸血/淬魂追加/
     * 影杀影子血量削减等模组能力结算（isInternalSource 统一拦截）。
     * NBT 清理和 BossBar 移除由调用方负责。
     */
    private static void executeShadowKill(LivingEntity target, Player attacker) {
        DamageSource killSource = DamageUtil.createShadowKill(target.level(), attacker);
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
        if (event.isCanceled()) return; // v1.4.0：死亡被其他模组取消（复活类机制）时不清理影子进度
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;

        removeShadowHPBossBar(target);
        String targetKey = target.getUUID().toString();
        for (Player onlinePlayer : target.level().players()) {
            CompoundTag playerData = onlinePlayer.getPersistentData();
            CompoundTag shadowData = playerData.getCompound(NBT_SP_DATA);
            if (shadowData.contains(targetKey)) {
                // 目标已死：清除该攻击者对应的 MISSING 计数（复合 key 按攻击者独立）
                MISSING_TARGET_TICKS.remove(missingKey(onlinePlayer.getUUID(), targetKey));
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

        // 防御性兜底：外部模组 cancel hurt() 导致 ThreadLocal 深度残留时归零
        // （hurt 不跨 tick，tick 末深度必为 0 是不变量，无条件归零安全）
        HealthUtil.resetHurtDepthPerTick();

        // 每 tick 清去重标记（防影杀同 tick 双倍削减）
        SHADOW_KILL_TICKED.clear();

        int interval = ModConfig.SHADOW_KILL_CLEANUP_INTERVAL.get();
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
            cleanupExpiredShadowData(shadowData, gameTime, sp.getUUID());
            cleanupInvalidTargets(shadowData, server, sp.getUUID());

            if (shadowData.isEmpty()) {
                playerData.remove(NBT_SP_DATA);
            } else {
                playerData.put(NBT_SP_DATA, shadowData);
            }
        }
    }

    /**
     * 检测 shadowData 中的 target 是否存在，无效（死亡/卸载/换实体）则清理。
     * 宽限为 2 个清理周期（SHADOW_KILL_CLEANUP_INTERVAL × 2，默认 200×2=400 tick）——
     * 本方法只在全局清理里按周期调用，MISSING_TARGET_TICKS 的计数单位是"清理周期"而非 tick，
     * 防跨维度传送时 target 短暂不在任何维度被误清。
     */
    private static void cleanupInvalidTargets(CompoundTag shadowData, MinecraftServer server, UUID attackerId) {
        Set<UUID> found = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (String uuidKey : shadowData.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(uuidKey);
                    if (found.contains(uuid)) continue;
                    Entity entity = level.getEntity(uuid);
                    if (entity instanceof LivingEntity living && living.isAlive()) {
                        found.add(uuid);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
        for (String uuidKey : new ArrayList<>(shadowData.getAllKeys())) {
            try {
                UUID uuid = UUID.fromString(uuidKey);
                if (!found.contains(uuid)) {
                    int missing = MISSING_TARGET_TICKS.getOrDefault(missingKey(attackerId, uuid), 0) + 1;
                    if (missing >= 2) {
                        shadowData.remove(uuidKey);
                        MISSING_TARGET_TICKS.remove(missingKey(attackerId, uuid));
                        removeShadowHPBossBarByUUID(uuidKey);
                    } else {
                        MISSING_TARGET_TICKS.put(missingKey(attackerId, uuid), missing);
                    }
                } else {
                    MISSING_TARGET_TICKS.remove(missingKey(attackerId, uuid));
                }
            } catch (IllegalArgumentException ignored) {}
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
        // 清理该玩家影子数据对应的 MISSING 计数：其目标离线期间不会被访问，
        // 计数保留只会拖到下轮全局清理才自愈（轻微内存残留）
        CompoundTag shadowData = event.getEntity().getPersistentData().getCompound(NBT_SP_DATA);
        for (String uuidKey : shadowData.getAllKeys()) {
            MISSING_TARGET_TICKS.remove(missingKey(event.getEntity().getUUID(), uuidKey));
        }
    }

    /**
     * 玩家死亡重生/换维度（Clone）时清理该玩家的影子血量 BossBar，
     * 否则 bar 仍持有死亡旧实例，重生后同目标重复广播浪费带宽（客户端按 UUID 去重不显示重复）。
     * 影子血量数据：死亡清零（刻意设计），维度切换由 CapabilityLifecycleHandler 转移。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Map<UUID, ServerBossEvent> inner = SHADOW_HP_BARS.remove(event.getEntity().getUUID());
        if (inner != null) {
            for (ServerBossEvent bar : inner.values()) {
                bar.removeAllPlayers();
            }
        }
    }
}
