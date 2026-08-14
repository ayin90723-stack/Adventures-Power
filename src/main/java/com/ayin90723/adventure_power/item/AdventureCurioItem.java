package com.ayin90723.adventure_power.item;

import com.ayin90723.adventure_power.ability.Ability;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.ProgressCache;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
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
 * 配色以金色（{@code §6}）为主调，上下金色装饰线营造边框感。
 * 里程碑列表由客户端 MilestoneRegistry 动态提供。
 * <p>
 * <b>渐进式显示</b>：里程碑行仅显示<b>已解锁</b>的里程碑（随冒险进度逐个浮现，
 * 未解锁隐藏——初始状态只有"这是一切的开始，旅途的启点"引导语）。
 * 依赖客户端 capability（服务端同步数据）+ {@link ProgressCache}
 * （每 tick 缓存 progress 引用，避免 tooltip 每帧渲染时 resolve）。
 * 因依赖玩家进度，不能全局缓存，逐次构建（里程碑数少，开销可忽略）。
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
        // side 守卫（v1.4.0 审查修复）：里程碑行内部引用客户端 Minecraft 类，
        // 少数模组在服务端构建 tooltip 时防止解析客户端类（NoClassDefFoundError）
        if (level != null && !level.isClientSide()) return;
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
        // 顶部金边
        tooltip.add(Component.literal("◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆").withStyle(ChatFormatting.GOLD));
        // 标题（◆ 包裹）
        tooltip.add(Component.literal("◆ ").withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("item.adventure_power.adventure_begin")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" ◆").withStyle(ChatFormatting.GOLD)));
        tooltip.add(Component.empty());

        tooltip.add(Component.translatable("item.adventure_power.adventure_begin.lore_intro")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.empty());

        addMilestoneLines(tooltip);

        // 底部金边
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆").withStyle(ChatFormatting.GOLD));
    }

    // ========================
    //  冒险的终点
    // ========================

    private void addEndTooltip(List<Component> tooltip) {
        tooltip.add(Component.literal("◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("◆ ").withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("item.adventure_power.adventure_end")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" ◆").withStyle(ChatFormatting.GOLD)));
        tooltip.add(Component.empty());

        tooltip.add(Component.translatable("item.adventure_power.adventure_end.lore_intro")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.empty());

        addMilestoneLines(tooltip);

        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("item.adventure_power.adventure_end.lore_footer")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
        tooltip.add(Component.literal("◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆ ◆").withStyle(ChatFormatting.GOLD));
    }

    // ========================
    //  公共：渐进式里程碑行
    // ========================

    /**
     * 渐进式里程碑行：仅显示已解锁里程碑（未解锁隐藏——初始状态只有引导语）。
     * 冒险的终点（全解锁持有者）显示全部里程碑，与旧版行为一致。
     * 玩家为 null（物品栏预览等无玩家上下文场景）时不显示里程碑行。
     */
    private void addMilestoneLines(List<Component> tooltip) {
        if (!MilestoneRegistry.isInitialized()) {
            tooltip.add(Component.translatable("item.adventure_power.lore.loading")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        IAdventureProgress progress = ProgressCache.get(player);

        boolean anyUnlocked = false;
        for (Milestone m : MilestoneRegistry.getAll()) {
            if (progress == null || !progress.isMilestoneUnlocked(m.id())) continue;
            anyUnlocked = true;
            MutableComponent line = Component.literal("★ ").withStyle(ChatFormatting.GOLD)
                    .append(m.displayName().withStyle(ChatFormatting.GOLD));
            line.append(Component.literal("  §8»  "));
            List<String> ids = m.abilities();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) line.append(Component.literal(" §8· "));
                Ability a = AbilityRegistry.get(ids.get(i));
                if (a != null) {
                    line.append(a.name().copy().withStyle(ChatFormatting.GRAY));
                }
            }
            tooltip.add(line);
        }
        // 渐进式提示：尚未解锁任何里程碑（初始状态）
        if (!anyUnlocked) {
            tooltip.add(Component.translatable("item.adventure_power.lore_locked")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
