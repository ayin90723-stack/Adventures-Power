package com.ayin90723.adventure_power.util;

import net.minecraft.resources.ResourceLocation;
import javax.annotation.Nullable;

/**
 * 里程碑触发器定义 — 对应 milestones.json 中 trigger 字段。
 * type 为预置值之一：survive_night / first_death / first_trade / y_below / first_kill
 */
public record TriggerDef(
    String type,
    @Nullable Integer y,
    @Nullable ResourceLocation entity
) {}
