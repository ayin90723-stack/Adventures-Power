package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 不动如山能力处理器 - 管理原版击退抗性属性值。
 * <p>
 * 不动如山直接操作 {@link Attributes#KNOCKBACK_RESISTANCE} 属性，
 * 不自行拦截击退计算，与其他模组的击退修改保持兼容。
 * <p>
 * 每 tick 检查能力开关和里程碑变化，自动同步属性值。
 * 登出时重置属性为 0（防止残留影响其他世界/角色）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class KnockbackResistHandler {

    /** 首次写入前的原始 baseValue（登出恢复用，避免覆盖其他模组持久化数据） */
    private static final Map<UUID, Double> ORIGINAL_KNOCKBACK_RESIST = new HashMap<>();

    /**
     * 每 tick 检查：统一计算期望击退抗性值，仅在当前值不一致时写入。
     * 覆盖启用/禁用/里程碑变化三种情况，避免三路分支重复代码。
     */
    /** 门禁后业务（由 PlayerTickDispatcher 调用）：不动如山击退抗性属性同步 */
    public static void onTick(Player player, IAdventureProgress progress) {
        boolean shouldHave = progress.isAbilityEnabled(AbilityIds.KNOCKBACK_RESIST);
        var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr == null) return;

        double currentVal = attr.getBaseValue();
        double expected = 0.0;
        if (shouldHave) {
            Ability ability = AbilityRegistry.get(AbilityIds.KNOCKBACK_RESIST);
            if (ability != null) {
                float percent = AbilityGate.awakenedPercent(ability, progress.getUnlockedMilestoneCount(), progress.isFullyUnlocked(), ModConfig.KNOCKBACK_RESIST_HARD_CAP.get().floatValue());
                expected = percent / 100.0;
            }
        }
        if (Math.abs(currentVal - expected) > 0.001) {
            ORIGINAL_KNOCKBACK_RESIST.putIfAbsent(player.getUUID(), currentVal);
            attr.setBaseValue(expected);
        }
    }

    /**
     * 维度切换后恢复击退抗性属性（Player.Clone 会重置属性为默认值 0）。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.KNOCKBACK_RESIST).ifPresent(progress -> {
            Ability ability = AbilityRegistry.get(AbilityIds.KNOCKBACK_RESIST);
            if (ability == null) return;

            var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (attr == null) return;

            float percent = AbilityGate.awakenedPercent(ability, progress.getUnlockedMilestoneCount(), progress.isFullyUnlocked(), ModConfig.KNOCKBACK_RESIST_HARD_CAP.get().floatValue());
            attr.setBaseValue(percent / 100.0);
        });
    }

    /**
     * 登出时恢复击退抗性为原始值（本模组写入前的值），防止残留且不覆盖其他模组数据。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        UUID uuid = player.getUUID();

        var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr != null) {
            double restore = ORIGINAL_KNOCKBACK_RESIST.getOrDefault(uuid, 0.0);
            if (Math.abs(attr.getBaseValue() - restore) > 0.001) {
                attr.setBaseValue(restore);
            }
        }
        ORIGINAL_KNOCKBACK_RESIST.remove(uuid);
    }
}
