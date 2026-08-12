package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

/**
 * 全视之眼 - 夜视 + 去雾（下界/末地/水下）。
 * <p>
 * 由 {@link PlayerTickDispatcher} 分发调用（已统一门禁）。服务端 tick 循环刷新
 * 夜视（无图标无粒子，余量刷新避免每 tick 同步）；客户端去雾由
 * {@link AllSeeingClientHandler} + {@link com.ayin90723.adventure_power.ui.ClientHudDataCache#allSeeingEnabled} 处理。
 * <p>
 * 觉醒：威胁雷达（右下角列表）由客户端 {@link com.ayin90723.adventure_power.ui.AllSeeingRadarOverlay}
 * + {@link com.ayin90723.adventure_power.ui.ClientHudDataCache#radarTargets} 渲染，服务端无额外逻辑。
 */
public class AllSeeingHandler {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 夜视剩余时间低于此值（tick）时刷新。原版闪烁线为 200 tick（10 秒），
     *  默认 400 tick 留足余量，保证剩余永远 > 200，画面永不闪烁。 */
    private static final int NIGHT_VISION_REFRESH_AT = 400;

    /** 配置时长 ≤ 200 tick 时的一次性告警（v1.4.0） */
    private static boolean warnedShortDuration = false;

    public static void onTick(Player player, IAdventureProgress progress) {
        if (!progress.isAbilityEnabled(AbilityIds.ALL_SEEING)) return;

        // 夜视：剩余低于安全余量时刷新（避免每 tick addEffect 的同步开销）。
        // 原版夜视剩余 < 200 tick 会进入强度摆动（画面闪烁）。
        // v1.4.0：刷新阈值随配置自适应——原固定 400 tick 在配置时长 < 400 时
        // 剩余永远低于阈值导致每 tick 刷新（addEffect 每 tick 同步）。
        // 取 min(400, duration-1)（下限 200）：duration>200 时刷新间隔 = duration-200，
        // 剩余永远 ≥ 200 不闪烁；duration ≤ 200 无法避免闪烁，启动一次性告警
        int duration = ModConfig.ALL_SEEING_NIGHT_VISION_DURATION.get();
        if (duration <= 200 && !warnedShortDuration) {
            warnedShortDuration = true;
            LOGGER.warn(
                "[AllSeeingHandler] ALL_SEEING_NIGHT_VISION_DURATION 配置 {} tick ≤ 200，"
                    + "低于原版闪烁线，夜视画面会闪烁——建议配置 > 200", duration);
        }
        int refreshAt = Math.min(NIGHT_VISION_REFRESH_AT, Math.max(200, duration - 1));
        MobEffectInstance existing = player.getEffect(MobEffects.NIGHT_VISION);
        if (existing == null || existing.getDuration() < refreshAt) {
            // ambient=false, visible=false(无粒子), showIcon=false(无图标)
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, duration,
                0, false, false, false));
        }
    }
}
