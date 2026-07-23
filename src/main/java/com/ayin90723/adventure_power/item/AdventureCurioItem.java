package com.ayin90723.adventure_power.item;

import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 冒险饰品基类 — 冒险的开始 / 冒险的终点。
 * <p>
 * 两件物品共享同一套里程碑能力描述，通过 {@code isEnd} 区分引导语。
 * 配色以金色（{@code §6}）为主调。
 * 里程碑列表由客户端 MilestoneRegistry 动态提供。
 * <p>
 * 里程碑行缓存：使用 {@link MilestoneRegistry#getVersion()} 作为失效标记，
 * 数据包重载或里程碑变更时 version 递增，tooltip 自动重建。
 */
public class AdventureCurioItem extends Item {

    private final boolean isEnd;

    /** 缓存的里程碑行列表。 */
    private static List<Component> cachedMilestoneLines = null;
    /** 缓存对应的版本号。 */
    private static int cachedVersion = -1;

    public AdventureCurioItem(boolean isEnd) {
        super(new Item.Properties().stacksTo(1));
        this.isEnd = isEnd;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        if (isEnd) {
            addEndTooltip(tooltip);
        } else {
            addBeginTooltip(tooltip);
        }
    }

    // ========================
    //  冒险的开始
    // ========================

    private void addBeginTooltip(List<Component> tooltip) {
        tooltip.add(Component.translatable("item.adventure_power.adventure_begin")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        tooltip.add(Component.empty());

        tooltip.add(Component.translatable("item.adventure_power.adventure_begin.lore_intro")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.empty());

        tooltip.add(Component.literal("◆ ◇ ◆").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.empty());

        addMilestoneLines(tooltip);
    }

    // ========================
    //  冒险的终点
    // ========================

    private void addEndTooltip(List<Component> tooltip) {
        tooltip.add(Component.translatable("item.adventure_power.adventure_end")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        tooltip.add(Component.empty());

        tooltip.add(Component.translatable("item.adventure_power.adventure_end.lore_intro")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.empty());

        tooltip.add(Component.literal("◆ ◇ ◆").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.empty());

        addMilestoneLines(tooltip);

        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("item.adventure_power.adventure_end.lore_footer")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
    }

    // ========================
    //  公共：里程碑行（缓存）
    // ========================

    /**
     * 添加里程碑行到 tooltip。
     * 使用 MilestoneRegistry.version 作为缓存失效标记，
     * 版本未变更时直接使用缓存，避免重复遍历构建。
     */
    private void addMilestoneLines(List<Component> tooltip) {
        if (!MilestoneRegistry.isInitialized()) {
            tooltip.add(Component.translatable("item.adventure_power.lore.loading")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        int currentVersion = MilestoneRegistry.getVersion();
        if (cachedMilestoneLines == null || cachedVersion != currentVersion) {
            cachedMilestoneLines = buildMilestoneLines();
            cachedVersion = currentVersion;
        }
        tooltip.addAll(cachedMilestoneLines);
    }

    /** 构建里程碑行列表（从 MilestoneRegistry 遍历生成）。 */
    private static List<Component> buildMilestoneLines() {
        List<Component> lines = new ArrayList<>();
        for (Milestone m : MilestoneRegistry.getAll()) {
            MutableComponent line = Component.literal(m.name()).withStyle(ChatFormatting.GOLD);
            line.append(Component.literal("  §8»  "));
            List<String> ids = m.abilities();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) line.append(Component.literal(" §8· "));
                Ability a = AbilityRegistry.get(ids.get(i));
                if (a != null) {
                    line.append(a.name().copy().withStyle(ChatFormatting.GRAY));
                }
            }
            lines.add(line);
        }
        return lines;
    }
}
