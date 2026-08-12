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

import java.util.Set;

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

        // 目标坐标 = 上 tick 位置：疑似被拽回原位
        boolean reset = Math.abs(x - player.xo) < RESET_EPSILON
            && Math.abs(y - player.yo) < RESET_EPSILON
            && Math.abs(z - player.zo) < RESET_EPSILON;
        if (!reset) return false;

        // 调用栈豁免：正常移动链（Entity.move/travel/aiStep/absMoveTo/moveTo 内部
        // 的 setPos）放行——服务端 aiStep 模拟位置落后/偏离客户端包位置时，move()
        // 的 setPos 目标可能恰好等于玩家上 tick 位置（跑跳/二段跳客户端预测场景
        // 实测顿挫），必须放行否则玩家被反复拉回/位置漂移。
        // 栈检查仅在疑似回溯时执行（低频），正常移动路径零开销。
        if (isNormalMovementCaller()) return false;

        // 外部直调 setPos 拽回 -> cancel
        return true;
    }

    /**
     * 正常移动链方法白名单——SRG 名 + 官方名双收录（v1.4.0）：
     * 运行时（生产）栈帧是 SRG 名；dev 环境（runClient）方法名保持官方名，
     * 只收 SRG 名会导致 dev 下正常移动链豁免失效（跑跳/二段跳误 cancel 顿挫）。
     */
    private static final Set<String> NORMAL_MOVEMENT_METHODS = Set.of(
        // Entity.move
        "m_6478_", "move",
        // Entity/LivingEntity/Player.travel
        "m_7023_", "travel",
        // LivingEntity/Player.aiStep
        "m_8107_", "aiStep",
        // Entity.absMoveTo(DDDFF) / absMoveTo(DDD)
        "m_19890_", "m_20248_", "absMoveTo",
        // Entity.moveTo(DDD) / moveTo(DDDFF) / moveTo(Vec3) / moveTo(BlockPos, FF)
        "m_6027_", "m_7678_", "m_20219_", "m_20035_", "moveTo"
    );

    private static boolean isNormalMovementCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // 跳过本类自身帧（getStackTrace / isNormalMovementCaller / 调用者）
        int start = Math.min(4, stack.length);
        int limit = Math.min(stack.length, start + 12);
        for (int i = start; i < limit; i++) {
            if (NORMAL_MOVEMENT_METHODS.contains(stack[i].getMethodName())) {
                return true;
            }
        }
        return false;
    }
}
