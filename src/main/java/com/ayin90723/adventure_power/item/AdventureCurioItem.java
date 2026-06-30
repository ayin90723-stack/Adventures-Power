package com.ayin90723.adventure_power.item;

import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 冒险饰品基类 — 冒险的开始 / 冒险的终点。
 * <p>
 * 两件物品共享同一套里程碑能力描述，通过 {@code isEnd} 区分引导语。
 * 配色以金色（{@code §6}）为主调。
 * 里程碑列表由客户端 MilestoneRegistry 动态提供。
 */
public class AdventureCurioItem extends Item {

    private final boolean isEnd;

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
    //  公共：里程碑行（动态）
    // ========================

    private void addMilestoneLines(List<Component> tooltip) {
        if (!MilestoneRegistry.isInitialized()) {
            tooltip.add(Component.translatable("item.adventure_power.lore.loading")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        for (Milestone m : MilestoneRegistry.getAll()) {
            tooltip.add(Component.translatable("item.adventure_power.lore.milestone." + m.id())
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
