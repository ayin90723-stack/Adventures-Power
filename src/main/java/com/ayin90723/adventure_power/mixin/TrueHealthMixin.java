package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.ClassPointerGuard;
import com.ayin90723.adventure_power.util.DebugLog;
import com.ayin90723.adventure_power.util.HealthUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
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
@Mixin(value = LivingEntity.class, priority = 10000)
public abstract class TrueHealthMixin {

    private static final float EPSILON = 0.001F;

    /** 调试日志开关（由冒险能力配置文件中 debug_log + debug_log_true_health 控制，默认关闭） */
    private static boolean debugLog() {
        return ModConfig.DEBUG_LOG.get() && ModConfig.DEBUG_LOG_TRUE_HEALTH.get();
    }

    /**
     * 能力门禁辅助：返回通过 true_health 门禁的 IAdventureProgress，未通过返回 null。
     * 统一 5 个注入点重复的"取进度->冒险者/觉醒->能力启用"检查。
     * <p>
     * 性能：通过 {@link com.ayin90723.adventure_power.util.ProgressCache} 按 tick 缓存
     * progress 引用——getHealth 是 MC 极高频调用（每 tick 每玩家数十次），
     * 每次调用都做 LazyOptional.resolve() 代价昂贵，缓存后每玩家每 tick 至多 resolve 一次。
     */
    private static IAdventureProgress gatedProgress(LivingEntity self) {
        if (!(self instanceof Player player)) return null;
        if (player.level().isClientSide()) return null;
        var progress = com.ayin90723.adventure_power.util.ProgressCache.get(player);
        if (progress == null) return null;
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) return null;
        if (!progress.isAbilityEnabled(AbilityIds.TRUE_HEALTH)) return null;
        return progress;
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
     * <p>
     * v1.4.0 补：修复后强制客户端同步——{@code setAllHealthLikeRaw} 反射直写
     * {@code DataItem.value} 不触发 dirty，若外部先经 {@code data.set(0)} 把 0 同步给
     * 客户端，修复值将永远推不到客户端（血条停留在 0，服务端 alive 但客户端显示
     * "血条 0、无死亡画面"的卡死感）。补一发 {@code INTERNAL_HEALTH_WRITE} 标记的
     * {@code data.set} 让同步包携带修复值（NaN 修复场景依赖 INTERNAL 放行双拦截层；
     * 升血场景 {@code RejectHealthManipDataMixin} 自然放行）。反射失败（accessor 为 null）
     * 时跳过同步，退回原逻辑。
     */
    private static void repairHealth(LivingEntity player, float health) {
        HealthUtil.setAllHealthLikeRaw(player, health);
        HealthUtil.clearNegativeFloatDeltas(player);
        EntityDataAccessor<Float> dataHealthId = HealthUtil.getDataHealthId();
        if (dataHealthId != null) {
            boolean prev = HealthUtil.INTERNAL_HEALTH_WRITE.get();
            HealthUtil.INTERNAL_HEALTH_WRITE.set(true);
            try {
                player.getEntityData().set(dataHealthId, health);
            } finally {
                HealthUtil.INTERNAL_HEALTH_WRITE.set(prev);
            }
        }
    }

    // ===== 读取层：getHealth() HEAD =====

