package com.ayin90723.adventure_power.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 全视之眼觉醒 - 威胁雷达 HUD。
 * <p>
 * 右下角紧贴边角，仅显示「方位 距离m」（如「右上 8m」），半透明不抢视野。
 * 读 {@link ClientHudDataCache#radarTargets}（客户端限频扫描缓存）。
 * 仅觉醒（fullyUnlocked）且全视之眼启用时渲染。
 * <p>
 * 审查修 P2#1：改订阅 {@link RenderGuiEvent.Post}（每帧一次）——原
 * RenderGuiOverlayEvent.Post 对 26 个原版 overlay 各发一次，半透明行色被叠加成近纯白。
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class AllSeeingRadarOverlay {

    /** 半透明行色，不抢视野 */
    private static final int ROW_COLOR = 0x55FFFFFF;
    private static final int ROW_HEIGHT = 9;
    private static final int RIGHT_MARGIN = 2;
    private static final int BOTTOM_MARGIN = 4;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!ClientHudDataCache.allSeeingEnabled || !ClientHudDataCache.fullyUnlocked) return;
        if (ClientHudDataCache.radarTargets.isEmpty()) return;

        GuiGraphics g = event.getGuiGraphics();
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        int count = ClientHudDataCache.radarTargets.size();

        // 右下角紧贴边角向上排
        int y = screenH - BOTTOM_MARGIN - count * ROW_HEIGHT;
        for (int i = 0; i < count; i++) {
            ClientHudDataCache.RadarTarget t = ClientHudDataCache.radarTargets.get(i);
            String text = t.arrow + " " + t.name + " " + t.distance + "m";
            int textW = mc.font.width(text);
            int rowX = screenW - RIGHT_MARGIN - textW;
            g.drawString(mc.font, text, rowX, y + i * ROW_HEIGHT, ROW_COLOR);
        }
    }
}
