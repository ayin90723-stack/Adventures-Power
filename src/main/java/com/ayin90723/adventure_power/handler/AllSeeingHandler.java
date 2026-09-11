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

    /** 原版夜视闪烁线（tick）：剩余低于此值进入强度摆动（画面闪烁） */
    private static final int VANILLA_FLICKER_LINE = 200;

    /** 配置时长 ≤ 闪烁线时的一次性告警（v1.4.0；v1.4.9.5 记录上次告警值——
     *  运行期改配置后再改短仍能再次告警，静态布尔置位不复位的旧问题） */
    private static int lastWarnedDuration = -1;

    public static void onTick(Player player, IAdventureProgress progress) {
        if (!progress.isAbilityEnabled(AbilityIds.ALL_SEEING)) return;

        // 夜视：剩余低于安全余量时刷新（避免每 tick addEffect 的同步开销）。
        // 原版夜视剩余 < 200 tick 会进入强度摆动（画面闪烁）。
        // v1.4.0：刷新阈值随配置自适应——原固定 400 tick 在配置时长 < 400 时
        // 剩余永远低于阈值导致每 tick 刷新（addEffect 每 tick 同步）。
        // v1.4.9.5 公式修正：refreshAt = min(400, max(200, duration-200))——旧公式
        // max(200, duration-1) 在 duration∈[202,400] 时 refreshAt=duration-1，刷新后
        // 2 tick 即再满足条件（每 2 tick 重发效果包/约 10 包每秒）。新公式下刷新
        // 间隔 = duration-200（≥2），剩余永远 ≥ 200 不闪烁；duration ≤ 200 无法
        // 避免闪烁，启动一次性告警
        int duration = ModConfig.ALL_SEEING_NIGHT_VISION_DURATION.get();
        if (duration <= VANILLA_FLICKER_LINE && lastWarnedDuration != duration) {
            lastWarnedDuration = duration;
            LOGGER.warn(
                "[AllSeeingHandler] ALL_SEEING_NIGHT_VISION_DURATION 配置 {} tick ≤ {}，"
                    + "低于原版闪烁线，夜视画面会闪烁——建议配置 > {}",
                duration, VANILLA_FLICKER_LINE, VANILLA_FLICKER_LINE);
        }
        int refreshAt = Math.min(NIGHT_VISION_REFRESH_AT,
            Math.max(VANILLA_FLICKER_LINE, duration - VANILLA_FLICKER_LINE));
        MobEffectInstance existing = player.getEffect(MobEffects.NIGHT_VISION);
        if (existing == null || existing.getDuration() < refreshAt) {
            // ambient=false, visible=false(无粒子), showIcon=false(无图标)
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, duration,
                0, false, false, false));
        }
    }

    /**
     * 维度切换后强制重发夜视效果（v1.4.0 修复夜视同步慢）。
     * <p>
     * <b>根因（字节码实锤）</b>：两条维度切换路径对效果的同步不对称——传送门
     * {@code ServerPlayer.changeDimension} 发 RespawnPacket 后<b>重发全部活跃效果包</b>
     * （方法体内 getActiveEffects + ClientboundUpdateMobEffectPacket 循环）；而末地出口
     * {@code ServerPlayer.teleportTo}（六参，跨维度 tp 同路径）只发 RespawnPacket
     * <b>不重发效果</b>。客户端 handleRespawn 重建 LocalPlayer（效果表为空）后，
     * 服务端夜视实例还活着、剩余时长未低于刷新阈值（400 tick）→ onTick 的刷新条件
     * 不满足、不补发 → 客户端夜视丢失最长 2400-400=2000 tick（约 100 秒）。
     * 与翱翔 v1.4.0 修复（teleportTo 不重发 abilities）同族。
     * <p>
     * 本方法通过 addEffect 替换实例触发 {@code ClientboundUpdateMobEffectPacket} 重发，
     * 挂 {@code PlayerChangedDimensionEvent}（两条路径均 fire，已验证），同 tick 恢复。
     */
    public static void resendNightVision(Player player) {
        int duration = ModConfig.ALL_SEEING_NIGHT_VISION_DURATION.get();
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, duration,
            0, false, false, false));
    }
}
