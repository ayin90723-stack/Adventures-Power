package com.ayin90723.adventure_power.util.probe;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.world.entity.LivingEntity;

/**
 * 改血引擎量纲计算（设计文档 quench-upgrade-proposal.md 不变量⑦）。
 * <p>
 * 一切绝对值参数禁止裸量纲，随目标体量缩放：float32 在 131k HP 处
 * ulp≈0.0156、1M 处 0.125、16M 处 2.0——裸 0.01/1.0 阈值在高血量 Boss
 * 上会低于精度地板（写入被舍入吞掉、探针静默失效）。规范定义：
 * {@code ε = max(基数, ulp(reading) × 4)}，ulp×4 保证写入跨越至少 4 个
 * 可表示值间隔，其余阈值随 ε 派生。
 * <p>
 * ulp 倍数 4 为工程常数（同 HealthUtil.GRAPH_DEPTH_LIMIT 性质，不配置化）；
 * 基数与重探比例走 ModConfig（能力数值约定）。
 */
public final class ProbeScales {

    private ProbeScales() {
    }

    /** ulp 安全倍数：写入量至少跨越 4 个 float 可表示值间隔。 */
    private static final float ULP_FACTOR = 4.0F;

    /** 探针步长 ε：max(配置基数, ulp(reading)×4)。探测与验证的最小有意义的扰动量。 */
    public static float epsilon(float reading) {
        float base = ModConfig.QUENCH_PROBE_EPS_BASE.get().floatValue();
        return Math.max(base, Math.ulp(reading) * ULP_FACTOR);
    }

    /** 联动验证门槛（"写入前后 getHealth 必须真实变化"的下限），随 ε 走（ε/2，保留 0.5 旧下限兼容中小血量）。 */
    public static float verifyThreshold(float eps) {
        return Math.max(0.5F, eps * 0.5F);
    }

    /** 指向容差（"变化量必须指向测试值"的判定带宽），随 ε 走（保留 1.0 旧下限）。 */
    public static float driftTolerance(float eps) {
        return Math.max(1.0F, eps);
    }

    /** 值闸容差：候选字段值与血量形态参照的匹配带宽（沿用原版插针 max(1.0, reading×0.2)）。 */
    public static float gateTolerance(float reading) {
        return Math.max(1.0F, Math.abs(reading) * 0.2F);
    }

    /** 读数条件化失效阈值：读数较 L2 负缓存记录时刻漂移超过 max(1.0, maxHealth×ratio) 时失效重探。 */
    public static float reprobeThreshold(float maxHealth) {
        return Math.max(1.0F, Math.abs(maxHealth) * ModConfig.QUENCH_REPROBE_RATIO.get().floatValue());
    }

    /**
     * hurt() 拦截判定容差：期望伤害与实际扣血差超过该值视为被拦截。
     * 三项取 max：伤害量 1%（旧逻辑）、读数 ulp 地板（大血量下读数本身舍入）、绝对下限 0.01。
     */
    public static float interceptTolerance(float expectedDamage, float healthBefore) {
        return Math.max(0.01F, Math.max(expectedDamage * 0.01F, Math.ulp(healthBefore) * ULP_FACTOR));
    }

    /** 反向形态闸地板：maxHealth−reading ≥ 1.0 时反向（承伤累计型）候选才参与常规探测（封存前补探放宽此闸）。 */
    public static boolean reverseFloorMet(LivingEntity target, float reading) {
        return target.getMaxHealth() - reading >= 1.0F;
    }
}
