package com.ayin90723.adventure_power.mixin;

import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.UUID;

/**
 * 暴露 {@link PersistentEntitySectionManager} 容器字段（SRG f_157491_ / f_157492_ /
 * f_157495_ / f_157494_）的 Accessor 接口（v1.4.9 容器重建链）。
 * <p>
 * <ul>
 *   <li>{@code knownUuids}：UUID 注册表（审计 A3 / 重注册预清——addEntityUuid 查重拒绝）</li>
 *   <li>{@code callbacks}：{@code LevelCallback}（= ServerLevel$EntityCallbacks，
 *       重建第 5 步 onTrackingStart 直调入口——追踪链+客户端广播）</li>
 *   <li>{@code sectionStorage}：空间索引（审计 A5 / 重建 section 定位）</li>
 *   <li>{@code visibleEntityStorage}：{@code EntityLookup}（byId/byUuid 双表——审计 A4
 *       的本体；重注册预清必须连它一起清：EntityLookup.add 在 byUuid 已含同 UUID 时
 *       warn 后直接 return、byId 不写，"byUuid 残留+byId 被抹"形态不预清无法自愈）</li>
 * </ul>
 */
@Mixin(PersistentEntitySectionManager.class)
public interface PesmFieldAccessor {

    @Accessor("knownUuids")
    Set<UUID> adventure_power$getKnownUuids();

    @Accessor("callbacks")
    LevelCallback<?> adventure_power$getCallbacks();

    @Accessor("sectionStorage")
    EntitySectionStorage<?> adventure_power$getSectionStorage();

    @Accessor("visibleEntityStorage")
    EntityLookup<?> adventure_power$getVisibleEntityStorage();
}