    @Inject(method = "m_21223_", at = @At("HEAD"), cancellable = true)
    private void onGetHealth(CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        Player player = (Player) self;

        // 重入：修复期间 BanHealing / 其他 Mixin 调了 getHealth() ->
        // 直接返回备份值，防止读到未修复完成的旧 DataItem 导致修复被抵消。
        // 不再调 getHealthDirect：其退化路径（反射初始化失败）会回落到 getHealth()
        // 再次进入本注入点，backup<=0 时形成无限递归——直接返回备份或最大生命。
        // backup 必须同时是有限值：NaN 比较全 false 落到 maxHealth（安全），
        // +Infinity 会被误当作有效备份返回（污染读数）——isFinite 兜底。
        if (IN_ON_GET_HEALTH.get()) {
            float backup = progress.getBackupHealth();
            cir.setReturnValue(backup > 0.0F && Float.isFinite(backup) ? backup : player.getMaxHealth());
            return;
        }

        IN_ON_GET_HEALTH.set(true);
        try {
            float backup = progress.getBackupHealth();
            float rawHealth = HealthUtil.getHealthDirect(player);

            // DataItem 被写入 NaN/Infinity -> 用备份值覆盖修复。
            // restore 必须同时是有限值：backup=+Infinity 且 rawHealth 也非法（双重污染）时，
            // 若直接选中 +Inf 会把 DataItem 永久修复成 Infinity（循环固化）——isFinite 兜底
            if (Float.isNaN(rawHealth) || Float.isInfinite(rawHealth)) {
                float restore = backup > 0.0F && Float.isFinite(backup) ? backup : player.getMaxHealth();
                if (debugLog()) {
                    DebugLog.trueHealth("[MME-TrueHealth] 检测到异常血量！" +
                        " rawHealth=" + rawHealth + " -> setAllHealthLikeRaw 修复为 " + restore);
                }
                repairHealth(player, restore);
                progress.setBackupHealth(restore);
                cir.setReturnValue(restore);
                return;
            }

            // backup 被外部写入 NaN/Infinity -> 从 DataItem 重建。
            // NaN 时所有比较（backup>0 / backup<=0）全 false，会跳过初始化分支直接
            // 返回 NaN；+Infinity 时 diff=-Inf 被误判"非法降血"，repairHealth(Inf)
            // 会把玩家血量修复成 Infinity（永久数据损坏）。
            // 合法路径 backup 恒为正常 float（setHealth RETURN 同步/初始化分支），
            // 检测到异常必然来自外部字段直写（如插针直写 Capability 备份字段）。
            // 权衡披露：backup 污染 + DataItem<=0（伪造死亡 + 备份污染双写）时，
            // 重建后 backup=0，后续假死分支按"确实死了"放行死亡——备份失效时以
            // DataItem 为唯一事实来源（改动前 NaN 会经 repairHealth(NaN) 自愈，
            // 但代价是 DataItem 被短暂污染，且依赖下一次 getHealth 触发修复）。
            if (Float.isNaN(backup) || Float.isInfinite(backup)) {
                if (debugLog()) {
                    DebugLog.trueHealth("[MME-TrueHealth] 备份被污染！" +
                        " backup=" + backup + " -> 从 DataItem=" + rawHealth + " 重建");
                }
                progress.setBackupHealth(rawHealth);
                backup = rawHealth;
            }

            // rawHealth ≤ 0 有两种情况：
            //   A) 备份也 ≤ 0 -> 玩家确实死了 -> 不干预
            //   B) 备份 > 0 -> 被 die()->catchSetTrueHealth(0) 伪造成死亡 -> 修复
            if (rawHealth <= 0.0F) {
                if (backup <= 0.0F) {
                    cir.setReturnValue(rawHealth);
                } else {
                    if (debugLog()) {
                        DebugLog.trueHealth("[MME-TrueHealth] 假死检测触发！" +
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
                    DebugLog.trueHealth("[MME-TrueHealth] 备份初始化: backup=" + backup +
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
                        DebugLog.trueHealth("[MME-TrueHealth] 非法降血检测！" +
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
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        Player player = (Player) self;

        float actual = HealthUtil.getHealthDirect(player);
        float oldBackup = progress.getBackupHealth();

        // 觉醒防秒杀底线：外部 setHealth 篡改到致死值（非 hurt 路径 + newHealth<=0），
        // 强制保留 1HP，防止 setHealth(0) 等秒杀。正常 hurt 打死不受影响（HURT_DEPTH>0）。
        // INTERNAL_HEALTH_WRITE 时不触发：模组内部降血（vitality 裁剪到 ≤0 的极端退化场景）
        // 不应被防秒杀覆盖（否则裁剪语义失效且 backup 被污染为 1）。
        if (progress.isFullyUnlocked() && HealthUtil.HURT_DEPTH.get() == 0 && actual <= 0.0F
            && !HealthUtil.INTERNAL_HEALTH_WRITE.get()) {
            progress.setBackupHealth(1.0F);
            repairHealth(player, 1.0F);
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 觉醒防秒杀！" +
                    " newHealth=" + newHealth + " actual=" + actual + " -> 强制保留 1HP");
            }
            return;
        }

        if (HealthUtil.HURT_DEPTH.get() > 0 || actual >= oldBackup - EPSILON
            || HealthUtil.INTERNAL_HEALTH_WRITE.get()) {
            // INTERNAL_HEALTH_WRITE：模组内部降血（vitality 裁剪等）同样同步备份，
            // 否则 backup 冻结在旧值，下次 getHealth 会把 DataItem 判定为"非法降血直写"
            // 而修复回旧值，裁剪被反向抵消
            progress.setBackupHealth(actual);
        } else {
            // HURT_DEPTH == 0 && actual < oldBackup -> 外部篡改，拒绝同步
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 拒绝外部降血同步！" +
                    " actual=" + actual + " oldBackup=" + oldBackup +
                    " HURT_DEPTH=" + HealthUtil.HURT_DEPTH.get());
            }
        }
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
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        float backup = progress.getBackupHealth();
        if (backup > 0.0F) {
            // 备份表明玩家应存活，无视一切外部 isDead 标记
            cir.setReturnValue(false);
        }
    }

    // ===== 存活性：isAlive() HEAD =====

    /**
     * 拦截 {@code isAlive()}，当备份血量 &gt; 0 时强制返回 true。
     * <p>
     * 与 {@link #onIsDeadOrDying} 配套：存活判定以 Capability 备份为准，
     * 外部通过字段直写把玩家标记为死亡（{@code dead} 字段直写、removalReason
     * 直写）时，只要备份表明玩家应存活就不允许被判为"非存活"——防止
     * {@code tickDeath -> remove(KILLED)} 死亡流程推进。正常死亡（备份 ≤ 0）
     * 放行原版判定。
     * <p>
     * <b>覆盖边界</b>：本注入落在 {@code LivingEntity} 声明的方法体上，若外部
     * 通过类指针替换换入覆写 isAlive 的隐藏类（子类覆写后调用直达覆写版，
     * 注入点不在分派链上），本注入不触发——该场景由 {@link #onTickLivenessCheck}
     * 的类指针守卫（ClassPointerGuard 换回原类）处理。</p>
     */
    @Inject(method = "m_6084_", at = @At("HEAD"), cancellable = true)
    private void onIsAlive(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        if (progress.getBackupHealth() > 0.0F) {
            cir.setReturnValue(true);
        }
    }

    // ===== 防移除：remove() HEAD =====

    /**
     * 拦截 {@code Entity.remove(RemovalReason)} (SRG {@code m_142687_})，
     * 当备份血量 &gt; 0 时取消实体移除。注意 {@code m_142687_} 是 {@code remove()}
     * 而非 {@code setRemoved()}（后者为 {@code m_142467_}）——原版死亡推进链
     * {@code tickDeath -> remove(KILLED)} 与秒杀路径走的都是 {@code remove()}。
     * <p>
     * 对抗「绕过 die()/LivingDeathEvent 直接移除玩家」的击杀路径
     * （如 killPlayer 的 {@code tickDeath -> remove(KILLED)}，其隐藏类覆写
     * isAlive/isDeadOrDying 让原版死亡流程推进，但 remove 未被覆写、本注入
     * 仍在分派链上）。配合 {@link #onTickLivenessCheck} 的类指针守卫，
     * 先保实体不被移除，再由 tick 自检把方法分派恢复原状。
     * <p>
     * <b>原因门禁</b>：仅拦截 {@code KILLED}（死亡推进/击杀）与 {@code DISCARDED}
     * （discard 型击杀）。正常流程不误伤：
     * <ul>
     *   <li>登出/踢出/封禁：{@code PlayerList.remove -> ServerLevel.removePlayerImmediately(UNLOADED_WITH_PLAYER)}——放行</li>
     *   <li>维度切换：{@code removePlayerImmediately(CHANGED_DIMENSION)}——放行</li>
     *   <li>重生 respawn：{@code remove(DISCARDED)} 但正常死亡备份 ≤ 0 已在上方放行</li>
     * </ul>
     * 若不加原因门禁，登出/换维度会被 cancel 导致幽灵实体（实体永留世界、
     * knownUuids 未清除，重登失败）与双维度重复实体。
     * {@code RejectHealthManipMixin} 同位置的 ATTR_OWNER 清理注入不受 cancel 影响
     * （Mixin 多注入互不阻断），玩家存活时条目保留无泄漏。</p>
     */
    @Inject(method = "m_142687_", at = @At("HEAD"), cancellable = true)
    private void onSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        // 只拦击杀/丢弃型移除；登出(UNLOADED_WITH_PLAYER)/维度切换(CHANGED_DIMENSION)
        // 等正常流程放行，避免幽灵实体与双维度重复实体
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        if (progress.getBackupHealth() > 0.0F) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 拦截实体移除！" +
                    " reason=" + reason + " backup=" + progress.getBackupHealth() + " -> cancel");
            }
            ci.cancel();
        }
    }

    // ===== 防直接死亡：die(DamageSource) HEAD =====
    // SRG m_6667_ = die(DamageSource)。注意勿写成 m_6668_（= dropAllDeathLoot，仅掉落不触发死亡事件）。

    /**
     * 拦截 {@code die(DamageSource)} (SRG {@code m_6667_})，当 true_health 激活且备份血量 &gt; 0 时
     * 直接取消死亡处理。
     *
     * <p>外部 Boss 可在不经过 {@code hurt()/setHealth()} 的前提下直接调用
     * {@code player.die()} 并同时操作 {@code isDead} 标记和血量字段。
     * 本注入阻止 {@code die()} 方法体执行，防止掉落物、死亡动画、经验损失
     * 等副作用发生。同时通过 {@link #onIsDeadOrDying} 确保即使 {@code die()}
     * 被绕过，实体也不会被 Minecraft 移除。</p>
     *
     * <p>事件层兜底：若本 Mixin 注入被 ASM 绕过（die() 照常执行并 post
     * {@code LivingDeathEvent}），由 {@link com.ayin90723.adventure_power.handler.TrueHealthHandler}
     * 在事件层 HIGH 优先级 cancel 死亡事件，作为最后防线。</p>
     */
    @Inject(method = "m_6667_", at = @At("HEAD"), cancellable = true)
    private void onDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        float backup = progress.getBackupHealth();
        if (backup > 0.0F) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 拦截外部 die() 调用！" +
                    " backup=" + backup + " -> cancel");
            }
            ci.cancel();
        }
    }

    // ===== 防直接死亡：kill() HEAD =====
    // SRG m_6074_ = kill()（1.20.1 无参，内部调 die(genericKill)）。

    /**
     * 拦截 {@code kill()} (SRG {@code m_6074_})——kill 内部走 {@code die()}，
     * 正常链已被 {@link #onDie} 覆盖；但<b>子类覆写 kill 时调用直达覆写版，
     * die 注入不在分派链上</b>（与 isAlive 覆写绕过同族，见 ClassPointerGuard）——
     * 本注入在方法级补防。门禁与 onDie 一致：备份血量 &gt; 0 → cancel。
     */
    @Inject(method = "m_6074_", at = @At("HEAD"), cancellable = true)
    private void onKill(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        if (progress.getBackupHealth() > 0.0F) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 拦截外部 kill() 调用！" +
                    " backup=" + progress.getBackupHealth() + " -> cancel");
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
        IAdventureProgress progress = gatedProgress(self);
        if (progress == null) return;
        Player player = (Player) self;
        float backup = progress.getBackupHealth();
        // backup 被外部写入 NaN/Infinity -> 先重建再检测（镜像 onGetHealth 重建逻辑）：
        // 直接跳过会让 isRemoved 救援（下方①分支）失效——实体在 backup 污染窗口内
        // 被字段直写标记移除后无人清除 removalReason。
        // DataItem 也非法（双重污染）时才放弃自检，交给 getHealth 层兜底。
        if (Float.isNaN(backup) || Float.isInfinite(backup)) {
            float raw = HealthUtil.getHealthDirect(player);
            if (Float.isNaN(raw) || Float.isInfinite(raw)) return;
            progress.setBackupHealth(raw);
            backup = raw;
        }
        // 备份无效（玩家确实应死）或重建后仍为 0
        if (backup <= 0.0F) return;

        // 类指针守卫：首次通过门禁时记录玩家类（之后空操作）
        ClassPointerGuard.record(player);

        boolean repaired = false;

        // ① 已移除复活：removalReason 被外部字段直写。
        //    v1.3.9 改为直读字段：EntityLivenessMixin 对真血玩家强制 isRemoved()=false
        //    （容器抹除防线保 tick 前提），player.isRemoved() 条件永不成立，必须直读字段。
        //    登出/换维度（UNLOADED_WITH_PLAYER/CHANGED_DIMENSION）此处也会触发清理——
        //    无害：这两个流程中实体已从 tick 表移除（levelCallback.onRemove），本自检不再执行；
        //    若窗口内执行，clearRemovedFlag 只清字段不影响原版容器清理。
        if (((EntityFieldsAccessor) (Object) player).adventure_power$getRemovalReason() != null) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 存活性自检：实体已移除！" +
                    " removalReason=" + ((EntityFieldsAccessor) (Object) player).adventure_power$getRemovalReason() +
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
                DebugLog.trueHealth("[MME-TrueHealth] 存活性自检：零血量！" +
                    " DataItem=" + rawHealth + " backup=" + backup +
                    " -> setAllHealthLikeRaw 修复");
            }
            repairHealth(player, backup);
            repaired = true;
        }

        // ③ 死亡状态否决：isDeadOrDying 被 ASM 绕过
        if (player.isDeadOrDying() && backup > 0.0F) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 存活性自检：isDeadOrDying=true！" +
                    " backup=" + backup + " -> clearRemovedFlag + 血量恢复");
            }
            HealthUtil.clearRemovedFlag(player);
            repairHealth(player, backup);
        }

        // ④ 死亡字段归位：dead/deathTime 被外部字段直写（不走 die()/remove）。
        //    dead 参与 die() 的幂等守卫（!isRemoved() && !dead），被直写 true 后
        //    后续 die() 调用会被跳过；deathTime 残留会让死亡动画持续播放。
        //    必须强制归位。备份 ≤ 0 的正常死亡在上面已放行。
        //    字段经 LivingEntityFieldsAccessor 访问（@Shadow 字段生产环境映射不可靠）。
        LivingEntityFieldsAccessor fields = (LivingEntityFieldsAccessor) (Object) player;
        if (fields.adventure_power$isDead()) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 存活性自检：dead 字段被直写！" +
                    " deathTime=" + fields.adventure_power$getDeathTime() + " -> 归位");
            }
            fields.adventure_power$setDead(false);
            fields.adventure_power$setDeathTime(0);
        } else if (fields.adventure_power$getDeathTime() > 0 && !player.isDeadOrDying()) {
            // 死亡动画残留：外部把 deathTime 推进但死亡状态已被否决，归位
            fields.adventure_power$setDeathTime(0);
        }

        // ⑤ 类指针守卫：实体类被替换（killPlayer 类指针替换）——换回原类恢复
        //    方法分派（getHealth/isAlive/isDeadOrDying 覆写全部失效），并清
        //    死亡状态残留。此时 setRemoved 拦截（onSetRemoved）已保证实体未被
        //    从世界中移除，本步是功能恢复的关键。
        if (ClassPointerGuard.isReplaced(player)) {
            if (debugLog()) {
                DebugLog.trueHealth("[MME-TrueHealth] 存活性自检：检测到实体类被替换！" +
                    " class=" + player.getClass().getName() + " -> 换回 " +
                    ClassPointerGuard.expectedClassName());
            }
            if (ClassPointerGuard.restore(player)) {
                fields.adventure_power$setDead(false);
                fields.adventure_power$setDeathTime(0);
                HealthUtil.clearRemovedFlag(player);
            }
        }
    }
}
