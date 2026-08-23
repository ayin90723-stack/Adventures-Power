package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.AttributeBonusUtil;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 探索/采集类能力效果处理器。
 * <p>
 * 处理 3 种非战斗能力的实际效果：
 * <ul>
 *   <li>大地之力 (digging_power) — BreakSpeed 事件提升挖掘速度</li>
 *   <li>无形之手 (extended_reach) — BLOCK_REACH / ENTITY_REACH 属性加成</li>
 *   <li>坚韧之躯 (vitality) — MAX_HEALTH 属性加成</li>
 * </ul>
 * <p>
 * v1.4.3-fix 起属性加成全部走固定 UUID 的 transient modifier
 * （与加速 SWIFT 同模式），不再写 baseValue——base 是独占资源，其他模组
 * （体质/耐力/触及/耐力类）以 baseValue 持久化同名属性时双方每 tick 互踩，
 * modifier 是叠加资源，任意模组共存互不干扰。
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
     * 每 tick 同步无形之手（BLOCK_REACH / ENTITY_REACH）、坚韧之躯（MAX_HEALTH）
     * 和加速（MOVEMENT_SPEED）属性加成。仅在加成变化时写入 modifier。
     */
    /** 门禁后业务（由 PlayerTickDispatcher 调用）：无形之手 / 坚韧之躯属性同步 */
    public static void onTick(Player player, IAdventureProgress progress) {
        // 全能力关闭时快速短路，避免每 tick 三次 AttributeRegistry.get + getAttribute
        if (!progress.isAbilityEnabled(AbilityIds.EXTENDED_REACH)
            && !progress.isAbilityEnabled(AbilityIds.VITALITY)
            && !progress.isAbilityEnabled(AbilityIds.SWIFT)) {
            // 但关闭的触及属性仍需补一次移除（"关闭即移除"语义，v1.3.1 起；
            // modifier 模式下 removeModifier 为 no-op 开销，无需 Map 记录判断）。
            syncReachAttribute(player, false,
                AbilityGate.effectiveCount(progress, AbilityIds.EXTENDED_REACH),
                progress.isFullyUnlocked());
            // 三能力全关时 maxHealth/速度加成同样需要移除（transient 登出本不残留，
            // 但能力关闭期间不摘除会在属性面板持续显示本模组加成）。
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
        // activeBonus = 能力开启时会写入的加成（与 enabled 无关，供旧残留迁移用）
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
     * 触及属性 ADDITION modifier 同步（v1.4.3-fix 起，替代原 baseValue 覆盖写）：
     * bonus &gt; 0 时挂 ADDITION 加成（默认 base + 加成，最终值 = 任意模组 base + 本模组加成）；
     * bonus = 0（关闭/未解锁）时按固定 UUID 移除。挂载前先做旧残留迁移
     * （v1.4.3 及之前写进 base 的加成剥除，防双份叠加）。
     */
    private static void applyReachAttr(Player player,
                                       net.minecraft.world.entity.ai.attributes.Attribute attr,
                                       float targetBonus, float activeBonus) {
        if (attr == null) return;
        var inst = player.getAttribute(attr);
        if (inst == null) return;
        boolean block = attr == ForgeMod.BLOCK_REACH.get();

        AttributeBonusUtil.migrateLegacyBaseBonus(inst, activeBonus);
        AttributeBonusUtil.syncTransientModifier(inst,
            block ? REACH_BLOCK_MODIFIER_UUID : REACH_ENTITY_MODIFIER_UUID,
            block ? "adventure_power_block_reach" : "adventure_power_entity_reach",
            targetBonus, AttributeModifier.Operation.ADDITION);
    }

    private static void syncVitalityAttribute(Player player, boolean enabled, int milestones, boolean fullyUnlocked) {
        var attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        Ability ability = AbilityRegistry.get(AbilityIds.VITALITY);
        if (ability == null) return;

        // activeBonus = 启用时会写入的加成（与 enabled 无关，供旧残留迁移用）
        // 坚韧之躯觉醒 ×1.5 向上取整（仅觉醒取整，未觉醒保持原值——避免小数配置下未觉醒 +1）
        float activeBonus = AbilityGate.awakenedPercent(ability, milestones, fullyUnlocked, Float.MAX_VALUE);
        if (fullyUnlocked) {
            activeBonus = (float) Math.ceil(activeBonus);
        }

        // 旧残留迁移必须在挂 modifier 前执行（先剥 base 残留，modifier 再加成）
        AttributeBonusUtil.migrateLegacyBaseBonus(attr, activeBonus);
        AttributeBonusUtil.syncTransientModifier(attr, VITALITY_HEALTH_MODIFIER_UUID,
            "adventure_power_vitality", enabled ? activeBonus : 0.0,
            AttributeModifier.Operation.ADDITION);

        // 若当前血量超过新上限，裁剪到新上限（modifier 移除/降级、或迁移剥 base 均可能降上限）。
        // 无条件检查：clamp 后 health ≤ max 不再触发，开销为两次读数；防"降上限后
        // 血量卡在异常值"（v1.4.3 及之前的"值变化才裁"不覆盖迁移路径）。
        if (player.getHealth() > attr.getValue()) {
            clampHealthTo(player, (float) attr.getValue());
        }
    }

    /**
     * 模组内部血量裁剪（降上限时把血量裁到新上限）。
     * 包 {@link HealthUtil#INTERNAL_HEALTH_WRITE} 标记：放行 RejectHealthManipMixin 的
     * reject_manip 拦截（否则"降血被拒 + 上限已降"造成 health > maxHealth 卡死），
     * 同时让 TrueHealthMixin 同步备份（否则 backup 冻结旧值、getHealth 会把裁剪反向修复）。
     */
    private static void clampHealthTo(Player player, float target) {
        boolean prevInternal = HealthUtil.INTERNAL_HEALTH_WRITE.get();
        HealthUtil.INTERNAL_HEALTH_WRITE.set(true);
        try {
            player.setHealth(target);
        } finally {
            // v1.4.0：恢复 prev 而非 remove()——防未来嵌套在其它 INTERNAL 窗口内调用时清掉外层标记
            HealthUtil.INTERNAL_HEALTH_WRITE.set(prevInternal);
        }
    }

    /** 加速·移速加成的 modifier UUID（固定，用于移除/更新） */
    private static final java.util.UUID SWIFT_SPEED_MODIFIER_UUID =
        java.util.UUID.fromString("c4f2a3b1-7d8e-4a6b-9c0d-1e2f3a4b5c6d");

    /** 无形之手触及加成的固定 modifier UUID（方块 / 实体分别） */
    private static final java.util.UUID REACH_BLOCK_MODIFIER_UUID =
        java.util.UUID.fromString("4e1d3b5c-9f6a-4d3e-8b2c-2f8a9b0c1d2e");
    private static final java.util.UUID REACH_ENTITY_MODIFIER_UUID =
        java.util.UUID.fromString("5f2e4c6d-0a7b-4e4f-9c3d-3a9b0c1d2e3f");

    /** 坚韧之躯 maxHealth 加成的固定 modifier UUID */
    private static final java.util.UUID VITALITY_HEALTH_MODIFIER_UUID =
        java.util.UUID.fromString("6a3f5d7e-1b8c-4f5a-8d4e-4b0c1d2e3f4a");

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

    // ==================== 坚韧之躯 — 治疗量加成 ====================

    /**
     * 坚韧之躯：收到的治疗量加成。
     * <p>
     * 只加成外部治疗（LivingHealEvent 链路：药水/再生效果/其他模组 heal()）；
     * 自家直写回血（休养生息/嗜血 setAllHealthLikeRaw）不走 heal()，不受加成，
     * 避免模组内部回血互相膨胀。成长曲线独立于 maxHealth 加成：
     * base + per_milestone × (effectiveCount - countAtUnlock)，觉醒 ×倍率。
     */
    @SubscribeEvent
    public static void onLivingHeal(LivingHealEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        float amount = event.getAmount();
        if (amount <= 0.0F) return;

        AbilityGate.getActiveProgress(player, AbilityIds.VITALITY).ifPresent(progress -> {
            Ability ability = AbilityRegistry.get(AbilityIds.VITALITY);
            if (ability == null) return;

            // 独立成长曲线：base + per_milestone × (effectiveCount - countAtUnlock)
            //（Math.max(0, ...) 下限保护与 LinearGrowthAbility 语义对齐——/reload 删里程碑
            //  等场景差值可为负时保底 base 而非整体失效）
            double bonus = ModConfig.VITALITY_HEAL_BONUS_BASE.get()
                + ModConfig.VITALITY_HEAL_BONUS_PER_MILESTONE.get()
                * Math.max(0, AbilityGate.effectiveCount(progress, AbilityIds.VITALITY) - ability.getCountAtUnlock());
            if (bonus <= 0.0) return;
            if (progress.isFullyUnlocked()) {
                bonus *= ModConfig.AWAKEN_VITALITY_HEAL_MULTIPLIER.get();
            }
            event.setAmount(amount * (1.0F + (float) bonus));
        });
    }

    // ==================== 维度切换/重生恢复 ====================

    /**
     * 维度切换或重生后恢复属性加成（Clone 重置属性，modifier 随实例销毁丢失）。
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
     * 登出时移除本模组属性加成 modifier（transient 本不落盘，移除仅为对称清理），
     * 不残留影响其他世界/角色。base 自始至终未被本模组写入，无需恢复。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        if (ForgeMod.BLOCK_REACH.get() != null) {
            var reachAttr = player.getAttribute(ForgeMod.BLOCK_REACH.get());
            if (reachAttr != null) {
                reachAttr.removeModifier(REACH_BLOCK_MODIFIER_UUID);
            }
        }
        if (ForgeMod.ENTITY_REACH.get() != null) {
            var entityReachAttr = player.getAttribute(ForgeMod.ENTITY_REACH.get());
            if (entityReachAttr != null) {
                entityReachAttr.removeModifier(REACH_ENTITY_MODIFIER_UUID);
            }
        }

        var healthAttr = player.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null) {
            healthAttr.removeModifier(VITALITY_HEALTH_MODIFIER_UUID);
        }

        var speedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(SWIFT_SPEED_MODIFIER_UUID);
        }
    }
}
