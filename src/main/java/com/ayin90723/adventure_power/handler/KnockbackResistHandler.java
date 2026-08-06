package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
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

    /** 本模组最后一次写入的 baseValue（关闭能力时残留判定用——仅当当前值仍等于本模组
     *  写入值时才归零/恢复，避免把其他模组中途改的值误清零） */
    private static final Map<UUID, Double> LAST_WRITTEN = new HashMap<>();

    /**
     * 每 tick 检查：统一计算期望击退抗性值，仅在当前值不一致时写入。
     * 覆盖启用/禁用/里程碑变化三种情况，避免三路分支重复代码。
     */
    /** 门禁后业务（由 PlayerTickDispatcher 调用）：不动如山击退抗性属性同步 */
    public static void onTick(Player player, IAdventureProgress progress) {
        boolean shouldHave = progress.isAbilityEnabled(AbilityIds.KNOCKBACK_RESIST);
        var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr == null) return;
        UUID uuid = player.getUUID();

        double currentVal = attr.getBaseValue();
        // 本模组启用时会写入的期望值（残留判定用：关闭分支无记录时判断当前值是否为本模组残留）
        double own = 0.0;
        Ability ability = AbilityRegistry.get(AbilityIds.KNOCKBACK_RESIST);
        if (ability != null) {
            float percent = AbilityGate.awakenedPercent(ability,
                AbilityGate.effectiveCount(progress, AbilityIds.KNOCKBACK_RESIST),
                progress.isFullyUnlocked(), ModConfig.KNOCKBACK_RESIST_HARD_CAP.get().floatValue());
            own = percent / 100.0;
        }
        double expected = shouldHave ? own : 0.0;
        if (Math.abs(currentVal - expected) > 0.001) {
            if (shouldHave) {
                ORIGINAL_KNOCKBACK_RESIST.putIfAbsent(uuid, currentVal);
                LAST_WRITTEN.put(uuid, expected);
                attr.setBaseValue(expected);
            } else {
                // 关闭分支：无记录（从未启用，或服务端重启后静态 Map 清空、player.dat 残留本模组
                // 写入值）时做残留判定——当前值 ≈ 本模组启用时会写的值则判定为本模组残留，
                // 恢复默认 0；否则视为其他模组修改，不记录不操作（与 ExplorationAbilityHandler
                // 对 maxHealth 的残留判定同模式）
                Double original = ORIGINAL_KNOCKBACK_RESIST.get(uuid);
                if (original == null) {
                    if (Math.abs(currentVal - own) > 0.001) return;
                    original = 0.0;
                    ORIGINAL_KNOCKBACK_RESIST.put(uuid, original);
                }
                double lastWritten = LAST_WRITTEN.getOrDefault(uuid, own);
                if (Math.abs(currentVal - lastWritten) <= 0.001) {
                    if (Math.abs(currentVal - original) > 0.001) {
                        attr.setBaseValue(original);
                    }
                    ORIGINAL_KNOCKBACK_RESIST.remove(uuid);
                    LAST_WRITTEN.remove(uuid);
                }
            }
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

            // 写入前 putIfAbsent 记录原值（Clone 重置后 baseValue=0，记录 0 兜底）：
            // 与 onTick 启用分支保持一致，保证关闭能力时总能恢复到记录值
            ORIGINAL_KNOCKBACK_RESIST.putIfAbsent(player.getUUID(), attr.getBaseValue());

            float percent = AbilityGate.awakenedPercent(ability,
                AbilityGate.effectiveCount(progress, AbilityIds.KNOCKBACK_RESIST),
                progress.isFullyUnlocked(), ModConfig.KNOCKBACK_RESIST_HARD_CAP.get().floatValue());
            attr.setBaseValue(percent / 100.0);
        });
    }

    /**
     * 登出时恢复击退抗性为原始值（本模组写入前的值），防止残留且不覆盖其他模组数据。
     * 仅恢复本模组记录过原值的玩家——从未被本模组写过的玩家（非冒险者）不受影响，
     * 避免把其他模组设的非零 base 误回写成 0。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        UUID uuid = player.getUUID();

        if (!ORIGINAL_KNOCKBACK_RESIST.containsKey(uuid)) {
            return;
        }
        var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr != null) {
            // 与关闭分支同款残留判定：仅当当前值仍等于本模组最后写入的值时才恢复原值——
            // 若已被其他模组中途改动（≠ 本模组写入值），尊重其他模组，不覆盖
            double lastWritten = LAST_WRITTEN.getOrDefault(uuid, ORIGINAL_KNOCKBACK_RESIST.get(uuid));
            if (Math.abs(attr.getBaseValue() - lastWritten) <= 0.001) {
                double restore = ORIGINAL_KNOCKBACK_RESIST.get(uuid);
                if (Math.abs(attr.getBaseValue() - restore) > 0.001) {
                    attr.setBaseValue(restore);
                }
            }
        }
        ORIGINAL_KNOCKBACK_RESIST.remove(uuid);
        LAST_WRITTEN.remove(uuid);
    }
}
