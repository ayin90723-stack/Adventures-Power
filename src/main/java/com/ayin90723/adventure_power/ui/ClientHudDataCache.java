package com.ayin90723.adventure_power.ui;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 客户端 HUD 数据快照 - 每客户端 tick 末从 Capability 抓取一次 HUD 所需字段，
 * HUD 渲染时读快照，避免每帧 {@code getCapability} 查找。
 * <p>
 * 字段仅在客户端渲染/tick 线程访问，无需同步。玩家为 null 时（菜单/断线）字段归零。
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class ClientHudDataCache {

    // ===== 主动技能 HUD =====
    public static boolean activeSkillReady;   // (adventurer||fullyUnlocked) && abilityEnabled("active_skill")
    public static int activeSkillIndex;
    public static long judgmentCdEnd;
    public static long sanctuaryCdEnd;

    // ===== 死亡抗拒 HUD =====
    public static boolean deathDefyEnabled;   // abilityEnabled("death_defy")
    public static long deathDefyInvulEnd;

    /** 当前游戏时间（HUD 渲染读此值，避免每帧取 level.getGameTime） */
    public static long currentGameTime;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            activeSkillReady = false;
            activeSkillIndex = 0;
            judgmentCdEnd = 0;
            sanctuaryCdEnd = 0;
            deathDefyEnabled = false;
            deathDefyInvulEnd = 0;
            currentGameTime = 0;
            return;
        }
        currentGameTime = mc.level.getGameTime();
        mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(p -> {
            activeSkillReady = (p.isAdventurer() || p.isFullyUnlocked()) && p.isAbilityEnabled("active_skill");
            activeSkillIndex = p.getActiveSkillIndex();
            judgmentCdEnd = p.getJudgmentCooldownEnd();
            sanctuaryCdEnd = p.getSanctuaryCooldownEnd();
            deathDefyEnabled = p.isAbilityEnabled("death_defy");
            deathDefyInvulEnd = p.getDeathDefyInvulEnd();
        });
    }
}
