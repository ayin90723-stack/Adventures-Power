package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.FortuneContext;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LootingLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 鸿运当头能力处理器。
 * <p>
 * 处理 3 种掉落/运气增强：
 * <ul>
 *   <li>抢夺 — LootingLevelEvent（Forge 内置事件，直接修改等级）</li>
 *   <li>时运 — BlockEvent.BreakEvent（设置 FortuneContext 供 Mixin 读取）</li>
 *   <li>幸运 — 每 tick 对账写 Attributes.LUCK 属性基值（钓鱼宝藏/战利品随机向正面偏移，
 *       等级与时运/抢夺共用一条成长曲线，觉醒 +AWAKEN_FORTUNE_FAVOR_BONUS，
 *       可乘 FORTUNE_FAVOR_LUCK_SCALE 单独调节强度）</li>
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

    /** 首次写入前的原始 baseValue（登出恢复用，避免覆盖其他模组持久化数据） */
    private static final Map<UUID, Double> ORIGINAL_LUCK = new HashMap<>();

    /** 本模组最后一次写入的 baseValue（关闭能力时残留判定用——仅当当前值仍等于本模组
     *  写入值时才归零/恢复，避免把其他模组中途改的值误清零） */
    private static final Map<UUID, Double> LAST_WRITTEN_LUCK = new HashMap<>();

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

    /** 门禁后业务（由 PlayerTickDispatcher 调用）：鸿运当头幸运属性同步 */
    public static void onTick(Player player, IAdventureProgress progress) {
        boolean shouldHave = progress.isAbilityEnabled(AbilityIds.FORTUNE_FAVOR)
            && ModConfig.FORTUNE_FAVOR_LUCK_SCALE.get() > 0.0;
        var attr = player.getAttribute(Attributes.LUCK);
        if (attr == null) return;
        UUID uuid = player.getUUID();

        double currentVal = attr.getBaseValue();
        // 本模组启用时会写入的期望值（残留判定用：关闭分支无记录时判断当前值是否为本模组残留）
        double own = luckValue(player, progress);
        double expected = shouldHave ? own : 0.0;
        if (Math.abs(currentVal - expected) > 0.001) {
            if (shouldHave) {
                ORIGINAL_LUCK.putIfAbsent(uuid, currentVal);
                LAST_WRITTEN_LUCK.put(uuid, expected);
                attr.setBaseValue(expected);
            } else {
                // 关闭分支：无记录（从未启用，或服务端重启后静态 Map 清空、player.dat 残留本模组
                // 写入值）时做残留判定——当前值 ≈ 本模组启用时会写的值则判定为本模组残留，
                // 恢复默认 0；否则视为其他模组修改，不记录不操作（与不动如山/坚韧之躯同模式）
                Double original = ORIGINAL_LUCK.get(uuid);
                if (original == null) {
                    if (Math.abs(currentVal - own) > 0.001) return;
                    original = 0.0;
                    ORIGINAL_LUCK.put(uuid, original);
                }
                double lastWritten = LAST_WRITTEN_LUCK.getOrDefault(uuid, own);
                if (Math.abs(currentVal - lastWritten) <= 0.001) {
                    if (Math.abs(currentVal - original) > 0.001) {
                        attr.setBaseValue(original);
                    }
                    ORIGINAL_LUCK.remove(uuid);
                    LAST_WRITTEN_LUCK.remove(uuid);
                }
            }
        }
    }

    /**
     * 维度切换后恢复幸运属性（Player.Clone 会重置属性为默认值 0）。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.FORTUNE_FAVOR).ifPresent(progress -> {
            if (!progress.isAbilityEnabled(AbilityIds.FORTUNE_FAVOR)) return;

            var attr = player.getAttribute(Attributes.LUCK);
            if (attr == null) return;

            // 写入前 putIfAbsent 记录原值（Clone 重置后 baseValue=0，记录 0 兜底）：
            // 与 onTick 启用分支保持一致，保证关闭能力时总能恢复到记录值
            ORIGINAL_LUCK.putIfAbsent(player.getUUID(), attr.getBaseValue());

            double luck = luckValue(player, progress);
            if (luck > 0.0) {
                attr.setBaseValue(luck);
                // 与 onTick 启用分支对称：同步 LAST_WRITTEN_LUCK——
                // 否则"重启后 player.dat 残留值 → onTick 空转（不写 Map）→ Clone 写入"的
                // 窄路径下 Logout 的残留判定取 ORIGINAL（0.0）而非 own，模组值残留进存档
                LAST_WRITTEN_LUCK.put(player.getUUID(), luck);
            }
        });
    }

    /** 玩家登出清理时运上下文 + 恢复幸运属性原值：ThreadLocal 会残留玩家强引用（违反
     *  "登出清理 per-player 状态"规范）；幸运属性不恢复会残留影响其他世界/角色 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        FortuneContext.clear();

        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        UUID uuid = player.getUUID();

        if (!ORIGINAL_LUCK.containsKey(uuid)) return;
        var attr = player.getAttribute(Attributes.LUCK);
        if (attr != null) {
            // 与关闭分支同款残留判定：仅当当前值仍等于本模组最后写入的值时才恢复原值——
            // 若已被其他模组中途改动（≠ 本模组写入值），尊重其他模组，不覆盖
            double lastWritten = LAST_WRITTEN_LUCK.getOrDefault(uuid, ORIGINAL_LUCK.get(uuid));
            if (Math.abs(attr.getBaseValue() - lastWritten) <= 0.001) {
                double restore = ORIGINAL_LUCK.get(uuid);
                if (Math.abs(attr.getBaseValue() - restore) > 0.001) {
                    attr.setBaseValue(restore);
                }
            }
        }
        ORIGINAL_LUCK.remove(uuid);
        LAST_WRITTEN_LUCK.remove(uuid);
    }
}
