package com.ayin90723.adventure_power.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 可滚动的 UI 屏幕基类。
 * <p>
 * 封装了垂直滚动所需的公共字段、滚动计算、鼠标滚轮事件和滚动条渲染。
 * 子类只需实现 {@link #contentHeight()}、{@link #visibleHeight()} 和 {@link #rowHeight()} 三个抽象方法，
 * 并在合适的位置调用 {@link #renderScrollBar(GuiGraphics, int, int)} 即可。
 * </p>
 */
public abstract class AbstractScrollableScreen extends Screen {

    /** 当前滚动偏移（像素），0 = 顶部 */
    protected int scrollOffset;

    protected AbstractScrollableScreen(Component title) {
        super(title);
    }

    /**
     * @return 内容总高度（像素）
     */
    protected abstract int contentHeight();

    /**
     * @return 可见区域高度（像素）
     */
    protected abstract int visibleHeight();

    /**
     * @return 每行高度（像素），用于滚轮滚动速度
     */
    protected abstract int rowHeight();

    /**
     * @return 最大滚动偏移
     */
    protected int maxScroll() {
        return Math.max(0, contentHeight() - visibleHeight());
    }

    /** 将 scrollOffset 限制在合法范围内 */
    protected void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        scrollOffset -= (int) (scrollDelta * rowHeight());
        clampScroll();
        return true;
    }

    /**
     * 渲染垂直滚动条。
     *
     * @param graphics 绘图上下文
     * @param barX     滚动条 X 坐标
     * @param topY     滚动条顶部 Y 坐标
     */
    protected void renderScrollBar(GuiGraphics graphics, int barX, int topY) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return;
        int barHeight = visibleHeight();
        int thumbHeight = Math.max(16, barHeight * visibleHeight() / Math.max(1, contentHeight()));
        int thumbY = topY + (int) ((barHeight - thumbHeight) * (float) scrollOffset / maxScroll);
        graphics.fill(barX, topY, barX + 4, topY + barHeight, 0x44FFFFFF);
        graphics.fill(barX, thumbY, barX + 4, thumbY + thumbHeight, 0xAAFFFFFF);
    }
}