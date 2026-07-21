package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 真实血量 -- 独立备份驱动的健康值保护，免疫一切 DataItem 字段直写篡改。
 *
 * <h3>四层防护</h3>
 * <ol>
 *   <li><b>读取层（getHealth HEAD）</b>：从 Capability 备份读取真实血量，
 *       而非被污染的 SynchedEntityData。同时检测备份与 DataItem 是否一致：
 *       若 DataItem &lt; 备份（非法降血直写，如 {@code catchSetTrueHealth}），
 *       自动通过 {@link HealthUtil#setAllHealthLikeRaw} + {@link HealthUtil#clearNegativeFloatDeltas} 修复。</li>
 *   <li><b>假死防护（getHealth HEAD）</b>：当 DataItem 被 {@code die()->catchSetTrueHealth(0)}
 *       清零但备份仍有效时，判定为伪造死亡，修复并返回备份值。</li>
 *   <li><b>同步层（setHealth RETURN）</b>：仅在合法路径（{@code hurt()} 内部或回血）下
 *       更新备份，拒绝外部篡改路径的同步。</li>
 *   <li><b>存活性自检（tick HEAD）</b>：每 tick 检测实体是否被外部标记为已移除/零血量/
 *       死亡状态，若备份有效则立即修复--清除 removalReason、恢复血量。
 *       作为 {@code die()} 和 {@code isDeadOrDying()} HEAD 注入被 ASM 绕过时的最后兜底。</li>
 * </ol>
 *
 * <h3>防御原理</h3>
 * 外部 Boss 的攻击链：
 * <pre>
 *   actuallyHurt0 -> setHealth(X) [被 RejectHealthManipMixin 拒绝]
 *                 -> catchSetTrueHealth(X) [直写 DataItem.value - 绕过一切]
 *   actuallyHurt wrapper -> die() -> catchSetTrueHealth(0) [强制清零]
 *                          -> setRemoved() -> removalReason = KILLED [字段直写]
 * </pre>
 * 本 Mixin 通过独立 Capability NBT 备份 + 多层注入（读取/同步/死亡拦截/tick 自检），
 * 确保所有非法修改在可检测的时间窗口内被纠正。
 *
 * <h3>通用性</h3>
 * 任何绕过 {@code setHealth()} 直接写入 DataItem 的攻击均被免疫。
 * 只有通过 {@code LivingEntity.hurt()} 的合法伤害能正常生效。
 *
 * <h3>性能</h3>
 * getHealth 是极高频调用（战斗/UI/属性计算每 tick 多次），本 Mixin 各注入点
 * 将原"isAdventurer + isFullyUnlocked + getAdventureProgress"三次 Capability 查询
 * 合并为单次查询，避免高频 map 查找开销。
 *
 * @see RejectHealthManipMixin setHealth 方法级拦截（外部直调）
 * @see DeathDefyMixin 死亡抗拒
 */
@Mixin(value = LivingEntity.class, priority = Integer.MAX_VALUE)
public abstract class TrueHealthMixin {

    private static final float EPSILON = 0.001F;

    /** 调试日志开关（由冒险能力配置文件中 true_health_debug_log 控制，默认关闭） */
    private static boolean debugLog() {
        return ModConfig.TRUE_HEALTH_DEBUG_LOG.get();
    }

    /** 重入防护：修复期间 BanHealing / 其他 Mixin 调 getHealth() 时直接返回备份 */
    private static final ThreadLocal<Boolean> IN_ON_GET_HEALTH =
        ThreadLocal.withInitial(() -> false);

    /**
     * 血量修复：先通过 {@link HealthUtil#setAllHealthLikeRaw} 恢复所有血量条目，
     * 再通过 {@link HealthUtil#clearNegativeFloatDeltas} 清除外部 Boss 注入的负值 delta。
     * <p>
     * 两次调用分别对应两个独立语义--"写入正确值"和"清除恶意偏移"。
     * 攻击侧（淬魂/影杀等）仅需 {@code setAllHealthLikeRaw}，无需清除负值 delta。
     */
    private static void repairHealth(LivingEntity player, float health) {
        HealthUtil.setAllHealthLikeRaw(player, health);
        HealthUtil.clearNegativeFloatDeltas(player);
    }

    // ===== 读取层：getHealth() HEAD =====

    @Inject(method = "m_21223_", at = @At("HEAD"), cancellable = true)
    private void onGetHealth(CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;

        // 客户端不需要备份机制--客户端没有攻击者，DataItem 是服务端同步的可靠值
        if (player.level().isClientSide()) return;

        // 单次 Capability 查询合并门禁（原 3 次 -> 1 次，getHealth 高频调用收益最大）
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
        if (!progress.isAbilityEnabled("true_health")) return;

        // 重入：修复期间 BanHealing / 其他 Mixin 调了 getHealth() ->
        // 直接返回备份值，防止读到未修复完成的旧 DataItem 导致修复被抵消
        if (IN_ON_GET_HEALTH.get()) {
            float backup = progress.getBackupHealth();
            if (backup > 0.0F) {
                cir.setReturnValue(backup);
            } else {
                cir.setReturnValue(HealthUtil.getHealthDirect(player));
            }
            return;
        }

        IN_ON_GET_HEALTH.set(true);
        try {
            float backup = progress.getBackupHealth();
            float rawHealth = HealthUtil.getHealthDirect(player);

            // DataItem 被写入 NaN/Infinity -> 用备份值覆盖修复
            if (Float.isNaN(rawHealth) || Float.isInfinite(rawHealth)) {
                float restore = backup > 0.0F ? backup : player.getMaxHealth();
                if (debugLog()) {
                    System.err.println("[MME-TrueHealth] 检测到异常血量！" +
                        " rawHealth=" + rawHealth + " -> setAllHealthLikeRaw 修复为 " + restore);
                }
                repairHealth(player, restore);
                progress.setBackupHealth(restore);
                cir.setReturnValue(restore);
                return;
            }

            // rawHealth ≤ 0 有两种情况：
            //   A) 备份也 ≤ 0 -> 玩家确实死了 -> 不干预
            //   B) 备份 > 0 -> 被 die()->catchSetTrueHealth(0) 伪造成死亡 -> 修复
            if (rawHealth <= 0.0F) {
                if (backup <= 0.0F) {
                    cir.setReturnValue(rawHealth);
                } else {
                    if (debugLog()) {
                        System.err.println("[MME-TrueHealth] 假死检测触发！" +
                            " DataItem=" + rawHealth + " backup=" + backup +
                            " -> setAllHealthLikeRaw 修复 -> 返回 " + backup);
                    }
                    repairHealth(player, backup);
                    cir.setReturnValue(backup);
                }
                return;
            }

            // 备份未初始化 / 玩家重生后恢复：从当前 DataItem 同步
            if (backup <= 0.0F) {
                backup = rawHealth;
                progress.setBackupHealth(backup);
                if (debugLog()) {
                    System.err.println("[MME-TrueHealth] 备份初始化: backup=" + backup +
                        " (从 DataItem=" + rawHealth + " 同步)");
                }
            }

            float effectiveEpsilon = EPSILON;
            // 觉醒：容差加倍（true_health 已由上方门禁保证启用）
            if (progress.isFullyUnlocked()) {
                effectiveEpsilon = EPSILON * 2.0F;
            }
            float diff = rawHealth - backup;
            if (Math.abs(diff) > effectiveEpsilon) {
                if (diff > 0.0F) {
                    // DataItem > 备份：合法回血 -> 更新备份
                    progress.setBackupHealth(rawHealth);
                    backup = rawHealth;
                } else {
                    // DataItem < 备份：非法降血直写 -> 修复
                    if (debugLog()) {
                        System.err.println("[MME-TrueHealth] 非法降血检测！" +
                            " DataItem=" + rawHealth + " backup=" + backup +
                            " diff=" + diff + " -> setAllHealthLikeRaw 修复为 " + backup);
                    }
                    repairHealth(player, backup);
                }
            }

            cir.setReturnValue(backup);
        } finally {
            IN_ON_GET_HEALTH.remove();
        }
    }

    // ===== 同步层：setHealth() RETURN =====

    /**
     * 仅在合法路径下更新备份：
     * <ul>
     *   <li>{@code hurt()} 内部 -> HURT_DEPTH > 0 -> 信任 setHealth 结果</li>
     *   <li>非 hurt 路径且 actual ≥ oldBackup -> 回血，更新</li>
     *   <li>非 hurt 路径且 actual < oldBackup -> 外部篡改 -> <b>拒绝同步</b></li>
     * </ul>
     */
    @Inject(method = "m_21153_", at = @At("RETURN"))
    private void onSetHealthReturn(float newHealth, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
            if (!progress.isAbilityEnabled("true_health")) return;

            float actual = HealthUtil.getHealthDirect(player);
            float oldBackup = progress.getBackupHealth();

            if (HealthUtil.HURT_DEPTH.get() > 0 || actual >= oldBackup - EPSILON) {
                progress.setBackupHealth(actual);
            } else {
                // HURT_DEPTH == 0 && actual < oldBackup -> 外部篡改，拒绝同步
                if (debugLog()) {
                    System.err.println("[MME-TrueHealth] 拒绝外部降血同步！" +
                        " actual=" + actual + " oldBackup=" + oldBackup +
                        " HURT_DEPTH=" + HealthUtil.HURT_DEPTH.get());
                }
            }
        });
    }

    // ===== 防直接死亡：isDeadOrDying() HEAD =====

    /**
     * 拦截 {@code isDeadOrDying()}，当备份血量 &gt; 0 时强制返回 false。
     *
     * <p>部分外部 Boss（如终焉秩序维系者）通过 ASM 篡改此方法，
     * 使 {@code special_isDeadOrDying()} 在内部 {@code isDead} 标记为 true 时
     * 不经血量检查直接返回 true。本注入在 Mixin 层（优先于 ASM 方法体执行）
     * 强制覆盖返回值--只要 Capability 备份表明玩家应存活，就不允许被判为死亡。</p>
     */
    @Inject(method = "m_21224_", at = @At("HEAD"), cancellable = true)
    private void onIsDeadOrDying(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
        if (!progress.isAbilityEnabled("true_health")) return;
        float backup = progress.getBackupHealth();
        if (backup > 0.0F) {
            // 备份表明玩家应存活，无视一切外部 isDead 标记
            cir.setReturnValue(false);
        }
    }

    // ===== 防直接死亡：die(DamageSource) HEAD =====

    /**
     * 拦截 {@code die(DamageSource)}，当 true_health 激活且备份血量 &gt; 0 时
     * 直接取消死亡处理。
     *
     * <p>外部 Boss 可在不经过 {@code hurt()/setHealth()} 的前提下直接调用
     * {@code player.die()} 并同时操作 {@code isDead} 标记和血量字段。
     * 本注入阻止 {@code die()} 方法体执行，防止掉落物、死亡动画、经验损失
     * 等副作用发生。同时通过 {@link #onIsDeadOrDying} 确保即使 {@code die()}
     * 被绕过，实体也不会被 Minecraft 移除。</p>
     */
    @Inject(method = "m_6668_", at = @At("HEAD"), cancellable = true)
    private void onDie(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
        if (!progress.isAbilityEnabled("true_health")) return;
        float backup = progress.getBackupHealth();
        if (backup > 0.0F) {
            if (debugLog()) {
                System.err.println("[MME-TrueHealth] 拦截外部 die() 调用！" +
                    " backup=" + backup + " -> cancel");
            }
            ci.cancel();
        }
    }

    // ===== 存活性自检：tick() HEAD =====

    /**
     * 每 tick 存活性自检--true_health 的最后一道防线。
     *
     * <h3>检测与修复</h3>
     * <ol>
     *   <li><b>已移除复活</b>：{@code isRemoved() == true} 且备份 &gt; 0 ->
     *       字段直写 {@code removalReason = null} + 血量恢复到备份值。
     *       处理外部 Mod 通过字段直写标记实体为已移除的场景。</li>
     *   <li><b>零血量修复</b>：DataItem 血量 ≤ 0 且备份 &gt; 0 ->
     *       {@code repairHealth} 恢复到备份值。作为 {@link #onGetHealth}
     *       读取层修复的补充--覆盖 tick 之间没有任何代码调用 {@code getHealth()}
     *       的极端情况。</li>
     *   <li><b>死亡状态否决</b>：{@code isDeadOrDying() == true} 且备份 &gt; 0 ->
     *       清除移除标记并恢复血量。处理 {@link #onIsDeadOrDying}
     *       被 ASM 层绕过的极端场景。</li>
     * </ol>
     *
     * <h3>为什么注入 tick() 而非 ServerTickEvent</h3>
     * {@code LivingEntity.tick()} 在服务端实体管理器的移除清理<b>之前</b>执行。
     * 每 tick 有一个修复窗口--如果本 tick 内实体被标记为已移除，
     * 下一次 tick HEAD 可以拦截并修复，在实体被从 chunk map / tick list 中
     * 清除之前挽救。
     * <p>
     * 若实体已被 {@code forceRemoveEntity} 从 tick list 中删除（无名术士
     * 不会对玩家执行此操作），tick 不再触发，此自检也失效。
     * 此时需依赖 {@link #onDie} 和 {@link #onIsDeadOrDying} 的 HEAD 注入。
     *
     * <h3>性能</h3>
     * 仅 true_health 激活的冒险者玩家每 tick 执行一次 Capability 查询 +
     * 至多三次简单条件检查。无反射遍历、无对象分配（除日志）。</p>
     */
    @Inject(method = "m_8119_", at = @At("HEAD"))
    private void onTickLivenessCheck(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return;
        if (!progress.isAbilityEnabled("true_health")) return;
        float backup = progress.getBackupHealth();
        if (backup <= 0.0F) return;  // 备份无效，玩家确实应死

        boolean repaired = false;

        // ① 已移除复活：removalReason 被外部字段直写
        if (player.isRemoved()) {
            if (debugLog()) {
                System.err.println("[MME-TrueHealth] 存活性自检：实体已移除！" +
                    " removalReason=" + player.getRemovalReason() +
                    " backup=" + backup + " -> clearRemovedFlag + 血量恢复");
            }
            HealthUtil.clearRemovedFlag(player);
            repairHealth(player, backup);
            repaired = true;
        }

        // ② 零血量修复：tick 之间被外部清零，且 getHealth() 未被调用
        float rawHealth = HealthUtil.getHealthDirect(player);
        if (!repaired && rawHealth <= 0.0F) {
            if (debugLog()) {
                System.err.println("[MME-TrueHealth] 存活性自检：零血量！" +
                    " DataItem=" + rawHealth + " backup=" + backup +
                    " -> setAllHealthLikeRaw 修复");
            }
            repairHealth(player, backup);
            repaired = true;
        }

        // ③ 死亡状态否决：isDeadOrDying 被 ASM 绕过
        if (player.isDeadOrDying() && backup > 0.0F) {
            if (debugLog()) {
                System.err.println("[MME-TrueHealth] 存活性自检：isDeadOrDying=true！" +
                    " backup=" + backup + " -> clearRemovedFlag + 血量恢复");
            }
            HealthUtil.clearRemovedFlag(player);
            repairHealth(player, backup);
        }
    }
}
