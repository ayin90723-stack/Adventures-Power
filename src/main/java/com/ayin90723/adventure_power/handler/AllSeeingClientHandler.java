package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ui.ClientHudDataCache;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 全视之眼 - 客户端去雾。
 * <p>
 * 读 {@link ClientHudDataCache#allSeeingEnabled}，启用时把雾平面推远到极值，
 * 消除下界/末地/水下等近距离雾。每帧触发，故读缓存避免 getCapability。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = AdventurePower.MODID, bus = Bus.FORGE)
public class AllSeeingClientHandler {

    /** 推远的雾平面距离（足够远以视觉上消除近距离雾） */
    private static final float FAR_PLANE = 4096.0F;

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!ClientHudDataCache.allSeeingEnabled) return;
        if (event.getFarPlaneDistance() < FAR_PLANE) {
            event.setFarPlaneDistance(FAR_PLANE);
        }
        event.setNearPlaneDistance(0.0F);
    }
}
