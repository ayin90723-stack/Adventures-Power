package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 真实血量 - 事件层免死兜底。
 * <p>
 * 在 {@link LivingDeathEvent} (HIGH 优先级) 兜底：当 true_health 启用且 Capability 备份血量 > 0
 * 时取消死亡事件。
 * <p>
 * <b>与 {@link com.ayin90723.adventure_power.mixin.TrueHealthMixin} 的关系</b>：
 * Mixin 层在 {@code die()} (SRG {@code m_6667_}) HEAD 无冷却 cancel（backup>0），
 * 正常情况下 die() 方法体不执行，{@code LivingDeathEvent} 根本不会被 post，本事件层不会触发。
 * 本层仅作为 die() 的 Mixin 注入被 ASM 绕过时的兜底（如终焉秩序维系者通过 ASM 篡改方法体）。
 * <p>
 * <b>优先级 HIGH 的选择</b>：
 * <ul>
 *   <li>低于 {@link DeathDefyHandler}(HIGHEST)：让 death_defy 优先处理（冷却好时回满血+进冷却+
 *       无敌期+清负面效果），death_defy cancel 后本层不触发（receiveCanceled=false）。</li>
 *   <li>高于 {@link PlayerStateHandler} 的灵魂绑定 onPlayerDeath(LOW，觉醒时清零经验)：
 *       death_defy 冷却中时本层 backup>0 cancel，避免灵魂绑定误清零经验。</li>
 * </ul>
 * <p>
 * 门禁与 TrueHealthMixin 各注入点保持一致：
 * {@code (isAdventurer || isFullyUnlocked) && isAbilityEnabled("true_health") && backup > 0}。
 *
 * @see com.ayin90723.adventure_power.mixin.TrueHealthMixin
 * @see DeathDefyHandler
 */
@EventBusSubscriber(modid = AdventurePower.MODID, bus = Bus.FORGE)
public class TrueHealthHandler {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled("true_health")) return;
            // 备份血量 <= 0 表示玩家确实该死（合法 hurt 路径已将备份归零），不干预
            if (progress.getBackupHealth() <= 0.0F) return;

            event.setCanceled(true);
        });
    }
}
