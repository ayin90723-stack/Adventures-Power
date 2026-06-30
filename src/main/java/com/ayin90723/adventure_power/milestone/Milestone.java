package com.ayin90723.adventure_power.milestone;

import com.ayin90723.adventure_power.util.TriggerDef;
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
    /** 根据 id 查找里程碑，委托给 MilestoneRegistry */
    public static Milestone fromId(String id) {
        return com.ayin90723.adventure_power.util.MilestoneRegistry.getById(id);
    }
}
