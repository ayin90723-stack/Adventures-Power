package com.ayin90723.adventure_power.ui;

import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.TriggerDef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * 冒险进度界面 — 按 M 键打开。
 * 左侧显示里程碑名称和解锁状态，右侧显示解锁提示。
 */
public class MilestoneProgressScreen extends Screen {

    private static final int LEFT_X = 30;
    private static final int RIGHT_X = 220;
    private static final int ROW_HEIGHT = 24;
    private static final int TOP_Y = 40;
    private static final int BOTTOM_PADDING = 30;
    private static final int COLOR_GOLD = 0xFFD700;
    private static final int COLOR_GREEN = 0x55FF55;
    private static final int COLOR_GRAY = 0xAAAAAA;
    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final int COLOR_DARK = 0x666666;
    private static final int COLOR_BG = 0x88000000;

    private int scrollOffset;

    public MilestoneProgressScreen() {
        super(Component.translatable("screen.adventure_power.milestone_progress"));
    }

    private int visibleHeight() { return this.height - TOP_Y - BOTTOM_PADDING; }
    private int contentHeight() { return MilestoneRegistry.getMilestoneCount() * ROW_HEIGHT; }
    private int maxScroll() { return Math.max(0, contentHeight() - visibleHeight()); }

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
        graphics.drawCenteredString(this.font,
            Component.translatable("screen.adventure_power.milestone_progress"),
            this.width / 2, 12, COLOR_GOLD);

        // 列标题
        graphics.drawString(this.font, Component.literal("进度").withStyle(s -> s.withColor(COLOR_GRAY)),
            LEFT_X, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.literal("解锁条件").withStyle(s -> s.withColor(COLOR_GRAY)),
            RIGHT_X, TOP_Y - 14, COLOR_GRAY);

        int total = MilestoneRegistry.getMilestoneCount();
        if (total == 0) {
            graphics.drawCenteredString(this.font, Component.literal("暂无里程碑"),
                this.width / 2, this.height / 2, COLOR_GRAY);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        int visibleRows = visibleHeight() / ROW_HEIGHT;
        int startRow = Math.max(0, scrollOffset / ROW_HEIGHT);
        int endRow = Math.min(total, startRow + visibleRows + 1);

        Minecraft mc = Minecraft.getInstance();
        IAdventureProgress progress = null;
        if (mc.player != null) {
            var opt = AdventureProgressCapability.getAdventureProgress(mc.player);
            progress = opt.orElse(null);
        }

        for (int i = startRow; i < endRow; i++) {
            Milestone m = MilestoneRegistry.getAll().get(i);
            int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;
            boolean unlocked = progress != null && progress.isMilestoneUnlocked(m.id());

            // 行背景
            graphics.fill(LEFT_X - 4, y - 1, this.width - LEFT_X + 4, y + ROW_HEIGHT - 1, COLOR_BG);

            // 左侧：状态 + 名称 + 能力
            MutableComponent left = Component.literal(unlocked ? "✓ " : "✗ ")
                .withStyle(s -> s.withColor(unlocked ? COLOR_GREEN : COLOR_DARK));
            left.append(Component.literal(m.name())
                .withStyle(s -> s.withColor(unlocked ? COLOR_GREEN : COLOR_GRAY)));

            if (unlocked) {
                left.append(Component.literal("  §8→  "));
                List<String> ids = m.abilities();
                for (int j = 0; j < ids.size(); j++) {
                    if (j > 0) left.append(Component.literal(" §8· "));
                    Ability a = AbilityRegistry.get(ids.get(j));
                    if (a != null) left.append(a.name().copy().withStyle(s -> s.withColor(COLOR_WHITE)));
                }
            }
            graphics.drawString(this.font, left, LEFT_X, y + 4, COLOR_WHITE);

            // 右侧：解锁条件提示
            String hint = getUnlockHint(m);
            graphics.drawString(this.font, Component.literal(hint).withStyle(s -> s.withColor(COLOR_GRAY)),
                RIGHT_X, y + 4, COLOR_GRAY);
        }

        // 底部统计
        if (progress != null) {
            int unlockedCount = 0;
            for (Milestone m : MilestoneRegistry.getAll()) {
                if (progress.isMilestoneUnlocked(m.id())) unlockedCount++;
            }
            graphics.drawCenteredString(this.font,
                Component.literal("已解锁: " + unlockedCount + " / " + total).withStyle(s -> s.withColor(COLOR_GOLD)),
                this.width / 2, this.height - 16, COLOR_GOLD);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 根据里程碑的 trigger 或 advancement 生成人类可读的解锁提示 */
    private static String getUnlockHint(Milestone m) {
        if (m.trigger() != null) {
            String hint = triggerHint(m.trigger());
            if (hint != null) return hint;
        }
        if (m.advancement() != null) {
            return "完成成就: " + m.advancement().toString();
        }
        return "未知条件";
    }

    private static String triggerHint(TriggerDef t) {
        return switch (t.type()) {
            case "survive_night" -> "度过第一个夜晚";
            case "first_death" -> "首次死亡";
            case "first_trade" -> "与村民交易";
            case "y_below" -> "深入地下 (Y<" + (t.y() != null ? t.y() : 0) + ")";
            case "first_kill" -> "击杀 " + (t.entity() != null ? t.entity().getPath() : "目标生物");
            default -> null;
        };
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
