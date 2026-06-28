package com.rosycentury.adventure_power.ui;

import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 死亡抗拒 HUD 叠加层。
 * 无敌期间渲染金色边框 + 中上方倒计时。
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class DeathDefyHudOverlay {

    /** 边框宽度（像素） */
    private static final int BORDER_WIDTH = 2;
    /** 淡金色（ARGB: Alpha=80 约31%不透明，纯金） */
    private static final int GOLD_COLOR = 0x50FFD700;
    /** 倒计时文本颜色 */
    private static final int TEXT_COLOR = 0xAAFFD700;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        long currentTime = mc.level.getGameTime();
        var progressOpt = mc.player.getCapability(AdventureProgressCapability.CAPABILITY).resolve();
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        if (!progress.isAbilityEnabled("death_defy")) return;

        long invulEnd = progress.getDeathDefyInvulEnd();
        if (invulEnd <= 0 || currentTime >= invulEnd) return;

        int remainingTicks = (int) (invulEnd - currentTime);
        int remainingSeconds = (remainingTicks + 20) / 20; // 向上取整

        GuiGraphics graphics = event.getGuiGraphics();
        int screenW = graphics.guiWidth();
        int screenH = graphics.guiHeight();

        // 金色边框
        renderGoldBorder(graphics, screenW, screenH);

        // 中上方倒计时
        graphics.drawCenteredString(mc.font, "死亡抗拒 " + remainingSeconds + "s",
            screenW / 2, screenH / 6, TEXT_COLOR);
    }

    private static void renderGoldBorder(GuiGraphics graphics, int screenW, int screenH) {
        // 上
        graphics.fill(0, 0, screenW, BORDER_WIDTH, GOLD_COLOR);
        // 下
        graphics.fill(0, screenH - BORDER_WIDTH, screenW, screenH, GOLD_COLOR);
        // 左
        graphics.fill(0, BORDER_WIDTH, BORDER_WIDTH, screenH - BORDER_WIDTH, GOLD_COLOR);
        // 右
        graphics.fill(screenW - BORDER_WIDTH, BORDER_WIDTH, screenW, screenH - BORDER_WIDTH, GOLD_COLOR);
    }
}
