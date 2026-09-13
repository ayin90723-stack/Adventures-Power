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
        // 审查修（遗漏补，与 resendNightVision 同一根因）：必须排除"无限时长"现存实例。
        // 原条件只看 `existing.getDuration() < refreshAt`，而外部模组给的**无限时长**夜视
        // duration == -1（MobEffectInstance.INFINITE_DURATION）恒满足该不等式 → 每 tick 都调
        // addEffect；而 addEffect 对已存在实例只走 update()（判据 isShorterDurationThan 对
        // 无限时长恒 false）→ 恒返回 false、不发包也不更新 → 每 tick 一次纯浪费的对象分配
        // 与效果表查表（长期挂机场景持续）。无限时长本身无需刷新（客户端若在维度切换后丢包，
        // 由 resendNightVision 的直发包路径修复，那条路径不受本条件影响）。
        // 该守卫与同仓"恩赐永驻"续期（PlayerTickHandler）的既有惯例一致
        // （那里同样是 `effect.getDuration() < 0 → continue`，注释理由即"避免被降级为有限时长
        // 反复重建"）——本处属漏跟该惯例
        if (existing == null
            || (existing.getDuration() >= 0 && existing.getDuration() < refreshAt)) {
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
     * 本方法直接发 {@code ClientboundUpdateMobEffectPacket} 重发实例（与 changeDimension
     * 的同步手段一致），挂 {@code PlayerChangedDimensionEvent}（两条路径均 fire，已验证），
     * 同 tick 恢复。
     * <p>
     * <b>审查修 P2（原"借 addEffect 替换实例触发发包"不可靠）</b>：字节码实证
     * {@code LivingEntity.addEffect} 在已存在同效果实例时只调
     * {@code MobEffectInstance.update}，其返回 false 则 addEffect 直接返回 false、
     * <b>不调 onEffectUpdated（=不发包）</b>；而 update 的时长判据是
     * {@code isShorterDurationThan}（{@code !this.isInfinite() && (this.duration < other.duration
     * || other.isInfinite())}）——现存剩余时长 ≥ 配置时长时不成立，外部模组给的
     * <b>无限时长夜视（duration=-1）更是该判据恒 false</b>。此时客户端 LocalPlayer 重建后
     * 夜视永久丢失——旧实现依赖 onTick 的刷新条件来补发，而该条件对无限时长恒真、只会每 tick
     * 空转重试 addEffect 且永远发不出去（onTick 侧已另行补 `duration >= 0` 守卫止血；
     * 重发本身则改由本方法直发，不依赖任何更新语义）。
     */
    public static void resendNightVision(Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        int duration = ModConfig.ALL_SEEING_NIGHT_VISION_DURATION.get();
        MobEffectInstance existing = player.getEffect(MobEffects.NIGHT_VISION);
        if (existing == null) {
            // 服务端本无夜视（能力刚开启/已被清）：正常添加——新建实例路径必然发包
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, duration,
                0, false, false, false));
            return;
        }
        sp.connection.send(new net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket(
            sp.getId(), existing));
    }
}
