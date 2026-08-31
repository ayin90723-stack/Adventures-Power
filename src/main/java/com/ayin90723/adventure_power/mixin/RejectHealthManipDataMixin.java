package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拒绝篡改 —— 数据同步层拦截（{@link SynchedEntityData#set}）。
 * <p>
 * {@link RejectHealthManipMixin} 拦截 {@code setHealth()} 方法级调用；本 Mixin
 * 在更底层的 {@code SynchedEntityData.set} 注入（2 参 {@code m_135381_} 与 3 参
 * 强制版 {@code m_276349_}），覆盖绕过 setHealth 直接调用 {@code data.set()}
 * 写血量的路径（外部 Boss 缓存/反射 {@code DATA_HEALTH_ID} accessor 后直调）。
 * <p>
 * <b>覆盖边界</b>：本注入只拦「直接调用 {@code set()} 方法」的写入路径；
 * 反射直写 {@code DataItem.value} 字段的字段级通道（本模组
 * {@code HealthUtil.setAllHealthLikeRaw} 即此类）不经过 {@code set()}，由
 * {@code TrueHealthMixin} 读取层（getHealth HEAD）的备份比对修复兜底。
 * 客户端同步包走 {@code assignValues}（{@code m_135356_}）不经 {@code set()}，
 * 服务端所有直接写入均经 {@code set()}。
 * <p>
 * 门禁与方法级拦截一致：
 * <ul>
 *   <li>伤害链内（{@link HealthUtil#HURT_DEPTH} &gt; 0）放行——合法 hurt 结算（玩家与禁疗目标共用）</li>
 *   <li>模组内部写入（{@link HealthUtil#INTERNAL_HEALTH_WRITE}）放行——真血修复/
 *       内部裁剪/禁疗钳制压回，否则与 TrueHealthMixin 自愈形成死锁</li>
 *   <li><b>非玩家目标（v1.4.8 探查回血）</b>：禁疗中的回血升写（值高于钳制低点）cancel——
 *       写入瞬间拒绝，覆盖"绕过 setHealth 直接 set 数据条目"的镜像回写通道；降向恒放行</li>
 *   <li>玩家目标：升血放行，其余血量降值写入一律 cancel（拒绝篡改本职）</li>
 * </ul>
 * <p>
 * 性能：{@code SynchedEntityData.set} 每 tick 调用多次，注入体第一道过滤
 * （客户端侧 / key 非血量）即返回；非玩家禁疗分支为 ConcurrentHashMap 无锁读 +
 * ThreadLocal 检查；玩家门禁走
 * {@link com.ayin90723.adventure_power.util.ProgressCache} 按 tick 缓存。
 */
@Mixin(value = SynchedEntityData.class)
public abstract class RejectHealthManipDataMixin {

    /** 2 参 set：{@code set(EntityDataAccessor<T>, T)} */
    @Inject(method = "m_135381_", at = @At("HEAD"), cancellable = true)
    private <T> void rejectDirectHealthWrite(EntityDataAccessor<T> key, T value, CallbackInfo ci) {
        if (shouldReject(key, value)) {
            ci.cancel();
        }
    }

    /** 3 参强制 set：{@code set(EntityDataAccessor<T>, T, boolean force)}（服务端直调可绕过 2 参版） */
    @Inject(method = "m_276349_", at = @At("HEAD"), cancellable = true)
    private <T> void rejectDirectHealthWriteForce(EntityDataAccessor<T> key, T value, boolean force, CallbackInfo ci) {
        if (shouldReject(key, value)) {
            ci.cancel();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> boolean shouldReject(EntityDataAccessor<T> key, T value) {
        // 数据条目所有者实体经 SynchedEntityDataAccessor 访问（@Shadow 字段生产环境映射不可靠）
        Entity owner = ((SynchedEntityDataAccessor) this).adventure_power$getEntity();
        if (owner == null || owner.level().isClientSide()) return false;
        // 只拦血量条目（DATA_HEALTH_ID 全局唯一实例，引用比较；accessor 经 HealthUtil 缓存获取）
        EntityDataAccessor<Float> dataHealthId = HealthUtil.getDataHealthId();
        if (dataHealthId == null || key != dataHealthId) return false;
        if (!(value instanceof Float newHealth)) return false;

        // 伤害链内写入放行（合法 hurt 结算路径——玩家与禁疗目标共用）
        if (HealthUtil.HURT_DEPTH.get() > 0) return false;
        // 模组内部写入放行（真血修复/死亡抗拒恢复/vitality 裁剪/禁疗钳制压回等）
        if (HealthUtil.INTERNAL_HEALTH_WRITE.get()) return false;

        // v1.4.8 探查回血·数据层升写拦截：非玩家目标禁疗中的回血写入在此取消（写入瞬间拒绝，
        // 覆盖"绕过 setHealth 直接 set 数据条目"的镜像回写通道；降向恒放行；字段级直写由 tick 钳制兜底）
        if (!(owner instanceof Player player)) {
            return owner instanceof net.minecraft.world.entity.LivingEntity living
                && com.ayin90723.adventure_power.effect.HealingBlockEffect.isDataLayerRiseBlocked(living, newHealth);
        }

        // 升血放行（回血/修复）
        if (newHealth >= HealthUtil.getHealthDirect(player)) return false;

        // 能力门禁（ProgressCache 按 tick 缓存，高频安全）
        var progress = com.ayin90723.adventure_power.util.ProgressCache.get(player);
        if (progress == null) return false;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return false;
        if (!progress.isAbilityEnabled(AbilityIds.REJECT_MANIP)) return false;

        // 外部直写血量降值 -> cancel
        return true;
    }
}
