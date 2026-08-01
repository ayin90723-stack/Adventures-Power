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

    /** 夜视剩余时间低于此值（tick）时刷新。原版闪烁线为 200 tick（10 秒），
     *  400 tick 留足余量，保证剩余永远 > 200，画面永不闪烁。 */
    private static final int NIGHT_VISION_REFRESH_AT = 400;

    public static void onTick(Player player, IAdventureProgress progress) {
        if (!progress.isAbilityEnabled(AbilityIds.ALL_SEEING)) return;

        // 夜视：剩余低于安全余量时刷新（避免每 tick addEffect 的同步开销）。
        // 原版夜视剩余 < 200 tick 会进入强度摆动（画面闪烁），刷新点固定在 400 tick，
        // 只要配置时长 > 400，剩余时间就永远远离闪烁线
        int duration = ModConfig.ALL_SEEING_NIGHT_VISION_DURATION.get();
        MobEffectInstance existing = player.getEffect(MobEffects.NIGHT_VISION);
        if (existing == null || existing.getDuration() < NIGHT_VISION_REFRESH_AT) {
            // ambient=false, visible=false(无粒子), showIcon=false(无图标)
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, duration,
                0, false, false, false));
        }
    }
}
