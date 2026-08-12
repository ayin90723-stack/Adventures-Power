package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.ProgressCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 容器抹除防线 · 保 tick 前提 —— {@code Entity.isRemoved()} (SRG m_213877_)
 * 对真血激活的冒险者玩家强制返回 false。
 * <p>
 * <b>为什么必须</b>：外部模组（如终极骷髅 killEntity 链）直接操作世界容器
 * （EntitySection/EntityLookup/knownUuids 直抹，绕过 {@code remove()}）并直写
 * {@code removalReason} 后，原版 {@code ServerLevel.tick -> tickNonPassenger}
 * 见 {@code isRemoved()==true} 直接跳过玩家——玩家从<b>世界 tick 流</b>脱离
 * （不移动/不结算/对其他玩家不可见），连接存活但"卡死"。
 * v1.4.0 注：{@code TrueHealthMixin} 的 tick 自检经
 * {@code ServerGamePacketListenerImpl.tick -> player.doTick() -> super.tick()} 链
 * 仍会每 tick 执行（与 ServerLevel 是否 tick 玩家无关），但玩家脱离世界 tick 流
 * 本身即不可接受；本注入让玩家保持在世界 tick 流中，行为与自检修复都正常进行。
 * <p>
 * <b>登出/换维度安全性</b>：原版容器清理走 {@code remove() -> setRemoved(reason)
 * -> levelCallback.onRemove(reason)}（f_146801_ 回调），不读取 {@code isRemoved()}
 * 返回值；登出（UNLOADED_WITH_PLAYER）/换维度（CHANGED_DIMENSION）的 remove 由
 * {@link TrueHealthMixin#onSetRemoved} 既有 reason 门禁放行，实体经 callback
 * 移出 tick 表后本注入无消费者。换维度 {@code addEntity} 前原版自行清
 * {@code removalReason}。本注入只篡改返回值，不阻止 remove() 执行、不改字段。
 * <p>
 * <b>联动</b>：注入后 {@code TrueHealthMixin} 自检①的 {@code player.isRemoved()}
 * 条件永不成立，已改为经 {@link EntityFieldsAccessor} 直读 {@code removalReason}
 * 字段（见 TrueHealthMixin 自检①）。
 * <p>
 * <b>门禁</b>：仅服务端 + 真血激活 + 备份血量 > 0 时强制 false；正常死亡
 * （backup ≤ 0，已同步为 0）放行原版判定，死亡流程正常推进。
 */
@Mixin(value = Entity.class, priority = 10000)
public abstract class EntityLivenessMixin {

    private static boolean debugLog() {
        return ModConfig.DEBUG_LOG.get() && ModConfig.DEBUG_LOG_TRUE_HEALTH.get();
    }

    private static IAdventureProgress gatedProgress(Entity self) {
        if (!(self instanceof Player player)) return null;
        if (player.level().isClientSide()) return null;
        var progress = ProgressCache.get(player);
        if (progress == null) return null;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return null;
        if (!progress.isAbilityEnabled(AbilityIds.TRUE_HEALTH)) return null;
        return progress;
    }

    @Inject(method = "m_213877_", at = @At("HEAD"), cancellable = true)
    private void onIsRemoved(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        if (progress.getBackupHealth() > 0.0F) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] isRemoved() 强制 false（容器抹除防线）" +
                    " backup=" + progress.getBackupHealth());
            }
            cir.setReturnValue(false);
        }
    }
}
