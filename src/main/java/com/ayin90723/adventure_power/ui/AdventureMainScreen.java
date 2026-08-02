package com.ayin90723.adventure_power.ui;

import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.ability.SoulQuenchAbility;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.network.NetworkHandler;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.TriggerDef;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final List<String> abilityNames = new ArrayList<>();

    // ===== Buff tab =====
    private final Set<String> excludedEffects = new HashSet<>();
    private final List<MobEffectInstance> displayEffects = new ArrayList<>();
    private final List<String> displayEffectNames = new ArrayList<>();
    /** 预计算的剩余时间/状态文本（refreshDisplayEffects 时更新，避免每帧 String.format 与对象创建） */
    private final List<String> displayEffectTimes = new ArrayList<>();
    private final List<String> displayEffectStatuses = new ArrayList<>();
    private boolean ready = false;
    private boolean buffExtendEnabled = true;
    private int refreshTick = 0;

    // ===== 里程碑 tab 缓存（按 version 失效，避免每帧 AbilityRegistry.get + 遍历计数） =====
    private static int cachedMilestoneVersion = -1;
    private static List<List<Component>> milestoneAbilityNames = List.of();
    /** 标签 Component，init 时构建（语言切换重开即更新） */
    private Component[] tabLabels = new Component[0];

    /** 里程碑 tab 的 progress 快照（每 20 tick 刷新一次，避免每帧 capability resolve） */
    private Optional<com.ayin90723.adventure_power.capability.IAdventureProgress> milestoneProgress = Optional.empty();
    private long milestoneProgressRefreshTick = -1;

    /** 能力 tab hover 信息框的 progress 快照（每 20 tick 刷新，避免每帧 capability resolve） */
    private Optional<IAdventureProgress> abilityHoverProgress = Optional.empty();
    private long abilityHoverProgressTick = -1;
    /** 悬停信息框内容缓存（按能力 ID；随 20 tick 快照基准失效）——避免悬停期间每帧 wrapText/格式化 */
    private final Map<String, InfoBoxCacheEntry> infoBoxCache = new HashMap<>();

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
        this.tabLabels = new Component[] {
            Component.translatable("screen.adventure_power.tab.buff"),
            Component.translatable("screen.adventure_power.tab.ability"),
            Component.translatable("screen.adventure_power.tab.milestone")
        };
        ensureMilestoneAbilityCache();
        // 打开即请求 Buff 黑名单（为切到 Buff tab 预备数据）
        NetworkHandler.sendBuffBlacklistRequest();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(p -> {
                this.buffExtendEnabled = p.isAbilityEnabled(AbilityIds.PERPETUAL_BLESSING);
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
        abilityNames.clear();
        if (mc.player == null) return;
        mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
            disabledAbilities.addAll(progress.getDisabledAbilities());
        });
        for (var entry : AdventureProgressCapability.KNOWN_ABILITIES.entrySet()) {
            if (AdventureProgressCapability.isAbilityAvailable(mc.player, entry.getKey())) {
                abilityEntries.add(entry);
                abilityNames.add(entry.getValue().getString());
            }
        }
    }

    private void refreshDisplayEffects() {
        displayEffects.clear();
        displayEffectNames.clear();
        displayEffectTimes.clear();
        displayEffectStatuses.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                displayEffects.add(effect);
            }
        }
        displayEffects.sort((a, b) -> Integer.compare(b.getDuration(), a.getDuration()));
        // 预计算显示名（含罗马数字）、剩余时间与状态文本，避免每帧格式化与对象创建
        for (MobEffectInstance effect : displayEffects) {
            String name = effect.getEffect().getDisplayName().getString();
            if (effect.getAmplifier() > 0) {
                name += " " + toRoman(effect.getAmplifier() + 1);
            }
            displayEffectNames.add(name);
            displayEffectTimes.add(formatDuration(effect.getDuration()));
            String effectId = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()).toString();
            boolean excluded = excludedEffects.contains(effectId);
            displayEffectStatuses.add(excluded
                ? Component.translatable("screen.adventure_power.status_expire").getString()
                : Component.translatable("screen.adventure_power.status_perpetual").getString());
        }
    }

    /** 格式化效果剩余时间；无限时长（-1）显示 ∞ */
    private static String formatDuration(int duration) {
        if (duration < 0) return "∞";
        int seconds = duration / 20;
        return seconds >= 60
            ? String.format("%d:%02d", seconds / 60, seconds % 60)
            : String.format("0:%02d", seconds);
    }

    /** 重建里程碑能力名缓存（按 version 失效，/reload 或客户端同步后惰性重建） */
    private static void ensureMilestoneAbilityCache() {
        if (cachedMilestoneVersion == MilestoneRegistry.getVersion()) return;
        cachedMilestoneVersion = MilestoneRegistry.getVersion();
        List<List<Component>> built = new ArrayList<>();
        for (Milestone m : MilestoneRegistry.getAll()) {
            List<Component> names = new ArrayList<>();
            for (String id : m.abilities()) {
                Ability a = AbilityRegistry.get(id);
                if (a != null) names.add(a.name());
            }
            built.add(List.copyOf(names));
        }
        milestoneAbilityNames = List.copyOf(built);
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

    /** 滚动条 X 坐标（右侧固定位置，统一能力/Buff tab） */
    private int getScrollBarX() {
        return leftX + PANEL_WIDTH + 4;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 能力/Buff tab 周期刷新（面板打开期间里程碑解锁/能力开关变更时列表与开关状态需跟进。
        // 注意：AdventureSyncPacket 只更新 capability，不触达本屏幕——能力 tab 的
        // 权威状态刷新完全依赖这里的 20-tick 兜底重建）
        refreshTick++;
        if (refreshTick % 20 == 0) {
            if (currentTab == Tab.BUFF) {
                refreshDisplayEffects();
            } else if (currentTab == Tab.ABILITY) {
                initAbilityData();
            }
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
        Component[] labels = this.tabLabels;
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
                Component.translatable("screen.adventure_power.no_abilities"), this.width / 2, this.height / 2, COLOR_GRAY);
            return;
        }
        graphics.drawString(this.font, "●/○", leftX, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.translatable("screen.adventure_power.header_ability"), leftX + 22, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.translatable("screen.adventure_power.header_status"), leftX + 155, TOP_Y - 14, COLOR_GRAY);

        int visibleRows = visibleHeight() / ROW_HEIGHT;
        int startRow = Math.max(0, scrollOffset / ROW_HEIGHT);
        int endRow = Math.min(abilityEntries.size(), startRow + visibleRows + 1);
        String hoveredAbilityId = null;
        for (int i = startRow; i < endRow; i++) {
            var entry = abilityEntries.get(i);
            String id = entry.getKey();
            String name = abilityNames.get(i);
            int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;
            boolean isDisabled = disabledAbilities.contains(id);
            if (mouseX >= leftX && mouseX <= leftX + PANEL_WIDTH
                && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
                graphics.fill(leftX, y - 1, leftX + PANEL_WIDTH, y + ROW_HEIGHT - 1, 0x22FFFFFF);
                hoveredAbilityId = id;
            }
            String dot = isDisabled ? "§7○" : "§a●";
            graphics.drawString(this.font, dot, leftX + 5, y, isDisabled ? COLOR_GRAY : COLOR_GREEN);
            graphics.drawString(this.font, name, leftX + 22, y, COLOR_WHITE);
            Component status = isDisabled
                ? Component.translatable("screen.adventure_power.disabled")
                : Component.translatable("screen.adventure_power.enabled");
            graphics.drawString(this.font, status.getString(), leftX + 140, y,
                isDisabled ? COLOR_GRAY : COLOR_GREEN);
        }
        renderScrollBar(graphics, getScrollBarX(), TOP_Y);
        // 悬停能力条目 -> 侧边显示效果 / 当前数值 / 觉醒效果
        if (hoveredAbilityId != null) {
            renderAbilityInfoBox(graphics, hoveredAbilityId);
        }
        graphics.drawCenteredString(this.font,
            Component.translatable("screen.adventure_power.toggle_ability_hint"), this.width / 2, this.height - 22, COLOR_GRAY);
    }

    // ===== 能力 hover 侧边信息框 =====

    private static final int INFO_BOX_MAX_WIDTH = 200;
    /** 侧边可用空间低于此值时不放侧边（右不足移左侧，左也不足则缩窄贴右） */
    private static final int MIN_INFO_BOX_SIDE = 140;
    private static final int INFO_BOX_PADDING = 6;
    /** 行间距 = 字体行高 + 1 */
    private static final int INFO_BOX_LINE_SPACING = 10;

    /** 渲染能力悬停信息框（面板右侧固定位置）：能力名/描述/当前数值/觉醒效果 */
    private void renderAbilityInfoBox(GuiGraphics graphics, String abilityId) {
        Ability ability = AbilityRegistry.get(abilityId);
        if (ability == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            long tick = mc.level.getGameTime() / 20;
            if (abilityHoverProgressTick != tick) {
                abilityHoverProgressTick = tick;
                abilityHoverProgress = mc.player != null
                    ? AdventureProgressCapability.getAdventureProgress(mc.player) : Optional.empty();
                infoBoxCache.clear(); // 快照基准变化 -> 悬停内容缓存失效（内容依赖 progress/开关状态）
            }
        } else {
            // level 为空（维度切换/重连窄窗口）：清空快照与缓存并重置 tick 基准——
            // 否则过渡结束后若落入同一 /20 桶，旧快照/旧缓存仍残留
            abilityHoverProgress = Optional.empty();
            abilityHoverProgressTick = -1;
            infoBoxCache.clear();
        }
        var progress = abilityHoverProgress.orElse(null);

        // 内容缓存：悬停期间每帧渲染，wrapText/String.format/I18n.get 全部预计算一次
        //（与 Buff tab 的 displayEffectTimes 预计算同一模式，避免每帧字符级测量）
        InfoBoxCacheEntry cached = infoBoxCache.get(abilityId);
        if (cached == null) {
            cached = buildInfoBoxContent(ability, progress);
            infoBoxCache.put(abilityId, cached);
        }

        // ---- 位置与宽度：内容自然宽度来自缓存，再决定框宽（自适应不撑满） ----
        int rightSpace = this.width - (leftX + PANEL_WIDTH + 10) - 8;
        int leftSpace = leftX - 10 - 8;
        int naturalWidth = cached.naturalWidth;

        int sideX;
        int boxWidth;
        if (rightSpace >= MIN_INFO_BOX_SIDE) {
            sideX = leftX + PANEL_WIDTH + 10;
            boxWidth = Math.min(naturalWidth, rightSpace);
        } else if (leftSpace >= MIN_INFO_BOX_SIDE) {
            boxWidth = Math.min(naturalWidth, leftSpace);
            boxWidth = Math.max(boxWidth, 80); // 先抬下限再算 sideX，防右缘越过 leftX
            sideX = leftX - 10 - boxWidth;
        } else {
            boxWidth = Math.min(naturalWidth, Math.max(60, rightSpace));
            boxWidth = Math.max(boxWidth, 80); // 下限保证不塌成一条（须在算 sideX 前，否则右缘溢出屏幕）
            sideX = this.width - boxWidth - 8;
        }
        if (rightSpace >= MIN_INFO_BOX_SIDE || leftSpace >= MIN_INFO_BOX_SIDE) {
            boxWidth = Math.max(boxWidth, 80);
        }

        List<String> lines = cached.lines;

        int boxHeight = lines.size() * INFO_BOX_LINE_SPACING + INFO_BOX_PADDING * 2 - 1;
        // 高度：顶部对齐；内容超高时底部锚定；极端超高时贴顶 + 绘制裁剪，保证不溢出屏幕
        int boxY = Math.max(4, Math.min(TOP_Y, this.height - boxHeight - 4));

        graphics.fill(sideX, boxY, sideX + boxWidth, boxY + boxHeight, 0xBB000000);
        graphics.fill(sideX, boxY, sideX + boxWidth, boxY + 1, 0x66FFFFFF);
        graphics.fill(sideX, boxY + boxHeight - 1, sideX + boxWidth, boxY + boxHeight, 0x66FFFFFF);
        graphics.fill(sideX, boxY, sideX + 1, boxY + boxHeight, 0x66FFFFFF);
        graphics.fill(sideX + boxWidth - 1, boxY, sideX + boxWidth, boxY + boxHeight, 0x66FFFFFF);

        int lineY = boxY + INFO_BOX_PADDING;
        for (String line : lines) {
            if (lineY + INFO_BOX_LINE_SPACING > this.height - 4) break; // 底部裁剪，防溢出
            graphics.drawString(this.font, line, sideX + INFO_BOX_PADDING, lineY, COLOR_WHITE);
            lineY += INFO_BOX_LINE_SPACING;
        }
    }

    /** 悬停信息框缓存条目：最终渲染行 + 内容自然宽度（含 padding） */
    private static class InfoBoxCacheEntry {
        final List<String> lines;
        final int naturalWidth;
        InfoBoxCacheEntry(List<String> lines, int naturalWidth) {
            this.lines = lines;
            this.naturalWidth = naturalWidth;
        }
    }

    /** 预构建信息框内容（悬停缓存源）：预计算 wrapText/数值文本/觉醒文案与自然宽度 */
    private InfoBoxCacheEntry buildInfoBoxContent(Ability ability, IAdventureProgress progress) {
        String abilityId = ability.id();
        // ---- 预构建内容行（先收集纯文本，再按实际宽度算框宽） ----
        List<String> headerParts = new ArrayList<>();
        boolean isDisabled = disabledAbilities.contains(abilityId);
        String dot = isDisabled ? "§7○ " : "§a● ";
        String headerName = "§f" + ability.name().getString();
        headerParts.add(dot + headerName);

        List<String> descLines = new ArrayList<>();
        List<String> valueParts = new ArrayList<>();
        List<String> awakenedLines = new ArrayList<>();

        String desc = ability.description().getString();
        String valueText = buildValueText(ability, progress);
        if (valueText != null) valueParts.add(valueText);
        String awakenedKey = "ability.adventure_power." + abilityId + ".awakened";
        boolean hasAwakened = progress != null && progress.isFullyUnlocked() && I18n.exists(awakenedKey);

        // ---- 宽度：按最大可用宽度测内容自然宽度 ----
        int rightSpace = this.width - (leftX + PANEL_WIDTH + 10) - 8;
        int leftSpace = leftX - 10 - 8;
        int candidateMaxWidth = Math.min(INFO_BOX_MAX_WIDTH, Math.max(rightSpace, leftSpace));
        int textMaxWidth = candidateMaxWidth - INFO_BOX_PADDING * 2;

        for (String l : wrapText(desc, textMaxWidth)) descLines.add("§7" + l);
        if (hasAwakened) {
            for (String l : wrapText(I18n.get(awakenedKey), textMaxWidth)) {
                awakenedLines.add("§a" + l);
            }
        }

        // 内容自然宽度（取各行实际像素宽度最大值）
        int naturalWidth = 0;
        for (String s : headerParts) naturalWidth = Math.max(naturalWidth, this.font.width(s));
        for (String s : descLines) naturalWidth = Math.max(naturalWidth, this.font.width(s));
        for (String s : valueParts) naturalWidth = Math.max(naturalWidth, this.font.width(s));
        if (hasAwakened) naturalWidth = Math.max(naturalWidth, this.font.width("§6" + I18n.get("screen.adventure_power.awakened_title")));
        for (String s : awakenedLines) naturalWidth = Math.max(naturalWidth, this.font.width(s));
        naturalWidth += INFO_BOX_PADDING * 2;

        // 组装最终行（带颜色码）
        List<String> lines = new ArrayList<>(headerParts);
        lines.addAll(descLines);
        lines.addAll(valueParts);
        if (hasAwakened) {
            lines.add("§6" + I18n.get("screen.adventure_power.awakened_title"));
            lines.addAll(awakenedLines);
        }
        return new InfoBoxCacheEntry(lines, naturalWidth);
    }

    /**
     * 当前数值行文本（不含颜色码）：无成长能力显示固定效果；soul_quench 特例；
     * 其余按 lang 值模板格式化。不走 Component.translatable 嵌套（避免 %s 二次解析失败），
     * 直接用 I18n.get 拼字符串。
     */
    private String buildValueText(Ability ability, IAdventureProgress progress) {
        String id = ability.id();
        String label = progress != null && progress.isFullyUnlocked()
            ? I18n.get("screen.adventure_power.base_value_label")
            : I18n.get("screen.adventure_power.value_label");
        if (AbilityIds.SOUL_QUENCH.equals(id)) {
            if (progress != null && ability instanceof SoulQuenchAbility sq) {
                int count = AbilityGate.effectiveCount(progress, id);
                String text = String.format(I18n.get("screen.adventure_power.soul_quench_value"),
                    formatFloat(sq.flatDamage(count)), formatFloat(sq.hpRatio(count) * 100.0f));
                return "§e" + label + text;
            }
            return null;
        }
        float value = progress != null
            ? AbilityGate.value(progress, id).orElse(-1.0f) : -1.0f;
        if (value < 0) {
            return "§e" + I18n.get("screen.adventure_power.value_fixed");
        }
        String key = "ability.adventure_power." + id + ".value";
        if (I18n.exists(key)) {
            float display = value;
            if (AbilityIds.SWIFT.equals(id)) display = value * 100.0f;
            else if (AbilityIds.DEATH_DEFY.equals(id)) display = value / 20.0f;
            String text;
            try {
                text = String.format(I18n.get(key), display);
            } catch (java.util.IllegalFormatException e) {
                text = formatFloat(value);
            }
            return "§e" + label + text;
        }
        return "§e" + label + formatFloat(value);
    }

    /** 数字格式化：整数值不带小数点，否则保留一位 */
    private static String formatFloat(float v) {
        if (Math.abs(v - Math.round(v)) < 0.0001f) {
            return String.valueOf((long) Math.round(v));
        }
        return String.format("%.1f", v);
    }

    /** 按像素宽度换行（逐字符累积；遇 \n 强制换行），供侧边信息框使用 */
    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(current.toString());
                current.setLength(0);
                currentWidth = 0;
                continue;
            }
            int w = this.font.width(String.valueOf(c));
            if (currentWidth + w > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
                currentWidth = 0;
            }
            current.append(c);
            currentWidth += w;
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    // ===== Buff tab =====
    private void renderBuffTab(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!buffExtendEnabled) {
            graphics.drawCenteredString(this.font,
                Component.translatable("screen.adventure_power.buff_disabled_warn")
                    .withStyle(ChatFormatting.RED),
                this.width / 2, TOP_Y - 18, 0xFF5555);
        }
        if (!ready) {
            graphics.drawCenteredString(this.font,
                Component.translatable("screen.adventure_power.loading"), this.width / 2, TOP_Y + 20, COLOR_GRAY);
            return;
        }
        if (displayEffects.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.translatable("screen.adventure_power.no_buffs"), this.width / 2, this.height / 2, COLOR_GRAY);
            return;
        }
        graphics.drawString(this.font, "●/○", leftX, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.translatable("screen.adventure_power.header_effect"), leftX + 22, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.translatable("screen.adventure_power.header_remaining"), leftX + 100, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.translatable("screen.adventure_power.header_status"), leftX + 155, TOP_Y - 14, COLOR_GRAY);

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
            String name = displayEffectNames.get(i);
            graphics.drawString(this.font, name, leftX + 22, y, COLOR_WHITE);
            String timeStr = displayEffectTimes.get(i);
            graphics.drawString(this.font, timeStr, leftX + 100, y,
                effect.getDuration() < 60 ? COLOR_YELLOW : COLOR_WHITE);
            String status = displayEffectStatuses.get(i);
            graphics.drawString(this.font, status, leftX + 140, y,
                isExcluded ? COLOR_GRAY : COLOR_GREEN);
        }
        renderScrollBar(graphics, getScrollBarX(), TOP_Y);
        graphics.drawCenteredString(this.font,
            Component.translatable("screen.adventure_power.toggle_buff_hint"), this.width / 2, this.height - 22, COLOR_GRAY);
    }

    // ===== 里程碑 tab =====
    private void renderMilestoneTab(GuiGraphics graphics, int mouseX, int mouseY) {
        // 与面板同坐标系（面板居中），右列为解锁条件
        final int LEFT_X = leftX;
        final int RIGHT_X = leftX + 110;
        int total = MilestoneRegistry.getMilestoneCount();
        ensureMilestoneAbilityCache();
        graphics.drawString(this.font, Component.translatable("screen.adventure_power.header_progress").withStyle(s -> s.withColor(COLOR_GRAY)),
            LEFT_X, TOP_Y - 14, COLOR_GRAY);
        graphics.drawString(this.font, Component.translatable("screen.adventure_power.header_condition").withStyle(s -> s.withColor(COLOR_GRAY)),
            RIGHT_X, TOP_Y - 14, COLOR_GRAY);

        if (total == 0) {
            graphics.drawCenteredString(this.font, Component.translatable("screen.adventure_power.no_milestones"),
                this.width / 2, this.height / 2, COLOR_GRAY);
            return;
        }

        int visibleRows = visibleHeight() / MILESTONE_ROW_HEIGHT;
        int startRow = Math.max(0, scrollOffset / MILESTONE_ROW_HEIGHT);
        int endRow = Math.min(total, startRow + visibleRows + 1);

        // progress 快照每 20 tick 刷新一次，避免每帧 capability resolve。
        // 注意：player 非空而 level 为空的窄窗口（维度切换/重连期间面板保持打开）需一并守卫，
        // 否则 mc.level.getGameTime() NPE（与 renderAbilityInfoBox / ClientHudDataCache 的守卫一致）
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            milestoneProgress = Optional.empty();
            milestoneProgressRefreshTick = -1; // 重置基准：level 恢复后立即刷新，防同 /20 桶残留旧快照
        } else {
            long tick = mc.level.getGameTime() / 20;
            if (milestoneProgressRefreshTick != tick) {
                milestoneProgressRefreshTick = tick;
                milestoneProgress = AdventureProgressCapability.getAdventureProgress(mc.player);
            }
        }
        var progress = milestoneProgress.orElse(null);

        for (int i = startRow; i < endRow; i++) {
            Milestone m = MilestoneRegistry.getAll().get(i);
            int y = TOP_Y + i * MILESTONE_ROW_HEIGHT - scrollOffset;
            boolean unlocked = progress != null && progress.isMilestoneUnlocked(m.id());
            graphics.fill(LEFT_X - 4, y - 1, leftX + PANEL_WIDTH + 4, y + MILESTONE_ROW_HEIGHT - 1, COLOR_BG);
            MutableComponent left = Component.literal(unlocked ? "✓ " : "✗ ")
                .withStyle(s -> s.withColor(unlocked ? COLOR_GREEN : COLOR_DARK));
            left.append(Component.literal(m.name())
                .withStyle(s -> s.withColor(unlocked ? COLOR_GREEN : COLOR_GRAY)));
            if (unlocked) {
                left.append(Component.literal("  §8->  "));
                List<Component> names = milestoneAbilityNames.get(i);
                for (int j = 0; j < names.size(); j++) {
                    if (j > 0) left.append(Component.literal(" §8· "));
                    left.append(names.get(j).copy().withStyle(s -> s.withColor(COLOR_WHITE)));
                }
            }
            graphics.drawString(this.font, left, LEFT_X, y + 4, COLOR_WHITE);
            graphics.drawString(this.font, getUnlockHint(m), RIGHT_X, y + 4, COLOR_GRAY);
        }
        renderScrollBar(graphics, getScrollBarX(), TOP_Y);

        if (progress != null) {
            int unlockedCount = progress.getUnlockedMilestoneCount();
            graphics.drawCenteredString(this.font,
                Component.translatable("screen.adventure_power.unlocked_count", unlockedCount, total).withStyle(s -> s.withColor(COLOR_GOLD)),
                this.width / 2, this.height - 16, COLOR_GOLD);
        }
    }

    private static Component getUnlockHint(Milestone m) {
        if (m.trigger() != null) {
            Component hint = triggerHint(m.trigger());
            if (hint != null) return hint;
        }
        if (m.advancement() != null) {
            return Component.translatable("screen.adventure_power.advancement_hint", m.advancement().toString());
        }
        return Component.translatable("screen.adventure_power.unknown_condition");
    }

    private static Component triggerHint(TriggerDef t) {
        return switch (t.type()) {
            case "survive_night" -> Component.translatable("screen.adventure_power.trigger.survive_night");
            case "first_death" -> Component.translatable("screen.adventure_power.trigger.first_death");
            case "first_trade" -> Component.translatable("screen.adventure_power.trigger.first_trade");
            case "y_below" -> Component.translatable("screen.adventure_power.trigger.y_below", t.y() != null ? t.y() : 0);
            case "first_kill" -> Component.translatable("screen.adventure_power.trigger.first_kill",
                t.entity() != null ? t.entity().getPath() : "?");
            case "enter_dimension" -> Component.translatable("screen.adventure_power.trigger.enter_dimension",
                t.dimension() != null ? t.dimension().toString() : "?");
            case "reach_y" -> Component.translatable("screen.adventure_power.trigger.reach_y", t.y() != null ? t.y() : 0);
            case "obtain_item" -> Component.translatable("screen.adventure_power.trigger.obtain_item",
                t.item() != null ? t.item().getPath() : "?");
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
                // 开关状态变化 -> 悬停信息框缓存失效（头部 ●/○ 图标固化在缓存条目中，
                // 不失效会显示旧开关状态直到下一个 20 tick 快照基准）
                infoBoxCache.remove(id);
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
