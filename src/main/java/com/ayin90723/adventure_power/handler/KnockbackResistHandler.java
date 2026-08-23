package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.AttributeBonusUtil;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * 不动如山能力处理器 - 管理原版击退抗性属性的加成。
 * <p>
 * 不动如山通过固定 UUID 的 ADDITION modifier 附加击退抗性（v1.4.3-fix 起，
 * 原直接写 baseValue 会与其他模组的 base 持久化互踩），
 * 不自行拦截击退计算，与其他模组的击退修改保持兼容。
 * <p>
 * 每 tick 检查能力开关和里程碑变化，自动对账 modifier 值。
 * modifier 为 transient：登出随实体销毁消失，不留残档。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class KnockbackResistHandler {

    /** 击退抗性加成的固定 modifier UUID（用于挂载/更新/移除） */
    private static final UUID KNOCKBACK_RESIST_MODIFIER_UUID =
        UUID.fromString("3d9c0f2a-8b4e-4c2f-9a5d-1e7b2c3d4e5f");

    /** 本模组 modifier 的注册名（属性面板展示用） */
    private static final String MODIFIER_NAME = "adventure_power_knockback_resist";

    /**
     * 每 tick 检查：统一计算期望击退抗性加成，对账 modifier。
     * 覆盖启用/禁用/里程碑变化三种情况，避免三路分支重复代码。
     */
    /** 门禁后业务（由 PlayerTickDispatcher 调用）：不动如山击退抗性 modifier 同步 */
    public static void onTick(Player player, IAdventureProgress progress) {
        boolean shouldHave = progress.isAbilityEnabled(AbilityIds.KNOCKBACK_RESIST);
        var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr == null) return;

        // 启用时会写入的加成（activeBonus 供旧残留迁移用，与 enabled 无关）
        double activeBonus = 0.0;
        Ability ability = AbilityRegistry.get(AbilityIds.KNOCKBACK_RESIST);
        if (ability != null) {
            float percent = AbilityGate.awakenedPercent(ability,
                AbilityGate.effectiveCount(progress, AbilityIds.KNOCKBACK_RESIST),
                progress.isFullyUnlocked(), ModConfig.KNOCKBACK_RESIST_HARD_CAP.get().floatValue());
            activeBonus = percent / 100.0;
        }
        // v1.4.3-fix：剥除旧版写进 base 的加成残留（防"旧 base + 新 modifier"双份），
        // 必须在挂 modifier 前执行
        AttributeBonusUtil.migrateLegacyBaseBonus(attr, activeBonus);

        AttributeBonusUtil.syncTransientModifier(attr, KNOCKBACK_RESIST_MODIFIER_UUID,
            MODIFIER_NAME, shouldHave ? activeBonus : 0.0, AttributeModifier.Operation.ADDITION);
    }

    /**
     * 维度切换后恢复击退抗性加成（Player.Clone 会重置属性，modifier 随实例销毁丢失）。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.KNOCKBACK_RESIST).ifPresent(progress -> {
            if (!progress.isAbilityEnabled(AbilityIds.KNOCKBACK_RESIST)) return;

            Ability ability = AbilityRegistry.get(AbilityIds.KNOCKBACK_RESIST);
            if (ability == null) return;

            var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (attr == null) return;

            double bonus = AbilityGate.awakenedPercent(ability,
                AbilityGate.effectiveCount(progress, AbilityIds.KNOCKBACK_RESIST),
                progress.isFullyUnlocked(), ModConfig.KNOCKBACK_RESIST_HARD_CAP.get().floatValue()) / 100.0;
            AttributeBonusUtil.migrateLegacyBaseBonus(attr, bonus);
            AttributeBonusUtil.syncTransientModifier(attr, KNOCKBACK_RESIST_MODIFIER_UUID,
                MODIFIER_NAME, bonus, AttributeModifier.Operation.ADDITION);
        });
    }

    /**
     * 登出时移除击退抗性 modifier（transient 本不落盘，移除仅为对称清理，
     * 与加速/触及等其他能力登出处理一致），不残留影响其他世界/角色。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        var attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr != null) {
            attr.removeModifier(KNOCKBACK_RESIST_MODIFIER_UUID);
        }
    }
}
