package com.ayin90723.adventure_power.ui;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 主动技能 HUD 叠加层。
 * 左下角显示当前选中技能 + 冷却倒计时。切换后显示 3 秒淡出。
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class ActiveSkillHudOverlay {

    private static final int COOLDOWN_COLOR = 0xFF5555;
    private static final int READY_COLOR = 0x55FF55;
    /** 切换后显示持续 tick 数 */
    private static final long SWITCH_DISPLAY_TICKS = 60; // 3 秒
    /** 上次切换的游戏时间 */
    private static long lastSwitchTime = -SWITCH_DISPLAY_TICKS;

    /** 由 InputHandler 在切换时调用 */
    public static void onSkillSwitched(long gameTime) {
        lastSwitchTime = gameTime;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        var progressOpt = mc.player.getCapability(AdventureProgressCapability.CAPABILITY).resolve();
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        if (!progress.isFullyUnlocked()) return;
        if (!progress.isAbilityEnabled("active_skill")) return;

        long currentTime = mc.level.getGameTime();
        // 非切换后 3 秒内且无技能冷却中 → 不显示
        boolean recentSwitch = (currentTime - lastSwitchTime) < SWITCH_DISPLAY_TICKS;
        long judgmentCd = progress.getJudgmentCooldownEnd();
        long sanctuaryCd = progress.getSanctuaryCooldownEnd();
        boolean anyCooldown = (judgmentCd > 0 && currentTime < judgmentCd)
                            || (sanctuaryCd > 0 && currentTime < sanctuaryCd);
        if (!recentSwitch && !anyCooldown) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 10;
        int y = graphics.guiHeight() - 50;

        // 审判行
        renderSkillRow(graphics, mc, x, y,
            Component.translatable("skill.adventure_power.judgment"),
            judgmentCd, currentTime, progress.getActiveSkillIndex() == 0);

        // 庇护行
        renderSkillRow(graphics, mc, x, y + 14,
            Component.translatable("skill.adventure_power.sanctuary"),
            sanctuaryCd, currentTime, progress.getActiveSkillIndex() == 1);
    }

    private static void renderSkillRow(GuiGraphics graphics, Minecraft mc, int x, int y,
                                        Component name, long cdEnd, long currentTime, boolean selected) {
        String prefix = selected ? "> " : "  ";
        boolean onCooldown = cdEnd > 0 && currentTime < cdEnd;
        int remainingTicks = onCooldown ? (int) (cdEnd - currentTime) : 0;
        int remainingSeconds = (remainingTicks + 20) / 20;
        int color = onCooldown ? COOLDOWN_COLOR : READY_COLOR;

        String text;
        if (onCooldown) {
            text = prefix + name.getString() + " [" + remainingSeconds + "s]";
        } else {
            text = prefix + name.getString() + " " + Component.translatable("skill.adventure_power.ready").getString();
        }
        graphics.drawString(mc.font, text, x, y, color);
    }
}
