package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 经验加成·智识加冕 - 拾取经验球时经验×倍率。
 * <p>
 * 采用「额外补差额」而非 cancel+重给：在原版 PickupXp 流程（含经验修补附魔、
 * 拾取音效与粒子）之上，额外 {@code giveExperiencePoints(差额)}。
 * 这样完整保留原版逻辑，只放大经验量，无需 cancel/反射/SRG 名。
 * <p>
 * 觉醒：倍率再×倍率。
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class XpBoostHandler {

    @SubscribeEvent
    public static void onPickupXp(PlayerXpEvent.PickupXp event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        // 其他模组已取消本次拾取时不再补发经验，尊重事件取消语义
        if (event.isCanceled()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.XP_BOOST).ifPresent(progress -> {
            Ability ability = AbilityRegistry.get(AbilityIds.XP_BOOST);
            if (ability == null) return;

            float mult = ability.value(progress.getUnlockedMilestoneCount());
            if (progress.isFullyUnlocked()) {
                mult *= ModConfig.AWAKEN_XP_BOOST_MULT.get().floatValue();
            }

            // 原版流程给 base，此处补差额 base×(mult-1)，合计 base×mult
            int baseXp = event.getOrb().getValue();
            int extra = Math.round(baseXp * (mult - 1.0f));
            if (extra > 0) {
                player.giveExperiencePoints(extra);
            }
        });
    }
}
