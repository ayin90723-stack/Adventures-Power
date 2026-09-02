package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可配置调试日志：由 {@code ModConfig.DEBUG_LOG} 总开关 + 各能力子开关双重控制
 * （adventure_power.toml → 冒险能力配置 → 调试），默认全关。调试时开总开关 +
 * 需要的子开关即可观察对应能力的诊断输出，避免日志过大；平时零开销
 * （一次布尔读取，字符串不格式化）。
 * <p>
 * v1.4.4：引擎探针日志（[插针]/[走梯]/[L3]/[灵魂打击] 等，原独立 {@code debug_log_probe}
 * 开关已删除）改为<b>按调用方能力开关归属</b>——同一段探针日志由淬魂走梯时挂淬魂开关、
 * 破敌之眼走梯时挂破敌开关、影杀处决时挂影杀开关（调用方上下文由引擎入口设置，见
 * {@link #setEngineCaller}）。淬魂/审判共用淬魂开关（审判无独立调试开关，引擎是淬魂的
 * 主导使用者，搭车记录）。
 */
public final class DebugLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("adventure_power.debug");

    /** 引擎调用方（改血引擎执行链的日志归属/调试分类） */
    public enum EngineCaller {
        SOUL_QUENCH, PIERCING_GAZE, HEALING_BLOCK, SHADOW_KILL, JUDGMENT
    }

    /** 引擎调用方上下文：BloodWriteEngine.execute 入口设置，finally 恢复（嵌套重入保持外层） */
    private static final ThreadLocal<EngineCaller> ENGINE_CALLER = new ThreadLocal<>();

    /** 引擎入口设置调用方上下文（引擎内部探针日志按它选择能力开关） */
    public static EngineCaller setEngineCaller(EngineCaller caller) {
        EngineCaller prev = ENGINE_CALLER.get();
        ENGINE_CALLER.set(caller);
        return prev;
    }

    /** 恢复引擎调用方上下文 */
    public static void restoreEngineCaller(EngineCaller prev) {
        ENGINE_CALLER.set(prev);
    }

    private DebugLog() {
    }

    /** 能力日志门禁：总开关 && 子开关 */
    private static boolean gate(boolean sub) {
        return ModConfig.DEBUG_LOG.get() && sub;
    }

    /**
     * 引擎探针日志门禁：总开关 && 调用方能力开关。
     * 调用方为 null（引擎外异常路径）时静默--探针日志只在引擎执行链内产生。
     */
    private static boolean probeGate() {
        if (!ModConfig.DEBUG_LOG.get()) return false;
        EngineCaller caller = ENGINE_CALLER.get();
        return caller != null && callerSubGate(caller);
    }

    /** 调用方能力子开关（淬魂/审判共用淬魂开关：审判无独立调试开关，搭车记录） */
    private static boolean callerSubGate(EngineCaller caller) {
        return switch (caller) {
            case SOUL_QUENCH, JUDGMENT -> ModConfig.DEBUG_LOG_SOUL_QUENCH.get();
            case PIERCING_GAZE -> ModConfig.DEBUG_LOG_PIERCING_GAZE.get();
            case HEALING_BLOCK -> ModConfig.DEBUG_LOG_HEALING_BLOCK.get();
            case SHADOW_KILL -> ModConfig.DEBUG_LOG_SHADOW_KILL.get();
        };
    }

    // ==================== 淬魂之力 ====================

    public static void soulQuench(String format, Object... args) {
        if (gate(ModConfig.DEBUG_LOG_SOUL_QUENCH.get())) {
            LOGGER.info(format, args);
        }
    }

    // ==================== 禁疗之触 ====================

    public static void healingBlock(String format, Object... args) {
        if (gate(ModConfig.DEBUG_LOG_HEALING_BLOCK.get())) {
            LOGGER.info(format, args);
        }
    }

    // ==================== 引擎探针/改血（按调用方能力开关归属） ====================

    public static void probe(String format, Object... args) {
        if (probeGate()) {
            LOGGER.info(format, args);
        }
    }

    // ==================== 死亡结算补完（按调用方能力开关归属，同引擎探针归属原则） ====================

    /** 死亡结算补完日志（v1.4.5 DeathFinalizer）：总开关 + 调用方能力开关 */
    public static void deathFinalize(EngineCaller caller, String format, Object... args) {
        if (caller != null && ModConfig.DEBUG_LOG.get() && callerSubGate(caller)) {
            LOGGER.info(format, args);
        }
    }

    // ==================== 影杀 ====================

    public static void shadowKill(String format, Object... args) {
        if (gate(ModConfig.DEBUG_LOG_SHADOW_KILL.get())) {
            LOGGER.info(format, args);
        }
    }

    // ==================== 破敌之眼 ====================

    public static void piercingGaze(String format, Object... args) {
        if (gate(ModConfig.DEBUG_LOG_PIERCING_GAZE.get())) {
            LOGGER.info(format, args);
        }
    }

    // ==================== 嗜血 ====================

    public static void lifesteal(String format, Object... args) {
        if (gate(ModConfig.DEBUG_LOG_LIFESTEAL.get())) {
            LOGGER.info(format, args);
        }
    }

    // ==================== 真实血量 ====================

    public static void trueHealth(String format, Object... args) {
        if (gate(ModConfig.DEBUG_LOG_TRUE_HEALTH.get())) {
            LOGGER.info(format, args);
        }
    }

    // ==================== 容器守护（v1.4.9） ====================

    public static void container(String format, Object... args) {
        if (gate(ModConfig.DEBUG_LOG_CONTAINER.get())) {
            LOGGER.info(format, args);
        }
    }
}
