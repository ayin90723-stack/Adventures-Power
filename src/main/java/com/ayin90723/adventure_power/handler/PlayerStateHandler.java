package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 玩家状态类能力效果处理器。
 * <p>
 * 管理 4 种冒险能力的运行时效果：
 * <ul>
 *   <li>灵魂绑定 (soul_bind) — 死亡保 Buff + 经验</li>
 *   <li>净魂 (purified_soul) — 免疫负面效果</li>
 *   <li>翱翔 (soar) — 创造飞行</li>
 *   <li>受击坚韧 (resilience) — 受伤叠层减伤</li>
 * </ul>
 * <p>
 * 门禁检查：soul_bind / purified_soul / soar 需要 isAdventurer() 或 isFullyUnlocked()；
 * resilience 需要 isFullyUnlocked()（已在 AdventureProgressCapability.FULLY_UNLOCKED_ABILITIES 中）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerStateHandler {

    // ========================================================================
    //  灵魂绑定 — persistentData 键
    // ========================================================================

    /** 死亡前保存的 Buff 列表 NBT 键 */
    private static final String SOUL_BIND_BUFFS_KEY = "AP_SoulBind_Buffs";

    /** 死亡前保存的经验值 NBT 键 */
    private static final String SOUL_BIND_EXP_KEY = "AP_SoulBind_Exp";

    // ========================================================================
    //  不朽装备觉醒 — 属性加成
    // ========================================================================

    private static final UUID AWAKEN_UNDYING_ARMOR_UUID =
        UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID AWAKEN_UNDYING_WEAPON_UUID =
        UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");

    // ========================================================================
    //  1. 灵魂绑定 (SoulBind) — 死亡保 Buff + 经验
    // ========================================================================

    /**
     * 玩家死亡前保存活跃效果和经验值到 persistentData。
     * <p>
     * 优先级 HIGHEST 以确保在其他修改死亡的逻辑之前保存数据。
     * 即使死亡被其他 handler 取消，数据已写入也不影响（clone 时会按数据恢复）。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("soul_bind")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            // 保存所有活跃效果（包括正面和负面，死后复活时统一恢复原状）
            CompoundTag buffsTag = new CompoundTag();
            ListTag effectList = new ListTag();
            for (MobEffectInstance effect : player.getActiveEffects()) {
                effectList.add(effect.save(new CompoundTag()));
            }
            buffsTag.put("effects", effectList);
            player.getPersistentData().put(SOUL_BIND_BUFFS_KEY, buffsTag);

            // 保存总经验值
            player.getPersistentData().putInt(SOUL_BIND_EXP_KEY, player.totalExperience);

            // 觉醒：死亡时清零经验等级防止掉落经验球（经验值已在上方保存，重生后恢复）
            if (progress.isFullyUnlocked()) {
                player.experienceLevel = 0;
                player.experienceProgress = 0.0F;
                player.totalExperience = 0;
            }
        });
    }

    /**
     * 死亡重生后恢复保存的 Buff 和经验。
     * <p>
     * 从原始（死亡）玩家的 persistentData 中读取，写入新玩家。
     * 只处理死亡导致的 clone（维度切换等无保存数据，自然跳过）。
     * <p>
     * 非死亡 clone（末地返回传送门等）也需要转移 soul_bind 残留 key，
     * 并恢复翱翔飞行能力（维度切换后 mayfly 被重置）。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        if (event.isWasDeath()) {
            // 死亡 clone：恢复保存的 Buff 和经验
            CompoundTag buffsTag = original.getPersistentData().getCompound(SOUL_BIND_BUFFS_KEY);
            if (!buffsTag.isEmpty()) {
                ListTag effectList = buffsTag.getList("effects", Tag.TAG_COMPOUND);
                for (int i = 0; i < effectList.size(); i++) {
                    MobEffectInstance effect = MobEffectInstance.load(effectList.getCompound(i));
                    if (effect != null) {
                        player.addEffect(effect);
                    }
                }
                original.getPersistentData().remove(SOUL_BIND_BUFFS_KEY);
            }

            if (original.getPersistentData().contains(SOUL_BIND_EXP_KEY)) {
                int savedExp = original.getPersistentData().getInt(SOUL_BIND_EXP_KEY);
                if (savedExp > 0) {
                    player.giveExperiencePoints(savedExp);
                }
                original.getPersistentData().remove(SOUL_BIND_EXP_KEY);
            }
        } else {
            // 非死亡 clone（维度切换）：转移可能残留的 soul_bind 数据
            transferSoulBindData(original, player);
        }

        // 维度切换后恢复翱翔飞行能力（mayfly 被重置）
        // 非死亡 clone（维度切换）时保留原有的飞行状态，防止飞行中穿越传送门后坠落
        boolean wasFlying = !event.isWasDeath() && original.getAbilities().flying;
        restoreSoarFlight(player, wasFlying);

        // Clone 时清理不朽装备觉醒属性（会由 tick 重新应用）
        removeUndyingGearAwakened(player);
    }

    @SubscribeEvent
    public static void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        removeUndyingGearAwakened(event.getEntity());
    }

    /** 游戏模式切换后恢复翱翔飞行能力（原版会在切回生存时重置 mayfly）。
     *  立即同步，不等 tick handler，避免竞态条件。 */
    @SubscribeEvent
    public static void onPlayerChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        if (progress.isAbilityEnabled("soar") && !player.getAbilities().mayfly
            && !player.getAbilities().instabuild && !player.isSpectator()) {
            player.getAbilities().mayfly = true;
            progress.setSoarGrantedFlight(true);
            player.onUpdateAbilities();
        }
    }

    /** 跨维度转移 soul_bind 持久数据，防止末地返回传送门等场景下数据丢失 */
    private static void transferSoulBindData(Player original, Player player) {
        CompoundTag buffsTag = original.getPersistentData().getCompound(SOUL_BIND_BUFFS_KEY);
        if (!buffsTag.isEmpty()) {
            player.getPersistentData().put(SOUL_BIND_BUFFS_KEY, buffsTag);
        }
        if (original.getPersistentData().contains(SOUL_BIND_EXP_KEY)) {
            int exp = original.getPersistentData().getInt(SOUL_BIND_EXP_KEY);
            if (exp > 0) {
                player.getPersistentData().putInt(SOUL_BIND_EXP_KEY, exp);
            }
        }
    }

    /** 翱翔能力开启时恢复飞行许可（维度切换后 Player.Abilities 被重置）。
     *  @param restoreFlying 是否同时恢复 flying 标志（维度切换时保留飞行中状态） */
    private static void restoreSoarFlight(Player player, boolean restoreFlying) {
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isAbilityEnabled("soar")) {
                boolean changed = false;
                if (!player.getAbilities().mayfly) {
                    player.getAbilities().mayfly = true;
                    progress.setSoarGrantedFlight(true);
                    changed = true;
                }
                if (restoreFlying && !player.getAbilities().flying) {
                    player.getAbilities().flying = true;
                    changed = true;
                }
                if (changed) {
                    player.onUpdateAbilities();
                }
            }
        });
    }

    // ========================================================================
    //  环境免疫 (EnvImmunity) — 免疫所有环境伤害
    // ========================================================================

    /**
     * 拦截所有环境伤害（火焰/岩浆/仙人掌/冰冻/溺水/摔落/闪电/钟乳石/甜浆果等）。
     * <p>
     * 使用 DamageTypeTags 判断伤害类型，覆盖原版全部环境伤害。
     * 生物攻击（mob_attack / player_attack / arrow 等）不受影响。
     */
    @SubscribeEvent
    public static void onEnvDamage(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("env_immunity")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            DamageSource source = event.getSource();

            // 觉醒：免疫所有无源伤害（不仅是标签覆盖的环境伤害）
            if (progress.isFullyUnlocked()
                && source.getEntity() == null
                && source.getDirectEntity() == null) {
                event.setCanceled(true);
                return;
            }

            // 非觉醒：仅排除生物造成的伤害（即使附带火焰/魔法属性也不拦截，那是战斗伤害）
            if (source.getEntity() != null) return;

            // 拦截所有标记为非生物的环境伤害
            if (source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.IS_FREEZING)
                || source.is(DamageTypeTags.IS_LIGHTNING)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypeTags.IS_DROWNING)) {
                event.setCanceled(true);
                return;
            }

            // 拦截标签未覆盖的环境伤害类型
            String msgId = source.getMsgId();
            if ("cactus".equals(msgId)
                || "sweetBerryBush".equals(msgId)
                || "stalagmite".equals(msgId)
                || "inWall".equals(msgId)
                || "hotFloor".equals(msgId)
                || "starve".equals(msgId)
                || "wither".equals(msgId)
                || "dryOut".equals(msgId)) {
                event.setCanceled(true);
            }
        });
    }

    // ========================================================================
    //  2. 净魂 (PurifiedSoul) — 免疫负面效果
    // ========================================================================

    /**
     * 净魂通过 {@link #onPlayerTick} 中每 tick 清除残留负面效果实现。
     * <p>
     * 不再使用 {@code MobEffectEvent.Applicable}——该事件在 Forge 1.20.1 中不可取消，
     * RevelationFix 等模组的 Mixin 会对其 {@code setCanceled()} 抛出
     * {@link UnsupportedOperationException}。
     * tick 兜底方案延迟最多 1 tick，玩家无感知。
     */

    // ========================================================================
    //  3 & 4. 翱翔 (Soar) + 净魂兜底 + 受击坚韧兜底 — 每 Tick
    // ========================================================================

    /**
     * 每 tick 处理：
     * <ul>
     *   <li>净魂：清除残留的负面效果（兜底，覆盖 Applicable 未捕获的路径）</li>
     *   <li>翱翔：维持 mayfly = true（死亡/维度切换后重置），能力关闭时回收</li>
     * </ul>
     * <p>
     * 受击坚韧的 tick 逻辑（超时归零）已在
     * {@link AdventureProgressCapability#onPlayerTick} 中处理，此处不重复。
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        // 门禁：至少需要 adventurer 状态
        boolean isGateOpen = progress.isAdventurer() || progress.isFullyUnlocked();
        if (!isGateOpen) return;

        // ---- 净魂兜底 + 觉醒虚弱光环 ----
        if (progress.isAbilityEnabled("purified_soul")) {
            player.getActiveEffects().stream()
                .filter(e -> e.getEffect().getCategory() == MobEffectCategory.HARMFUL)
                .map(MobEffectInstance::getEffect)
                .toList()
                .forEach(player::removeEffect);

            // 觉醒：每2秒给周围敌对生物施加虚弱II
            if (progress.isFullyUnlocked()
                && player.level().getGameTime() % 40 == 0) {
                int radius = com.ayin90723.adventure_power.config.ModConfig.AWAKEN_PURIFIED_SOUL_RADIUS.get();
                AABB aabb = player.getBoundingBox().inflate(radius);
                List<net.minecraft.world.entity.LivingEntity> targets = player.level()
                    .getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, aabb,
                        e -> e != player && e.isAlive()
                            && e instanceof net.minecraft.world.entity.monster.Monster);
                for (net.minecraft.world.entity.LivingEntity target : targets) {
                    net.minecraft.world.effect.MobEffectInstance existing = target.getEffect(MobEffects.WEAKNESS);
                    if (existing == null || existing.getAmplifier() < 1
                        || existing.getDuration() < 60) {
                        target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            MobEffects.WEAKNESS, 100, 1,
                            false, false, true));
                    }
                }
            }
        }

        // ---- 翱翔 ----
        if (progress.isAbilityEnabled("soar")) {
            if (!player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
                // 不自动开启 flying，让玩家自己双击空格
                player.onUpdateAbilities();
            }
            // 标记飞行由翱翔授予，用于关闭时精准回收
            progress.setSoarGrantedFlight(true);
            // 觉醒：飞行速度 +50%（只在值不同时写入，不在 tick 中重复乘法避免指数爆炸）
            double targetSpeed = progress.isFullyUnlocked()
                ? 0.05 * com.ayin90723.adventure_power.config.ModConfig.AWAKEN_SOAR_SPEED.get()
                : 0.05;
            if (Math.abs(player.getAbilities().getFlyingSpeed() - targetSpeed) > 0.0001) {
                player.getAbilities().setFlyingSpeed((float) targetSpeed);
            }
        } else {
            // 能力关闭/未解锁时回收翱翔授予的飞行，不没收装备或其他模组提供的飞行
            if (player.getAbilities().mayfly && progress.isSoarGrantedFlight()
                && !player.getAbilities().instabuild && !player.isSpectator()) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.getAbilities().setFlyingSpeed(0.05F);  // 恢复原版飞行速度
                progress.setSoarGrantedFlight(false);
                player.onUpdateAbilities();
            }
        }

        // ---- 不朽装备觉醒：属性加成 ----
        if (progress.isAbilityEnabled("undying_gear") && progress.isFullyUnlocked()) {
            applyUndyingGearAwakened(player);
        } else {
            removeUndyingGearAwakened(player);
        }

        // ---- 旅者庇护觉醒：减速而非锁定 ----
        long sanctuaryNow = player.level().getGameTime();
        boolean inSanctuary = progress.getSanctuaryInvulEnd() > sanctuaryNow
            && progress.isFullyUnlocked()
            && progress.isAbilityEnabled("active_skill");
        var sanctuarySpeedAttr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (inSanctuary && sanctuarySpeedAttr != null) {
            double target = 0.1 * com.ayin90723.adventure_power.config.ModConfig.AWAKEN_SANCTUARY_SPEED.get();
            if (Math.abs(sanctuarySpeedAttr.getBaseValue() - target) > 0.001) {
                sanctuarySpeedAttr.setBaseValue(target);
            }
        } else if (!inSanctuary && sanctuarySpeedAttr != null) {
            // 庇护结束后恢复原版移动速度
            double slowSpeed = 0.1 * com.ayin90723.adventure_power.config.ModConfig.AWAKEN_SANCTUARY_SPEED.get();
            if (Math.abs(sanctuarySpeedAttr.getBaseValue() - slowSpeed) < 0.001) {
                sanctuarySpeedAttr.setBaseValue(0.1);
            }
        }
    }

    // ========================================================================
    //  4. 受击坚韧 (Resilience) — 受伤叠层 + 减伤
    // ========================================================================

    /**
     * 玩家受伤时：
     * <ol>
     *   <li>基于已有层数按比例减伤（每层 5%）</li>
     *   <li>叠层 + 1（上限由 {@link AbilityRegistry#get} 的 value 决定）</li>
     *   <li>更新最后受伤时间戳</li>
     * </ol>
     * <p>
     * 优先级 LOW 以确保在大多数伤害修改之后执行，拿到最终伤害值。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled("resilience")) return;

            long currentTime = player.level().getGameTime();

            // 基于已有层数减伤
            int stacks = progress.getResilienceStacks();
            if (stacks > 0) {
                float reduction = stacks * com.ayin90723.adventure_power.config.ModConfig.RESILIENCE_DAMAGE_REDUCTION_PER_STACK.get().floatValue();
                float newAmount = event.getAmount() * (1.0F - reduction);
                event.setAmount(Math.max(newAmount, 0.0F));
            }

            // 叠层（上限由能力里程碑配置决定，觉醒 +6）
            int maxStacks = (int) ((com.ayin90723.adventure_power.ability.ResilienceAbility)
                com.ayin90723.adventure_power.ability.AbilityRegistry.get("resilience"))
                .value(progress.getUnlockedMilestoneCount(), progress.isFullyUnlocked());
            if (stacks < maxStacks) {
                progress.setResilienceStacks(stacks + 1);
            }

            // 更新时间戳（供 AdventureProgressCapability.onPlayerTick 超时归零使用）
            progress.setLastHurtTime(currentTime);
        });
    }

    // ========================================================================
    //  不朽装备觉醒 — 属性加成
    // ========================================================================

    private static void applyUndyingGearAwakened(Player player) {
        var armorAttr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        if (armorAttr != null) {
            var existing = armorAttr.getModifier(AWAKEN_UNDYING_ARMOR_UUID);
            // count equipped armor pieces
            int pieces = 0;
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR
                    && !player.getItemBySlot(slot).isEmpty()) {
                    pieces++;
                }
            }
            double bonus = pieces * com.ayin90723.adventure_power.config.ModConfig.AWAKEN_UNDYING_ARMOR_BONUS.get();
            if (existing != null) {
                if (Math.abs(existing.getAmount() - bonus) > 0.001) {
                    armorAttr.removeModifier(AWAKEN_UNDYING_ARMOR_UUID);
                } else {
                    return; // value unchanged
                }
            }
            armorAttr.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                AWAKEN_UNDYING_ARMOR_UUID, "awakened_undying_armor", bonus,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
        }

        var atkAttr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if (atkAttr != null) {
            var existing = atkAttr.getModifier(AWAKEN_UNDYING_WEAPON_UUID);
            double weaponBonus = com.ayin90723.adventure_power.config.ModConfig.AWAKEN_UNDYING_WEAPON_BONUS.get();
            if (existing != null && Math.abs(existing.getAmount() - weaponBonus) <= 0.001) {
                return;
            }
            if (existing != null) {
                atkAttr.removeModifier(AWAKEN_UNDYING_WEAPON_UUID);
            }
            atkAttr.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                AWAKEN_UNDYING_WEAPON_UUID, "awakened_undying_weapon", weaponBonus,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_BASE));
        }
    }

    private static void removeUndyingGearAwakened(Player player) {
        var armorAttr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        if (armorAttr != null && armorAttr.getModifier(AWAKEN_UNDYING_ARMOR_UUID) != null) {
            armorAttr.removeModifier(AWAKEN_UNDYING_ARMOR_UUID);
        }
        var atkAttr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if (atkAttr != null && atkAttr.getModifier(AWAKEN_UNDYING_WEAPON_UUID) != null) {
            atkAttr.removeModifier(AWAKEN_UNDYING_WEAPON_UUID);
        }
    }
}
