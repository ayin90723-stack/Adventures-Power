package com.ayin90723.adventure_power.milestone;

import com.ayin90723.adventure_power.util.TriggerDef;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import javax.annotation.Nullable;
import java.util.List;

/**
 * 冒险里程碑 — 由 MilestoneRegistry 从数据包 JSON 加载。
 * 不再使用枚举，改为 record 以支持数据驱动。
 */
public record Milestone(
    String id,
    String name,
    List<String> abilities,
    ResourceLocation advancement,
    @Nullable TriggerDef trigger
) {
    /**
     * 本地化显示名：内置里程碑走 lang 键（zh/en 翻译），数据包自定义里程碑用
     * JSON name 兜底。所有 UI 显示点（解锁提示/面板/饰品 tooltip/指令）统一走此方法，
     * 避免直读 JSON 硬编码 name 导致英文客户端显示中文（v1.4.0 审查修复）。
     */
    public MutableComponent displayName() {
        return net.minecraft.network.chat.Component
            .translatableWithFallback("milestone.adventure_power." + id, name);
    }
}
