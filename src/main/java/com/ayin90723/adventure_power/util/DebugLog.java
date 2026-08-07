package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可配置调试日志：由 {@code ModConfig.DEBUG_LOG} 总开关 + 各能力子开关双重控制
 * （adventure_power.toml → 冒险能力配置 → 调试），默认全关。调试时开总开关 +
 * 需要的子开关即可观察对应能力的诊断输出，避免日志过大；平时零开销
 * （一次布尔读取，字符串不格式化）。
 */
public final class DebugLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("adventure_power.debug");

    private DebugLog() {
    }

    /** 能力日志门禁：总开关 && 子开关 */
    private static boolean gate(boolean sub) {
        return ModConfig.DEBUG_LOG.get() && sub;
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

    // ==================== 插针/通用直写 ====================

    public static void probe(String format, Object... args) {
        if (gate(ModConfig.DEBUG_LOG_PROBE.get())) {
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
}
