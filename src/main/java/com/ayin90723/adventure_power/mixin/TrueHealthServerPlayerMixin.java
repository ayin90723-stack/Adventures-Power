package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.ProgressCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 真实血量 -- ServerPlayer.die 死注入点补防（v1.4.0）。
 *
 * <p><b>背景</b>：1.20.1 原版 {@code ServerPlayer.die(DamageSource)} 是<b>完整覆写</b>，
 * 方法体内从不调用 {@code super.die()}（字节码核实：仅有死亡包/广播/掉落/统计，
 * 无任何 invokespecial 到 LivingEntity.die）。因此 {@link TrueHealthMixin} 注入
 * {@code LivingEntity.die (m_6667_)} 的 onDie 对服务端玩家<b>永不触发</b>（虚拟分派
 * 直达覆写版）——玩家侧 die() 拦截实际只有事件层（LivingDeathEvent HIGH cancel）单点。
 * 本 Mixin 在覆写层补防：backup &gt; 0 时直接 cancel，阻止死亡包/掉落/死亡画面副作用，
 * 与 {@code TrueHealthMixin#onDie} 门禁完全一致。</p>
 *
 * <p>被本层拦截后 LivingDeathEvent 不会 post（die 方法体未执行），与事件层 cancel
 * 语义等价（事件层 cancel 后 ServerPlayer.die 同样直接 return），双保险无冲突。</p>
 *
 * <p>kill() 链：{@code LivingEntity.kill} 内部调 {@code die(genericKill)} → 虚拟分派
 * 同样直达本注入点；{@code TrueHealthMixin#onKill} 的 m_6074_ 注入仍在方法级补防
 * 子类覆写 kill 的场景。</p>
 *
 * @see TrueHealthMixin onDie/onKill（LivingEntity 层，玩家侧被覆写屏蔽）
 */
@Mixin(value = ServerPlayer.class, priority = 10000)
public abstract class TrueHealthServerPlayerMixin {

    /** 调试日志开关（与 TrueHealthMixin.debugLog 同配置，默认关闭） */
    private static boolean debugLog() {
        return ModConfig.DEBUG_LOG.get() && ModConfig.DEBUG_LOG_TRUE_HEALTH.get();
    }

    /**
     * 能力门禁辅助：与 {@link TrueHealthMixin#gatedProgress} 完全一致的玩家路径
     * （ProgressCache 按 tick 缓存，避免高频 resolve）。ServerPlayer 必然是服务端实例，
     * 无需再次判 isClientSide。
     */
    private static IAdventureProgress gatedProgress(ServerPlayer self) {
        var progress = ProgressCache.get(self);
        if (progress == null) return null;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return null;
        if (!progress.isAbilityEnabled(AbilityIds.TRUE_HEALTH)) return null;
        return progress;
    }

    /**
     * 拦截 {@code ServerPlayer.die(DamageSource)} (SRG {@code m_6667_}) 覆写层：
     * 备份血量 &gt; 0 → cancel 整个死亡流程（死亡包/死亡广播/掉落物/经验损失全部不发生）。
     * 备份归零（真死）时放行原版死亡流程。
     */
    @Inject(method = "m_6667_", at = @At("HEAD"), cancellable = true)
    private void onServerPlayerDie(DamageSource source, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        if (progress.getBackupHealth() > 0.0F) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 拦截 ServerPlayer.die() 覆写层！" +
                    " backup=" + progress.getBackupHealth() + " -> cancel");
            }
            ci.cancel();
        }
    }
}
