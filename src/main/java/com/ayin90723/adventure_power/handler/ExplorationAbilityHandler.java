package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 探索/采集类能力效果处理器。
 * <p>
 * 处理 3 种非战斗能力的实际效果：
 * <ul>
 *   <li>大地之力 (digging_power) — BreakSpeed 事件提升挖掘速度</li>
 *   <li>无形之手 (extended_reach) — 设置 BLOCK_REACH 属性</li>
 *   <li>坚韧之躯 (vitality) — 设置 MAX_HEALTH 属性</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ExplorationAbilityHandler {

    // ==================== 大地之力 — 挖掘速度 ====================

    /**
     * 大地之力：提升玩家挖掘速度。公式：originalSpeed × multiplier
     * <p>
     * 双端处理：BreakSpeed 是双端事件。服务端决定实际破坏进度，客户端若不应用
     * 同样的倍率，进度条按原速增长而破坏按加速完成，表现为「进度条突然跳满」。
     */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();

        AbilityGate.getActiveProgress(player, AbilityIds.DIGGING_POWER).ifPresent(progress -> {
            Ability ability = AbilityRegistry.get(AbilityIds.DIGGING_POWER);
            if (ability == null) return;

            // 觉醒倍率统一走 AbilityGate.awakenedPercent（无 cap 上限，数值语义 = value × 觉醒倍率）
            float multiplier = AbilityGate.awakenedPercent(ability,
                AbilityGate.effectiveCount(progress, AbilityIds.DIGGING_POWER),
                progress.isFullyUnlocked(), Float.MAX_VALUE);

            float speed = event.getOriginalSpeed();
            // 觉醒：取消水中/空中挖掘惩罚（反向补偿原版 getDestroySpeed 的 /5）
            // 原版条件：眼在水中且无水下速掘附魔 -> /5；未着地 -> /5（飞行也受此惩罚）
            if (progress.isFullyUnlocked()) {
                if (player.isEyeInFluid(FluidTags.WATER) && !EnchantmentHelper.hasAquaAffinity(player)) {
                    speed *= 5.0F;
                }
                if (!player.onGround()) {
                    speed *= 5.0F;
                }
            }
            event.setNewSpeed(speed * multiplier);
        });
    }

    // ==================== 无形之手 + 坚韧之躯 — 属性管理 ====================

    /**
     * 每 tick 同步无形之手（BLOCK_REACH）和坚韧之躯（MAX_HEALTH）属性值。
     * 仅在值变化时写入。
     */
    /** 门禁后业务（由 PlayerTickDispatcher 调用）：无形之手 / 坚韧之躯属性同步 */
    public static void onTick(Player player, IAdventureProgress progress) {
        // 全能力关闭时快速短路，避免每 tick 三次 AttributeRegistry.get + getAttribute
        if (!progress.isAbilityEnabled(AbilityIds.EXTENDED_REACH)
            && !progress.isAbilityEnabled(AbilityIds.VITALITY)
            && !progress.isAbilityEnabled(AbilityIds.SWIFT)) {
            // 但关闭的触及属性仍需补一次恢复（"关闭即恢复原值"语义，v1.3.1 起）。
            // 仅 ORIGINAL_* 有记录才执行——从未写入过的玩家不做无用对账。
            if (ORIGINAL_REACH.containsKey(player.getUUID())
                || ORIGINAL_ENTITY_REACH.containsKey(player.getUUID())) {
                syncReachAttribute(player, false,
                    AbilityGate.effectiveCount(progress, AbilityIds.EXTENDED_REACH),
                    progress.isFullyUnlocked());
            }
            // 修复：vitality/swift 同样需要关闭恢复——三能力全关时
            // maxHealth baseValue 加成与速度 modifier 会残留（直到重新开启任一能力或登出）。
            // sync 内部自带残留判定（无记录时按当前值判断是否本模组残留），从未启用的玩家为 no-op。
            syncVitalityAttribute(player, false,
                AbilityGate.effectiveCount(progress, AbilityIds.VITALITY), progress.isFullyUnlocked());
            syncSpeedAttribute(player, false,
                AbilityGate.effectiveCount(progress, AbilityIds.SWIFT), progress.isFullyUnlocked());
            return;
        }

        // ---- 无形之手 ----
        syncReachAttribute(player, progress.isAbilityEnabled(AbilityIds.EXTENDED_REACH),
            AbilityGate.effectiveCount(progress, AbilityIds.EXTENDED_REACH), progress.isFullyUnlocked());

        // ---- 坚韧之躯 ----
        syncVitalityAttribute(player, progress.isAbilityEnabled(AbilityIds.VITALITY),
            AbilityGate.effectiveCount(progress, AbilityIds.VITALITY), progress.isFullyUnlocked());

        // ---- 加速 ----
        syncSpeedAttribute(player, progress.isAbilityEnabled(AbilityIds.SWIFT),
            AbilityGate.effectiveCount(progress, AbilityIds.SWIFT), progress.isFullyUnlocked());
    }

    private static void syncReachAttribute(Player player, boolean enabled, int milestones, boolean fullyUnlocked) {
        Ability ability = AbilityRegistry.get(AbilityIds.EXTENDED_REACH);
        // activeBonus = 能力开启时会写入的加成（与 enabled 无关，供残留判定用）
        float activeBonus = 0.0F;
        if (ability != null) {
            activeBonus = AbilityGate.awakenedPercent(ability, milestones, fullyUnlocked, Float.MAX_VALUE);
        }
        float bonus = enabled ? activeBonus : 0.0F;
        // 方块触及 + 实体触及（攻击距离）
        applyReachAttr(player, ForgeMod.BLOCK_REACH.get(), bonus, activeBonus);
        applyReachAttr(player, ForgeMod.ENTITY_REACH.get(), bonus, activeBonus);
    }

    /**
     * 触及属性首次写入前的原始 baseValue（登出/关闭能力恢复用，与 ORIGINAL_MAX_HEALTH 同模式）。
     * 其他模组可能以 baseValue 形式持久化触及距离，无条件恢复默认值会覆盖其数据。
     * BLOCK_REACH 与 ENTITY_REACH 分别记录原值（v1.3.1 起 entity 侧同样恢复原值而非默认值）。
     */
    private static final Map<UUID, Double> ORIGINAL_REACH = new HashMap<>();
    private static final Map<UUID, Double> ORIGINAL_ENTITY_REACH = new HashMap<>();

    /**
     * 设置触及属性 baseValue：
     * targetBonus &gt; 0（能力启用）时 = 默认 + targetBonus，首次真正写入前记录原值（putIfAbsent，多次启用只保留第一次）；
     * targetBonus = 0（关闭/未解锁）时恢复首次写入前的原值而非默认值——从未记录过原值（从未写入过，
     * 或崩溃/强杀重启 Map 清空）时做<b>残留判定</b>：当前值 ≈ 本模组开启时会写的值（默认 + activeBonus）
     * 则判定为本模组残留，回归默认值；否则视为其他模组的修改，记录当前值为原值不覆盖
     * （v1.3.1 起承诺：不覆盖其他模组对触及距离 baseValue 的持久化修改）。
     *
     * @param targetBonus 本次要写入的加成（关闭/未解锁时为 0）
     * @param activeBonus 能力开启时会写入的加成（与 targetBonus 无关，仅用于残留判定）
     */
    private static void applyReachAttr(Player player,
                                       net.minecraft.world.entity.ai.attributes.Attribute attr,
                                       float targetBonus, float activeBonus) {
        if (attr == null) return;
        var inst = player.getAttribute(attr);
        if (inst == null) return;
        boolean block = attr == ForgeMod.BLOCK_REACH.get();
        Map<UUID, Double> originals = block ? ORIGINAL_REACH : ORIGINAL_ENTITY_REACH;
        UUID uuid = player.getUUID();

        if (targetBonus > 0) {
            double expected = attr.getDefaultValue() + targetBonus;
            if (Math.abs(inst.getBaseValue() - expected) > 0.001) {
                originals.putIfAbsent(uuid, inst.getBaseValue());
                inst.setBaseValue(expected);
            }
        } else {
            Double restore = originals.get(uuid);
            if (restore == null) {
                double own = attr.getDefaultValue() + activeBonus;
                restore = Math.abs(inst.getBaseValue() - own) <= 0.001
                    ? attr.getDefaultValue()
                    : inst.getBaseValue();
                originals.putIfAbsent(uuid, restore);
            }
            if (Math.abs(inst.getBaseValue() - restore) > 0.001) {
                inst.setBaseValue(restore);
            }
        }
    }

    /**
     * 最大生命首次写入前的原始 baseValue（登出/关闭能力恢复用）。
     * 其他模组（耐力/体质类）常以 baseValue 形式持久化 maxHealth，
     * 登出无条件重置为 20 会覆盖其存档数据且无法回滚。
     */
    private static final Map<UUID, Double> ORIGINAL_MAX_HEALTH = new HashMap<>();

    private static void syncVitalityAttribute(Player player, boolean enabled, int milestones, boolean fullyUnlocked) {
        var attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        Ability ability = AbilityRegistry.get(AbilityIds.VITALITY);
        if (ability == null) return;

        double currentVal = attr.getBaseValue();
        // activeBonus = 启用时会写入的加成（与 enabled 无关，供残留判定用）
        // 坚韧之躯觉醒 ×1.5 向上取整（仅觉醒取整，未觉醒保持原值——避免小数配置下未觉醒 +1）
        float bonus = AbilityGate.awakenedPercent(ability, milestones, fullyUnlocked, Float.MAX_VALUE);
        if (fullyUnlocked) {
            bonus = (float) Math.ceil(bonus);
        }

        if (enabled) {
            double expected = 20.0 + bonus;
            if (Math.abs(currentVal - expected) > 0.001) {
                ORIGINAL_MAX_HEALTH.putIfAbsent(player.getUUID(), currentVal);
                attr.setBaseValue(expected);
                // 如果当前血量超过新上限，裁剪到新上限
                if (player.getHealth() > expected) {
                    clampHealthTo(player, (float) expected);
                }
            }
        } else {
            // 关闭能力：恢复到本模组记录的原值（其他模组的 baseValue 修改不被覆盖）。
            Double restore = ORIGINAL_MAX_HEALTH.get(player.getUUID());
            if (restore == null) {
                // 无记录（从未启用，或服务端重启后静态 Map 清空、player.dat 已保存 20+bonus 的残留值）：
                // 残留判定——当前值 ≈ 本模组启用时会写的值（20+bonus）则判定为本模组残留，回归默认 20；
                // 否则视为其他模组的修改，**不记录、不操作**——若无条件记录当前值为原值，
                // 从未启用过本能力的玩家其 baseValue 会被永久"锁存"，之后其他模组的临时修改
                // 会被本模组每 tick 还原并强制裁剪血量（三能力全关的短路分支每 tick 调用本方法）。
                double own = 20.0 + bonus;
                if (Math.abs(currentVal - own) > 0.001) return;
                restore = 20.0;
                ORIGINAL_MAX_HEALTH.put(player.getUUID(), restore);
            }
            if (Math.abs(currentVal - restore) > 0.001) {
                // 裁剪血量到目标上限以下再调低上限，防止血量卡在异常值
                if (player.getHealth() > restore) {
                    clampHealthTo(player, restore.floatValue());
                }
                attr.setBaseValue(restore);
            }
        }
    }

    /**
     * 模组内部血量裁剪（降上限时把血量裁到新上限）。
     * 包 {@link HealthUtil#INTERNAL_HEALTH_WRITE} 标记：放行 RejectHealthManipMixin 的
     * reject_manip 拦截（否则"降血被拒 + 上限已降"造成 health > maxHealth 卡死），
     * 同时让 TrueHealthMixin 同步备份（否则 backup 冻结旧值、getHealth 会把裁剪反向修复）。
     */
    private static void clampHealthTo(Player player, float target) {
        HealthUtil.INTERNAL_HEALTH_WRITE.set(true);
        try {
            player.setHealth(target);
        } finally {
            HealthUtil.INTERNAL_HEALTH_WRITE.remove();
        }
    }

    /** 加速·移速加成的 modifier UUID（固定，用于移除/更新） */
    private static final UUID SWIFT_SPEED_MODIFIER_UUID =
        java.util.UUID.fromString("c4f2a3b1-7d8e-4a6b-9c0d-1e2f3a4b5c6d");

    private static void syncSpeedAttribute(Player player, boolean enabled, int milestones, boolean fullyUnlocked) {
        var attr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return;
        float bonus = 0.0F;
        if (enabled) {
            Ability ability = AbilityRegistry.get(AbilityIds.SWIFT);
            if (ability != null) {
                // 移速加成比例（类似迅捷药水），觉醒倍率统一走 AbilityGate
                bonus = AbilityGate.awakenedPercent(ability, milestones, fullyUnlocked, Float.MAX_VALUE);
            }
        }
        // 仅在加成变化时更新 modifier，避免每 tick add/remove 开销
        AttributeModifier existing = attr.getModifier(SWIFT_SPEED_MODIFIER_UUID);
        if (bonus > 0.0F) {
            if (existing == null || existing.getAmount() != bonus) {
                if (existing != null) attr.removeModifier(SWIFT_SPEED_MODIFIER_UUID);
                // MULTIPLY_TOTAL：与迅捷药水同 operation，作为额外附加速度，不改基础值
                attr.addTransientModifier(new AttributeModifier(SWIFT_SPEED_MODIFIER_UUID,
                    "adventure_power_swift", bonus, AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
        } else if (existing != null) {
            attr.removeModifier(SWIFT_SPEED_MODIFIER_UUID);
        }
    }

    // ==================== 维度切换/重生恢复 ====================

    /**
     * 维度切换或重生后恢复属性值。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            boolean fullyUnlocked = progress.isFullyUnlocked();
            // 与 onTick 一致：每个能力取各自 effectiveCount（指令后门解锁的能力按平移后计数恢复）
            if (progress.isAbilityEnabled(AbilityIds.EXTENDED_REACH)) {
                syncReachAttribute(player, true,
                    AbilityGate.effectiveCount(progress, AbilityIds.EXTENDED_REACH), fullyUnlocked);
            }
            if (progress.isAbilityEnabled(AbilityIds.VITALITY)) {
                syncVitalityAttribute(player, true,
                    AbilityGate.effectiveCount(progress, AbilityIds.VITALITY), fullyUnlocked);
            }
            if (progress.isAbilityEnabled(AbilityIds.SWIFT)) {
                syncSpeedAttribute(player, true,
                    AbilityGate.effectiveCount(progress, AbilityIds.SWIFT), fullyUnlocked);
            }
        });
    }

    // ==================== 登出清理 ====================

    /**
     * 登出时恢复属性为原始值（本模组写入前的值），防止残留影响且不覆盖其他模组的持久化数据。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        UUID uuid = player.getUUID();

        var reachAttr = player.getAttribute(ForgeMod.BLOCK_REACH.get());
        if (reachAttr != null) {
            // 仅当本模组实际写入过才恢复原值——从未解锁无形之手的玩家跳过，
            // 避免覆盖其他模组对触及距离 baseValue 的持久化修改
            Double restore = ORIGINAL_REACH.get(uuid);
            if (restore != null && Math.abs(reachAttr.getBaseValue() - restore) > 0.001) {
                reachAttr.setBaseValue(restore);
            }
        }
        ORIGINAL_REACH.remove(uuid);
        if (ForgeMod.ENTITY_REACH.get() != null) {
            var entityReachAttr = player.getAttribute(ForgeMod.ENTITY_REACH.get());
            if (entityReachAttr != null) {
                // 与 BLOCK_REACH 一致：仅本模组实际写入过才恢复原值，从未解锁无形之手的玩家跳过
                Double restore = ORIGINAL_ENTITY_REACH.get(uuid);
                if (restore != null && Math.abs(entityReachAttr.getBaseValue() - restore) > 0.001) {
                    entityReachAttr.setBaseValue(restore);
                }
            }
        }
        ORIGINAL_ENTITY_REACH.remove(uuid);

        var healthAttr = player.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null) {
            // 与 BLOCK_REACH 一致：仅本模组实际写入过才恢复原值——从未解锁坚韧之躯的玩家跳过，
            // 避免覆盖其他模组对 maxHealth baseValue 的持久化修改
            Double restore = ORIGINAL_MAX_HEALTH.get(uuid);
            if (restore != null && Math.abs(healthAttr.getBaseValue() - restore) > 0.001) {
                healthAttr.setBaseValue(restore);
            }
        }
        ORIGINAL_MAX_HEALTH.remove(uuid);

        var speedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(SWIFT_SPEED_MODIFIER_UUID);
        }
    }
}
