package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.AttributeBonusUtil;
import com.ayin90723.adventure_power.util.FortuneContext;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LootingLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * 鸿运当头能力处理器。
 * <p>
 * 处理 3 种掉落/运气增强：
 * <ul>
 *   <li>抢夺 — LootingLevelEvent（Forge 内置事件，直接修改等级）</li>
 *   <li>时运 — BlockEvent.BreakEvent（设置 FortuneContext 供 Mixin 读取）</li>
 *   <li>幸运 — 每 tick 对账 Attributes.LUCK 的 ADDITION modifier（v1.4.3-fix 起，
 *       原写 baseValue 会与其他模组的幸运 base 持久化互踩；等级与时运/抢夺共用
 *       一条成长曲线，觉醒 +AWAKEN_FORTUNE_FAVOR_BONUS，
 *       可乘 FORTUNE_FAVOR_LUCK_SCALE 单独调节强度）</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FortuneFavorHandler {

    /** 幸运属性加成的固定 modifier UUID（用于挂载/更新/移除） */
    private static final UUID LUCK_MODIFIER_UUID =
        UUID.fromString("7b4a6e8f-2c9d-4a6b-9e5f-5c1d2e3f4a5b");

    /** 本模组 modifier 的注册名（属性面板展示用） */
    private static final String MODIFIER_NAME = "adventure_power_luck";

    /**
     * 抢夺：攻击者击杀生物时增加抢夺等级。在工具已有等级上叠加。
     */
    @SubscribeEvent
    public static void onLootingLevel(LootingLevelEvent event) {
        if (event.getDamageSource() == null) return;
        if (!(event.getDamageSource().getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.FORTUNE_FAVOR).ifPresent(progress -> {
            Ability ability = AbilityRegistry.get(AbilityIds.FORTUNE_FAVOR);
            if (ability == null) return;

            int bonus = (int) ability.value(AbilityGate.effectiveCount(progress, AbilityIds.FORTUNE_FAVOR));
            if (progress.isFullyUnlocked()) {
                bonus += ModConfig.AWAKEN_FORTUNE_FAVOR_BONUS.get();
            }
            event.setLootingLevel(event.getLootingLevel() + bonus);
        });
    }

    /**
     * 时运：方块破坏前将玩家引用写入 FortuneContext，供 Mixin 读取。
     * Mixin（FortuneFavorMixin）在 EnchantmentHelper 中检测上下文并叠加时运等级。
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player.level().isClientSide()) return;

        // 清理旧上下文，防止无能力的玩家继承上一位有能力的玩家的时运加成
        FortuneContext.clear();

        AbilityGate.getActiveProgress(player, AbilityIds.FORTUNE_FAVOR).ifPresent(progress -> {
            FortuneContext.setBreaker(player);
            if (progress.isFullyUnlocked()) {
                FortuneContext.setAwakened(true);
            }
        });
    }

    // ==================== 幸运属性 — Attributes.LUCK ====================

    /** 幸运属性等级：与时运/抢夺共用 value 成长曲线，觉醒 +AWAKEN_FORTUNE_FAVOR_BONUS，
     *  再乘 FORTUNE_FAVOR_LUCK_SCALE（0 = 关闭幸运加成） */
    private static double luckValue(Player player, IAdventureProgress progress) {
        Ability ability = AbilityRegistry.get(AbilityIds.FORTUNE_FAVOR);
        if (ability == null) return 0.0;
        int bonus = (int) ability.value(AbilityGate.effectiveCount(progress, AbilityIds.FORTUNE_FAVOR));
        if (progress.isFullyUnlocked()) {
            bonus += ModConfig.AWAKEN_FORTUNE_FAVOR_BONUS.get();
        }
        return bonus * ModConfig.FORTUNE_FAVOR_LUCK_SCALE.get();
    }

    /** 门禁后业务（由 PlayerTickDispatcher 调用）：鸿运当头幸运属性 modifier 同步 */
    public static void onTick(Player player, IAdventureProgress progress) {
        boolean shouldHave = progress.isAbilityEnabled(AbilityIds.FORTUNE_FAVOR)
            && ModConfig.FORTUNE_FAVOR_LUCK_SCALE.get() > 0.0;
        var attr = player.getAttribute(Attributes.LUCK);
        if (attr == null) return;

        // 启用时会写入的加成（activeBonus 供旧残留迁移用，与 enabled 无关）
        double activeBonus = luckValue(player, progress);
        // v1.4.3-fix：剥除旧版写进 base 的加成残留（防"旧 base + 新 modifier"双份），
        // 必须在挂 modifier 前执行
        AttributeBonusUtil.migrateLegacyBaseBonus(attr, activeBonus);

        AttributeBonusUtil.syncTransientModifier(attr, LUCK_MODIFIER_UUID,
            MODIFIER_NAME, shouldHave ? activeBonus : 0.0, AttributeModifier.Operation.ADDITION);
    }

    /**
     * 维度切换后恢复幸运属性加成（Player.Clone 会重置属性，modifier 随实例销毁丢失）。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.FORTUNE_FAVOR).ifPresent(progress -> {
            if (!progress.isAbilityEnabled(AbilityIds.FORTUNE_FAVOR)) return;

            var attr = player.getAttribute(Attributes.LUCK);
            if (attr == null) return;

            double luck = luckValue(player, progress);
            AttributeBonusUtil.migrateLegacyBaseBonus(attr, luck);
            AttributeBonusUtil.syncTransientModifier(attr, LUCK_MODIFIER_UUID,
                MODIFIER_NAME, luck, AttributeModifier.Operation.ADDITION);
        });
    }

    /** 玩家登出清理时运上下文 + 移除幸运属性 modifier（transient 本不落盘，移除仅为
     *  对称清理）：ThreadLocal 会残留玩家强引用（违反"登出清理 per-player 状态"规范） */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        FortuneContext.clear();

        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        var attr = player.getAttribute(Attributes.LUCK);
        if (attr != null) {
            attr.removeModifier(LUCK_MODIFIER_UUID);
        }
    }
}
