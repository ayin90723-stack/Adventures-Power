package com.ayin90723.adventure_power.ui;

import com.ayin90723.adventure_power.ui.ClientHudDataCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
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
    // 技能名/就绪文案为固定 key 的 TranslatableComponent（延迟到 getString 时解析，
    // 静态缓存避免 HUD 可见期间每帧构建新对象）
    private static final Component JUDGMENT_LABEL = Component.translatable("skill.adventure_power.judgment");
    private static final Component SANCTUARY_LABEL = Component.translatable("skill.adventure_power.sanctuary");
    private static final Component READY_LABEL = Component.translatable("skill.adventure_power.ready");
    /** 上次切换的游戏时间 */
    private static long lastSwitchTime = -SWITCH_DISPLAY_TICKS;
    /** 上次渲染时所在的 level（进入新世界后重置切换标记，防止上一世界残留的
     *  lastSwitchTime 让新世界前 3 秒误显示技能条） */
    private static net.minecraft.world.level.Level lastRenderLevel;

    /** 由 InputHandler 在切换时调用 */
    public static void onSkillSwitched(long gameTime) {
        lastSwitchTime = gameTime;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            // 断线/回主菜单：清空 level 引用，否则其引用的区块/实体大对象图无法 GC
            lastRenderLevel = null;
            return;
        }

        // 跨世界重置：level 实例变化（进新世界/换服）时清掉上一世界的切换标记
        if (mc.level != lastRenderLevel) {
            lastRenderLevel = mc.level;
            lastSwitchTime = -SWITCH_DISPLAY_TICKS;
        }

        if (!ClientHudDataCache.activeSkillReady) return;

        long currentTime = ClientHudDataCache.currentGameTime;
        // 非切换后 3 秒内且无技能冷却中 → 不显示
        boolean recentSwitch = (currentTime - lastSwitchTime) < SWITCH_DISPLAY_TICKS;
        long judgmentCd = ClientHudDataCache.judgmentCdEnd;
        long sanctuaryCd = ClientHudDataCache.sanctuaryCdEnd;
        boolean anyCooldown = (judgmentCd > 0 && currentTime < judgmentCd)
                            || (sanctuaryCd > 0 && currentTime < sanctuaryCd);
        if (!recentSwitch && !anyCooldown) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 10;
        int y = graphics.guiHeight() - 50;

        // 审判行
        renderSkillRow(graphics, mc, x, y,
            JUDGMENT_LABEL,
            judgmentCd, currentTime, ClientHudDataCache.activeSkillIndex == 0);

        // 庇护行
        renderSkillRow(graphics, mc, x, y + 14,
            SANCTUARY_LABEL,
            sanctuaryCd, currentTime, ClientHudDataCache.activeSkillIndex == 1);
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
            text = prefix + name.getString() + " " + READY_LABEL.getString();
        }
        graphics.drawString(mc.font, text, x, y, color);
    }
}
