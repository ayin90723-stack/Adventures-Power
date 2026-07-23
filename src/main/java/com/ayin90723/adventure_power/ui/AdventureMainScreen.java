package com.ayin90723.adventure_power.ui;

import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.network.NetworkHandler;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.TriggerDef;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 冒险统一面板 - 按 P 键打开。
 * <p>
 * 顶部 3 个标签切换子面板：
 * <ol>
 *   <li>Buff 永驻 - 管理正面效果的永续/正常到期（原 Buff 管理面板）</li>
 *   <li>能力配置 - 管理被动能力开关（原能力管理面板，默认显示）</li>
 *   <li>冒险进度 - 查看里程碑解锁进度（原进度面板）</li>
 * </ol>
 * 合并原 BuffManagementScreen / AbilityManagementScreen / MilestoneProgressScreen 三个面板。
 */
public class AdventureMainScreen extends AbstractScrollableScreen {

    public enum Tab { BUFF, ABILITY, MILESTONE }

    private Tab currentTab;

    // ===== 能力 tab =====
    private final Set<String> disabledAbilities = new HashSet<>();
    private final List<Map.Entry<String, Component>> abilityEntries = new ArrayList<>();

    // ===== Buff tab =====
    private final Set<String> excludedEffects = new HashSet<>();
    private final List<MobEffectInstance> displayEffects = new ArrayList<>();
    private boolean ready = false;
    private boolean buffExtendEnabled = true;
    private int refreshTick = 0;

    // ===== 布局 =====
    private int leftX;
    private static final int PANEL_WIDTH = 220;
    private static final int TOP_Y = 44;
    private static final int BOTTOM_PADDING = 36;
    // 标签栏
    private static final int TAB_Y = 6;
    private static final int TAB_HEIGHT = 14;
    private static final int TAB_WIDTH = 80;
    private static final int TAB_GAP = 4;
    // 行高
    private static final int ROW_HEIGHT = 22;
    private static final int MILESTONE_ROW_HEIGHT = 24;
    // 颜色
    private static final int COLOR_GREEN = 0x55FF55;
    private static final int COLOR_GRAY = 0xAAAAAA;
    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final int COLOR_YELLOW = 0xFFFF55;
    private static final int COLOR_GOLD = 0xFFD700;
    private static final int COLOR_DARK = 0x666666;
    private static final int COLOR_BG = 0x88000000;

    public AdventureMainScreen() {
        this(Tab.ABILITY);
    }

    public AdventureMainScreen(Tab initialTab) {
        super(Component.translatable("screen.adventure_power.main"));
        this.currentTab = initialTab;
    }

