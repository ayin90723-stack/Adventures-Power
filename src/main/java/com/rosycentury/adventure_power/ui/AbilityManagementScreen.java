package com.rosycentury.adventure_power.ui;

import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.network.NetworkHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 冒险之力管理菜单 — 按 P 键打开，管理已解锁的被动能力。
 * 默认所有能力启用（●），单击切换为禁用（○）。
 * 能力列表由 AdventureProgressCapability.KNOWN_ABILITIES 驱动。
 * <p>
 * 当能力数量超出屏幕时自动启用垂直滚动。
 */
public class AbilityManagementScreen extends Screen {
    /** 禁用的能力 ID（客户端缓存，乐观更新 + 服务端确认） */
    private Set<String> disabledAbilities = new HashSet<>();
    /** (abilityId → displayName) 驱动列表显示 */
    private List<Map.Entry<String, Component>> abilityEntries = new ArrayList<>();
    private int leftX;
    private static final int PANEL_WIDTH = 220;
    private static final int ROW_HEIGHT = 22;
    private static final int TOP_Y = 40;
    private static final int BOTTOM_PADDING = 36;
    private static final int COLOR_GREEN = 0x55FF55;
    private static final int COLOR_GRAY = 0xAAAAAA;
    private static final int COLOR_WHITE = 0xFFFFFF;

    /** 当前滚动偏移（像素），0 = 顶部 */
    private int scrollOffset;

    public AbilityManagementScreen() {
        super(Component.translatable("screen.adventure_power.ability"));
    }

    @Override
    protected void init() {
        super.init();
        this.leftX = this.width / 2 - PANEL_WIDTH / 2;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
                this.disabledAbilities = new HashSet<>(progress.getDisabledAbilities());
            });
        }
        abilityEntries = new ArrayList<>();
        for (var entry : AdventureProgressCapability.KNOWN_ABILITIES.entrySet()) {
            if (AdventureProgressCapability.isAbilityAvailable(mc.player, entry.getKey())) {
                abilityEntries.add(entry);
            }
        }
    }

    /** 可视区域高度（不含标题和底部提示） */
    private int visibleHeight() {
        return this.height - TOP_Y - BOTTOM_PADDING;
    }

    /** 内容总高度 */
    private int contentHeight() {
        return abilityEntries.size() * ROW_HEIGHT;
    }

    /** 最大滚动偏移 */
    private int maxScroll() {
        return Math.max(0, contentHeight() - visibleHeight());
    }

    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        scrollOffset -= (int)(scrollDelta * ROW_HEIGHT);
        clampScroll();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // 标题
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, COLOR_WHITE);

        if (abilityEntries.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal("§7暂无可用能力"),
                this.width / 2, this.height / 2, COLOR_GRAY);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        // 列标题（固定位置）
        graphics.drawString(this.font, "●/○", leftX, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.literal("能力"), leftX + 22, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.literal("状态"), leftX + 155, TOP_Y - 14, COLOR_GRAY);

        int visibleRows = visibleHeight() / ROW_HEIGHT;
        int startRow = Math.max(0, scrollOffset / ROW_HEIGHT);
        int endRow = Math.min(abilityEntries.size(), startRow + visibleRows + 1);

        for (int i = startRow; i < endRow; i++) {
            var entry = abilityEntries.get(i);
            String id = entry.getKey();
            Component name = entry.getValue();
            int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;

            boolean isDisabled = disabledAbilities.contains(id);

            // 鼠标悬浮高亮
            if (mouseX >= leftX && mouseX <= leftX + PANEL_WIDTH
                  && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
                graphics.fill(leftX, y - 1, leftX + PANEL_WIDTH, y + ROW_HEIGHT - 1, 0x22FFFFFF);
            }

            // 绿点/灰点
            String dot = isDisabled ? "§7○" : "§a●";
            graphics.drawString(this.font, dot, leftX + 5, y, isDisabled ? COLOR_GRAY : COLOR_GREEN);

            // 能力名称
            graphics.drawString(this.font, name.getString(), leftX + 22, y, COLOR_WHITE);

            // 状态文字
            Component status = isDisabled
                ? Component.translatable("screen.adventure_power.disabled")
                : Component.translatable("screen.adventure_power.enabled");
            graphics.drawString(this.font, status.getString(), leftX + 140, y,
                isDisabled ? COLOR_GRAY : COLOR_GREEN);
        }

        // 滚动条
        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int barX = leftX + PANEL_WIDTH + 4;
            int barHeight = visibleHeight();
            int thumbHeight = Math.max(16, barHeight * visibleHeight() / contentHeight());
            int thumbY = TOP_Y + (int)((barHeight - thumbHeight) * (float)scrollOffset / maxScroll);
            graphics.fill(barX, TOP_Y, barX + 4, TOP_Y + barHeight, 0x44FFFFFF);
            graphics.fill(barX, thumbY, barX + 4, thumbY + thumbHeight, 0xAAFFFFFF);
        }

        // 底部提示（固定位置）
        graphics.drawCenteredString(this.font,
            Component.literal("§7单击切换能力开关"),
            this.width / 2, this.height - 22, COLOR_GRAY);
        graphics.drawCenteredString(this.font,
            Component.literal("§7按 O 键打开 Buff 管理面板"),
            this.width / 2, this.height - 10, COLOR_GRAY);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) { // 左键
            for (int i = 0; i < abilityEntries.size(); i++) {
                int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;
                if (y < TOP_Y - ROW_HEIGHT || y > TOP_Y + visibleHeight()) continue;
                if (mouseX >= leftX && mouseX <= leftX + PANEL_WIDTH
                      && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
                    String id = abilityEntries.get(i).getKey();
                    // 乐观更新
                    if (disabledAbilities.contains(id)) {
                        disabledAbilities.remove(id);
                    } else {
                        disabledAbilities.add(id);
                    }
                    NetworkHandler.sendAbilityToggle(id);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
