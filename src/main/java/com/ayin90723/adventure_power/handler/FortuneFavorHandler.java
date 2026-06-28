package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.util.FortuneContext;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LootingLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 鸿运当头能力处理器。
 * <p>
 * 处理 2 种掉落增强：
 * <ul>
 *   <li>抢夺 — LootingLevelEvent（Forge 内置事件，直接修改等级）</li>
 *   <li>时运 — BlockEvent.BreakEvent（设置 FortuneContext 供 Mixin 读取）</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FortuneFavorHandler {

    /**
     * 抢夺：攻击者击杀生物时增加抢夺等级。在工具已有等级上叠加。
     */
    @SubscribeEvent
    public static void onLootingLevel(LootingLevelEvent event) {
        if (event.getDamageSource() == null) return;
        if (!(event.getDamageSource().getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("fortune_favor")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            Ability ability = AbilityRegistry.get("fortune_favor");
            if (ability == null) return;

            int bonus = (int) ability.value(progress.getUnlockedMilestoneCount());
            if (progress.isFullyUnlocked()) {
                bonus += 2;
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

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAbilityEnabled("fortune_favor")) return;
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;

            FortuneContext.setBreaker(player);
            if (progress.isFullyUnlocked()) {
                FortuneContext.setAwakened(true);
            }
        });
    }
}