    @Override
    protected void init() {
        super.init();
        this.leftX = this.width / 2 - PANEL_WIDTH / 2;
        // 打开即请求 Buff 黑名单（为切到 Buff tab 预备数据）
        NetworkHandler.sendBuffBlacklistRequest();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(p -> {
                this.buffExtendEnabled = p.isAbilityEnabled("perpetual_blessing");
            });
        }
        refreshCurrentTabData();
    }

    /** Buff 黑名单同步到达回调 */
    public void onSyncReceived(Set<String> blacklist) {
        this.excludedEffects.clear();
        if (blacklist != null) this.excludedEffects.addAll(blacklist);
        this.ready = true;
        refreshDisplayEffects();
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        this.scrollOffset = 0;
        refreshCurrentTabData();
    }

    private void refreshCurrentTabData() {
        switch (currentTab) {
            case ABILITY -> initAbilityData();
            case BUFF -> refreshDisplayEffects();
            case MILESTONE -> { }
        }
    }

    private void initAbilityData() {
        Minecraft mc = Minecraft.getInstance();
        disabledAbilities.clear();
        abilityEntries.clear();
        if (mc.player == null) return;
        mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
            disabledAbilities.addAll(progress.getDisabledAbilities());
        });
        for (var entry : AdventureProgressCapability.KNOWN_ABILITIES.entrySet()) {
            if (AdventureProgressCapability.isAbilityAvailable(mc.player, entry.getKey())) {
                abilityEntries.add(entry);
            }
        }
    }

    private void refreshDisplayEffects() {
        displayEffects.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                displayEffects.add(effect);
            }
        }
        displayEffects.sort((a, b) -> Integer.compare(b.getDuration(), a.getDuration()));
    }

    @Override
    protected int visibleHeight() { return this.height - TOP_Y - BOTTOM_PADDING; }

    @Override
    protected int contentHeight() {
        return switch (currentTab) {
            case ABILITY -> abilityEntries.size() * ROW_HEIGHT;
            case BUFF -> displayEffects.size() * ROW_HEIGHT;
            case MILESTONE -> MilestoneRegistry.getMilestoneCount() * MILESTONE_ROW_HEIGHT;
        };
    }

    @Override
    protected int rowHeight() {
        return currentTab == Tab.MILESTONE ? MILESTONE_ROW_HEIGHT : ROW_HEIGHT;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Buff tab 周期刷新效果列表
        refreshTick++;
        if (currentTab == Tab.BUFF && refreshTick % 20 == 0) {
            refreshDisplayEffects();
        }

        this.renderBackground(graphics);
        renderTabs(graphics, mouseX, mouseY);
        switch (currentTab) {
            case ABILITY -> renderAbilityTab(graphics, mouseX, mouseY);
            case BUFF -> renderBuffTab(graphics, mouseX, mouseY);
            case MILESTONE -> renderMilestoneTab(graphics, mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        Component[] labels = {
            Component.translatable("screen.adventure_power.tab.buff"),
            Component.translatable("screen.adventure_power.tab.ability"),
            Component.translatable("screen.adventure_power.tab.milestone")
        };
        Tab[] tabs = Tab.values();
        int totalWidth = 3 * TAB_WIDTH + 2 * TAB_GAP;
        int startX = (this.width - totalWidth) / 2;
        for (int i = 0; i < 3; i++) {
            int tx = startX + i * (TAB_WIDTH + TAB_GAP);
            boolean active = tabs[i] == currentTab;
            boolean hover = mouseX >= tx && mouseX <= tx + TAB_WIDTH
                && mouseY >= TAB_Y && mouseY <= TAB_Y + TAB_HEIGHT;
            int bg = active ? 0x66005500 : (hover ? 0x44FFFFFF : 0x33000000);
            graphics.fill(tx, TAB_Y, tx + TAB_WIDTH, TAB_Y + TAB_HEIGHT, bg);
            graphics.drawCenteredString(this.font, labels[i],
                tx + TAB_WIDTH / 2, TAB_Y + 3, active ? COLOR_GREEN : COLOR_GRAY);
        }
    }

    // ===== 能力 tab =====
    private void renderAbilityTab(GuiGraphics graphics, int mouseX, int mouseY) {
        if (abilityEntries.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal("§7暂无可用能力"), this.width / 2, this.height / 2, COLOR_GRAY);
            return;
        }
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
            if (mouseX >= leftX && mouseX <= leftX + PANEL_WIDTH
                && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
                graphics.fill(leftX, y - 1, leftX + PANEL_WIDTH, y + ROW_HEIGHT - 1, 0x22FFFFFF);
            }
            String dot = isDisabled ? "§7○" : "§a●";
            graphics.drawString(this.font, dot, leftX + 5, y, isDisabled ? COLOR_GRAY : COLOR_GREEN);
            graphics.drawString(this.font, name.getString(), leftX + 22, y, COLOR_WHITE);
            Component status = isDisabled
                ? Component.translatable("screen.adventure_power.disabled")
                : Component.translatable("screen.adventure_power.enabled");
            graphics.drawString(this.font, status.getString(), leftX + 140, y,
                isDisabled ? COLOR_GRAY : COLOR_GREEN);
        }
        renderScrollBar(graphics, leftX + PANEL_WIDTH + 4, TOP_Y);
        graphics.drawCenteredString(this.font,
            Component.literal("§7单击切换能力开关"), this.width / 2, this.height - 22, COLOR_GRAY);
    }

    // ===== Buff tab =====
    private void renderBuffTab(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!buffExtendEnabled) {
            graphics.drawCenteredString(this.font,
                Component.literal("§c⚠ Buff延长已在能力配置中关闭 - 以下设置不生效")
                    .withStyle(ChatFormatting.RED),
                this.width / 2, TOP_Y - 18, 0xFF5555);
        }
        if (!ready) {
            graphics.drawCenteredString(this.font,
                Component.literal("§7正在加载排除列表..."), this.width / 2, TOP_Y + 20, COLOR_GRAY);
            return;
        }
        if (displayEffects.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal("§7暂无正面效果可管理"), this.width / 2, this.height / 2, COLOR_GRAY);
            return;
        }
        graphics.drawString(this.font, "●/○", leftX, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, "效果", leftX + 22, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, "剩余", leftX + 100, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, "状态", leftX + 155, TOP_Y - 14, COLOR_GRAY);

        int visibleRows = visibleHeight() / ROW_HEIGHT;
        int startRow = Math.max(0, scrollOffset / ROW_HEIGHT);
        int endRow = Math.min(displayEffects.size(), startRow + visibleRows + 1);
        for (int i = startRow; i < endRow; i++) {
            MobEffectInstance effect = displayEffects.get(i);
            int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;
            String effectId = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()).toString();
            boolean isExcluded = excludedEffects.contains(effectId);
            if (mouseX >= leftX && mouseX <= leftX + PANEL_WIDTH
                && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
                graphics.fill(leftX, y - 1, leftX + PANEL_WIDTH, y + ROW_HEIGHT - 1, 0x22FFFFFF);
            }
            String dot = isExcluded ? "§7○" : "§a●";
            graphics.drawString(this.font, dot, leftX + 5, y, isExcluded ? COLOR_GRAY : COLOR_GREEN);
            String name = effect.getEffect().getDisplayName().getString();
            if (effect.getAmplifier() > 0) {
                name += " " + toRoman(effect.getAmplifier() + 1);
            }
            graphics.drawString(this.font, name, leftX + 22, y, COLOR_WHITE);
            int seconds = effect.getDuration() / 20;
            String timeStr = seconds >= 60
                ? String.format("%d:%02d", seconds / 60, seconds % 60)
                : String.format("0:%02d", seconds);
            graphics.drawString(this.font, timeStr, leftX + 100, y,
                effect.getDuration() < 60 ? COLOR_YELLOW : COLOR_WHITE);
            String status = isExcluded
                ? Component.literal("正常到期").getString()
                : Component.literal("永续").getString();
            graphics.drawString(this.font, status, leftX + 140, y,
                isExcluded ? COLOR_GRAY : COLOR_GREEN);
        }
        renderScrollBar(graphics, leftX + PANEL_WIDTH + 4, TOP_Y);
        graphics.drawCenteredString(this.font,
            Component.literal("§7单击切换效果排除状态"), this.width / 2, this.height - 22, COLOR_GRAY);
    }

    // ===== 里程碑 tab =====
    private void renderMilestoneTab(GuiGraphics graphics, int mouseX, int mouseY) {
        final int LEFT_X = 30;
        final int RIGHT_X = 220;
        int total = MilestoneRegistry.getMilestoneCount();
        graphics.drawString(this.font, Component.literal("进度").withStyle(s -> s.withColor(COLOR_GRAY)),
            LEFT_X, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.literal("解锁条件").withStyle(s -> s.withColor(COLOR_GRAY)),
            RIGHT_X, TOP_Y - 14, COLOR_GRAY);

        if (total == 0) {
            graphics.drawCenteredString(this.font, Component.literal("§7暂无里程碑"),
                this.width / 2, this.height / 2, COLOR_GRAY);
            return;
        }

        int visibleRows = visibleHeight() / MILESTONE_ROW_HEIGHT;
        int startRow = Math.max(0, scrollOffset / MILESTONE_ROW_HEIGHT);
        int endRow = Math.min(total, startRow + visibleRows + 1);

        Minecraft mc = Minecraft.getInstance();
        Optional<com.ayin90723.adventure_power.capability.IAdventureProgress> progressOpt =
            mc.player != null ? AdventureProgressCapability.getAdventureProgress(mc.player) : Optional.empty();
        var progress = progressOpt.orElse(null);

        for (int i = startRow; i < endRow; i++) {
            Milestone m = MilestoneRegistry.getAll().get(i);
            int y = TOP_Y + i * MILESTONE_ROW_HEIGHT - scrollOffset;
            boolean unlocked = progress != null && progress.isMilestoneUnlocked(m.id());
            graphics.fill(LEFT_X - 4, y - 1, this.width - LEFT_X + 4, y + MILESTONE_ROW_HEIGHT - 1, COLOR_BG);
            MutableComponent left = Component.literal(unlocked ? "✓ " : "✗ ")
                .withStyle(s -> s.withColor(unlocked ? COLOR_GREEN : COLOR_DARK));
            left.append(Component.literal(m.name())
                .withStyle(s -> s.withColor(unlocked ? COLOR_GREEN : COLOR_GRAY)));
            if (unlocked) {
                left.append(Component.literal("  §8->  "));
                List<String> ids = m.abilities();
                for (int j = 0; j < ids.size(); j++) {
                    if (j > 0) left.append(Component.literal(" §8· "));
                    Ability a = AbilityRegistry.get(ids.get(j));
                    if (a != null) left.append(a.name().copy().withStyle(s -> s.withColor(COLOR_WHITE)));
                }
            }
            graphics.drawString(this.font, left, LEFT_X, y + 4, COLOR_WHITE);
            String hint = getUnlockHint(m);
            graphics.drawString(this.font, Component.literal(hint).withStyle(s -> s.withColor(COLOR_GRAY)),
                RIGHT_X, y + 4, COLOR_GRAY);
        }

        if (progress != null) {
            int unlockedCount = 0;
            for (Milestone m : MilestoneRegistry.getAll()) {
                if (progress.isMilestoneUnlocked(m.id())) unlockedCount++;
            }
            graphics.drawCenteredString(this.font,
                Component.literal("已解锁: " + unlockedCount + " / " + total).withStyle(s -> s.withColor(COLOR_GOLD)),
                this.width / 2, this.height - 16, COLOR_GOLD);
        }
    }

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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 标签点击切换
            if (mouseY >= TAB_Y && mouseY <= TAB_Y + TAB_HEIGHT) {
                int totalWidth = 3 * TAB_WIDTH + 2 * TAB_GAP;
                int startX = (this.width - totalWidth) / 2;
                Tab[] tabs = Tab.values();
                for (int i = 0; i < 3; i++) {
                    int tx = startX + i * (TAB_WIDTH + TAB_GAP);
                    if (mouseX >= tx && mouseX <= tx + TAB_WIDTH) {
                        switchTab(tabs[i]);
                        return true;
                    }
                }
            }
            // 内容点击
            switch (currentTab) {
                case ABILITY -> { if (abilityTabClicked(mouseX, mouseY)) return true; }
                case BUFF -> { if (buffTabClicked(mouseX, mouseY)) return true; }
                default -> { }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean abilityTabClicked(double mouseX, double mouseY) {
        for (int i = 0; i < abilityEntries.size(); i++) {
            int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;
            if (y < TOP_Y - ROW_HEIGHT || y > TOP_Y + visibleHeight()) continue;
            if (mouseX >= leftX && mouseX <= leftX + PANEL_WIDTH
                && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
                String id = abilityEntries.get(i).getKey();
                if (disabledAbilities.contains(id)) disabledAbilities.remove(id);
                else disabledAbilities.add(id);
                NetworkHandler.sendAbilityToggle(id);
                return true;
            }
        }
        return false;
    }

    private boolean buffTabClicked(double mouseX, double mouseY) {
        for (int i = 0; i < displayEffects.size(); i++) {
            int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;
            if (y < TOP_Y - ROW_HEIGHT || y > TOP_Y + visibleHeight()) continue;
            if (mouseX >= leftX && mouseX <= leftX + PANEL_WIDTH
                && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
                MobEffectInstance effect = displayEffects.get(i);
                String effectId = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()).toString();
                NetworkHandler.sendBuffToggle(effectId);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static String toRoman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            default -> String.valueOf(n);
        };
    }
}
