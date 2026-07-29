package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.util.AbilityGate;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
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
     */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, "digging_power").ifPresent(progress -> {
            Ability ability = AbilityRegistry.get("digging_power");
            if (ability == null) return;

            float multiplier = ability.value(progress.getUnlockedMilestoneCount());
            boolean awakened = progress.isFullyUnlocked();
            if (awakened) {
                multiplier *= com.ayin90723.adventure_power.config.ModConfig.AWAKEN_MULTIPLIER.get().floatValue();
            }

            float speed = event.getOriginalSpeed();
            // 觉醒：取消水中/空中挖掘惩罚（反向补偿原版 getDestroySpeed 的 /5）
            // 原版条件：眼在水中且无水下速掘附魔 -> /5；未着地 -> /5（飞行也受此惩罚）
            if (awakened) {
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

        // ---- 无形之手 ----
        syncReachAttribute(player, progress.isAbilityEnabled("extended_reach"),
            progress.getUnlockedMilestoneCount(), progress.isFullyUnlocked());

        // ---- 坚韧之躯 ----
        syncVitalityAttribute(player, progress.isAbilityEnabled("vitality"),
            progress.getUnlockedMilestoneCount(), progress.isFullyUnlocked());

        // ---- 加速 ----
        syncSpeedAttribute(player, progress.isAbilityEnabled("swift"),
            progress.getUnlockedMilestoneCount(), progress.isFullyUnlocked());
    }

    private static void syncReachAttribute(Player player, boolean enabled, int milestones, boolean fullyUnlocked) {
        Ability ability = AbilityRegistry.get("extended_reach");
        float bonus = 0.0F;
        if (enabled && ability != null) {
            bonus = ability.value(milestones);
            if (fullyUnlocked) {
                bonus *= com.ayin90723.adventure_power.config.ModConfig.AWAKEN_MULTIPLIER.get().floatValue();
            }
        }
        // 方块触及 + 实体触及（攻击距离）
        applyReachAttr(player, ForgeMod.BLOCK_REACH.get(), bonus);
        applyReachAttr(player, ForgeMod.ENTITY_REACH.get(), bonus);
    }

    /** 设置触及属性 baseValue = 默认 + bonus（bonus=0 时恢复默认） */
    private static void applyReachAttr(Player player,
                                       net.minecraft.world.entity.ai.attributes.Attribute attr, float bonus) {
        if (attr == null) return;
        var inst = player.getAttribute(attr);
        if (inst == null) return;
        double def = attr.getDefaultValue();
        double expected = def + bonus;
        if (Math.abs(inst.getBaseValue() - expected) > 0.001) {
            inst.setBaseValue(expected);
        }
    }

    private static void syncVitalityAttribute(Player player, boolean enabled, int milestones, boolean fullyUnlocked) {
        var attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;

        double currentVal = attr.getBaseValue();

        if (enabled) {
            Ability ability = AbilityRegistry.get("vitality");
            if (ability == null) return;
            float bonus = ability.value(milestones);
            if (fullyUnlocked) {
                bonus = (float) Math.ceil(bonus * com.ayin90723.adventure_power.config.ModConfig.AWAKEN_MULTIPLIER.get());
            }
            double expected = 20.0 + bonus;
            if (Math.abs(currentVal - expected) > 0.001) {
                attr.setBaseValue(expected);
                // 如果当前血量超过新上限，裁剪到新上限
                if (player.getHealth() > expected) {
                    player.setHealth((float) expected);
                }
            }
        } else {
            if (Math.abs(currentVal - 20.0) > 0.001) {
                // 裁剪血量到 20 以下再调低上限，防止血量卡在异常值
                if (player.getHealth() > 20.0F) {
                    player.setHealth(20.0F);
                }
                attr.setBaseValue(20.0);
            }
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
            Ability ability = AbilityRegistry.get("swift");
            if (ability != null) {
                bonus = ability.value(milestones);  // 移速加成比例（类似迅捷药水）
                if (fullyUnlocked) {
                    bonus *= com.ayin90723.adventure_power.config.ModConfig.AWAKEN_MULTIPLIER.get().floatValue();
                }
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
            int milestones = progress.getUnlockedMilestoneCount();

            boolean fullyUnlocked = progress.isFullyUnlocked();
            if (progress.isAbilityEnabled("extended_reach")) {
                syncReachAttribute(player, true, milestones, fullyUnlocked);
            }
            if (progress.isAbilityEnabled("vitality")) {
                syncVitalityAttribute(player, true, milestones, fullyUnlocked);
            }
            if (progress.isAbilityEnabled("swift")) {
                syncSpeedAttribute(player, true, milestones, fullyUnlocked);
            }
        });
    }

    // ==================== 登出清理 ====================

    /**
     * 登出时重置属性为默认值，防止残留影响。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        var reachAttr = player.getAttribute(ForgeMod.BLOCK_REACH.get());
        if (reachAttr != null && Math.abs(reachAttr.getBaseValue() - ForgeMod.BLOCK_REACH.get().getDefaultValue()) > 0.001) {
            reachAttr.setBaseValue(ForgeMod.BLOCK_REACH.get().getDefaultValue());
        }
        if (ForgeMod.ENTITY_REACH.get() != null) {
            var entityReachAttr = player.getAttribute(ForgeMod.ENTITY_REACH.get());
            double erDef = ForgeMod.ENTITY_REACH.get().getDefaultValue();
            if (entityReachAttr != null && Math.abs(entityReachAttr.getBaseValue() - erDef) > 0.001) {
                entityReachAttr.setBaseValue(erDef);
            }
        }

        var healthAttr = player.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null && Math.abs(healthAttr.getBaseValue() - 20.0) > 0.001) {
            healthAttr.setBaseValue(20.0);
        }

        var speedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(SWIFT_SPEED_MODIFIER_UUID);
        }
    }
}
