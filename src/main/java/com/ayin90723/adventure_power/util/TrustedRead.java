package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.mixin.LivingEntityFieldsAccessor;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 读数可信原语：攻击/防御链关键读血点的统一对账入口（v1.4.8）。
 * <p>
 * 读血失真有两个已知攻击面，语义不同，处置也不同：
 * <ul>
 *   <li><b>架空型</b>（实测案例：亚波伦）——原版 DataItem 槽是不动值，真血在覆写
 *       {@code getHealth()} 里。此时<b>数据层直读是假的</b>，方法读才是真血。</li>
 *   <li><b>谎报型</b>（实锤案例：yuyu HealthKillTransformer 对全 JVM 类的
 *       {@code getHealth()}/{@code isDeadOrDying()} 注入 HEAD 谎报）——方法体被
 *       Mixin/ASM/agent 注入后返回假值。此时<b>方法读是假的</b>，数据层直读可信
 *       （方法体钩子拦不到字段读）。</li>
 * </ul>
 * 两者在外部观测上同构（|方法读 − 直读| &gt; tol），无实弹证据时不可武断翻转默认方向
 * ——架空取方法读的语义由实测案例支撑，维持不变；本类的职责是把对账收口到单一入口、
 * 暴露结构化结果与不一致标记，让后续按能力语义各自决策（伤害结算基准/影子投影/写后
 * 验证的读数来源今后可按需分叉），不再散落各处各写一套。
 * <p>
 * <b>三方构成</b>：
 * <ol>
 *   <li>方法读 {@code getHealth()}（可能被覆写/JVM 层注入谎报）；</li>
 *   <li>数据层直读 {@code DataItem.value}（绕过一切方法体钩子——{@link HealthUtil#getHealthDirect}）；</li>
 *   <li>容器事实 {@link #isFactuallyDead}（isRemoved / deathTime 直读——存活判定不依赖
 *       血量读数，对 liveness 谎报免疫；与 GateOracle confirmDead 同一判据族）。</li>
 * </ol>
 * <p>
 * <b>性能分层</b>：热路径（probeGraph 每次扰动验证都读）走 {@link #value}——零分配，
 * 与原 getEffectiveHealth 等价；需要完整对账与不一致检测时用 {@link #read}（record
 * 分配 + per-tick 采样日志）；死亡判定一律 {@link #isFactuallyDead}。
 */
public final class TrustedRead {

    private TrustedRead() {
    }

    /** 对账结果：方法读/数据直读/采纳值/一致性标记。 */
    public record Result(float direct, float reported, float value, boolean consistent) {
    }

    /** 架空判定阈值（与原 getEffectiveHealth 历史行为一致，勿随意收紧——探针 eps=1.0 场景依赖）。 */
    private static final float SKEW_THRESHOLD = 1.0F;

    /**
     * 热路径采纳值（零分配）：两读一致取直读（原版槽即真血），不一致取方法读
     * （架空语义——真血在覆写 getHealth 里的 Boss）。与原 getEffectiveHealth 行为等价。
     */
    public static float value(LivingEntity target) {
        float direct = HealthUtil.getHealthDirect(target);
        float reported = target.getHealth();
        return Math.abs(reported - direct) > SKEW_THRESHOLD ? reported : direct;
    }

    /**
     * 完整对账（低频诊断/关键决策入口）：两方读数 + 一致性标记。
     * 不一致时按"实体 × tick"采样告警一次（防限伤/浮动读数 Boss 刷屏），
     * 采样表弱 key 防泄漏（与 EntityLivenessMixin 日志去重同款纪律）。
     */
    public static Result read(LivingEntity target) {
        float direct = HealthUtil.getHealthDirect(target);
        float reported = target.getHealth();
        boolean consistent = Math.abs(reported - direct) <= SKEW_THRESHOLD;
        float value = consistent ? direct : reported;
        if (!consistent) {
            long now = target.level().getGameTime();
            Map<LivingEntity, Long> seen = SEEN_MISMATCH.get();
            Long last = seen.get(target);
            if (last == null || last != now) {
                seen.put(target, now);
                DebugLog.probe("[可信读] {} 读数不一致：方法读={} 数据直读={}（架空型真血在覆写侧 / 谎报型数据侧可信），采纳={}",
                    target.getClass().getSimpleName(), reported, direct, value);
            }
        }
        return new Result(direct, reported, value, consistent);
    }

    /**
     * 容器事实死亡判定（第三方）：isRemoved 或 deathTime&gt;0。
     * <p>
     * 死亡事实不依赖血量读数——两类读数失真（架空/谎报）都影响不到它。与 GateOracle
     * confirmDead 的硬证据判据同族（那边另含死亡流程证据，这里是最小公共子集，供
     * 攻击链上的快速裁决）。
     */
    public static boolean isFactuallyDead(LivingEntity target) {
        if (target.isRemoved()) return true;
        try {
            return ((LivingEntityFieldsAccessor) target).adventure_power$getDeathTime() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 不一致采样表：实体弱 key × tick 去重（ThreadLocal 私有，无需同步包装）。 */
    private static final ThreadLocal<Map<LivingEntity, Long>> SEEN_MISMATCH =
        ThreadLocal.withInitial(WeakHashMap::new);
}
