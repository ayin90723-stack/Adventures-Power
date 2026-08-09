package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.ProgressCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 不动如山 —— 位移控制防御（拉拽/传送拽回免疫）。
 * <p>
 * 拦截 {@code Entity.setPos} 对移动中玩家的「回溯拽回」：目标坐标等于玩家
 * 上 tick 位置（{@code xo/yo/zo}，由 {@code baseTick} 每 tick 开头更新）即
 * 判定为被强制拽回原位，cancel。
 * <p>
 * 覆盖场景：拉拽/钉住型位移控制——Boss 每 tick 把玩家 {@code setPos} 回原点
 * 或上 tick 位置（钉住型禁锢的典型实现，目标坐标恰好是玩家的历史位置）。
 * 而玩家正常移动不走 {@code setPos}（移动包处理走 {@code absMoveTo}），原版
 * 传送（末影珍珠/指令/维度）走 {@code moveTo}/{@code teleportTo}，均不受影响。
 * <p>
 * <b>字段语义注意</b>：判定用 {@code xo/yo/zo}（f_19854_/f_19855_/f_19856_，
 * baseTick 每 tick 更新 = 上 tick 位置），<b>不是</b> {@code xOld/yOld/zOld}
 * （f_19790_ 系列，仅 {@code setOldPosAndRot()} 写入 = 上次 moveTo/加载锚点——
 * 用它会恒判"移动中"且拦不到逐 tick 拽回）。
 * <p>
 * <b>速度清零类禁锢刻意不防</b>：原版玩家停止移动本身就是速度跳变清零
 * （客户端移动输入直接决定速度，松开按键即从全速跳 0），服务端无按键状态
 * 可区分正常停止与外部清零——拦截会破坏玩家急停。钉住型禁锢（每 tick
 * {@code setPos} 钉原位）已被本注入覆盖，玩家被禁锢时可用传送/二段跳脱离。
 * <p>
 * 门禁：仅服务端 + 玩家 + 不动如山能力启用（ProgressCache 按 tick 缓存）。
 * 判定用几何量（上 tick 位移 + 目标坐标），无需按键状态，服务端可靠。
 */
@Mixin(value = Entity.class)
public abstract class KnockbackResistMovementMixin {

    /** 视为"正在移动"的最小水平位移（格/tick）。疾跑 0.45，走路 0.21；庇护减速 0.1 不误伤 */
    private static final double MOVING_THRESHOLD = 0.05;
    /** 回溯判定的坐标差容差 */
    private static final double RESET_EPSILON = 0.001;

    @Inject(method = "m_6034_", at = @At("HEAD"), cancellable = true)
    private void onSetPos(double x, double y, double z, CallbackInfo ci) {
        if (shouldRejectSetPos((Entity) (Object) this, x, y, z)) {
            ci.cancel();
        }
    }

    @Inject(method = "m_146884_", at = @At("HEAD"), cancellable = true)
    private void onSetPosVec3(Vec3 pos, CallbackInfo ci) {
        if (shouldRejectSetPos((Entity) (Object) this, pos.x, pos.y, pos.z)) {
            ci.cancel();
        }
    }

    private static boolean shouldRejectSetPos(Entity self, double x, double y, double z) {
        if (!(self instanceof Player player)) return false;
        if (player.level().isClientSide()) return false;

        // 能力门禁（ProgressCache 按 tick 缓存）
        var progress = ProgressCache.get(player);
        if (progress == null) return false;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return false;
        if (!progress.isAbilityEnabled(AbilityIds.KNOCKBACK_RESIST)) return false;

        // 正在移动：当前位置与上 tick 位置（xo/yo/zo，baseTick 更新）有水平位移
        double moved = Math.abs(player.getX() - player.xo) + Math.abs(player.getZ() - player.zo);
        if (moved < MOVING_THRESHOLD) return false;

        // 目标坐标 = 上 tick 位置：被拽回原位
        return Math.abs(x - player.xo) < RESET_EPSILON
            && Math.abs(y - player.yo) < RESET_EPSILON
            && Math.abs(z - player.zo) < RESET_EPSILON;
    }
}
