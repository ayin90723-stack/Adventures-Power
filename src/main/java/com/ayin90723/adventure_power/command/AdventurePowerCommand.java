package com.ayin90723.adventure_power.command;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.AdventureItemNbtUtil;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.SyncUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

/**
 * 冒险的力量 — 指令后门（op 2 级权限）。
 * <p>
 * 仅提供两个状态变更功能，无法绕过里程碑解锁未被禁用的能力：
 * <ul>
 *   <li>{@code /ap unlock milestone <id> [target]} — 解锁里程碑（走 grantMilestone 唯一入口，含觉醒级联）</li>
 *   <li>{@code /ap unlock ability <id> [target]} — 解锁一个<b>被数据包禁用</b>的能力（per-player，NBT 持久化；
 *       该能力保持其归属里程碑的 countAtUnlock，成长公式按原设计计算）</li>
 *   <li>{@code /ap disabled} — 列出当前被禁用的能力</li>
 * </ul>
 * 目标省略时默认指令执行者自己。非禁用能力、未知 ID、未佩戴冒险饰品的玩家都会被拒绝。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AdventurePowerCommand {

    private static final String ARG_MILESTONE = "milestone";
    private static final String ARG_ABILITY = "ability";
    private static final String ARG_TARGET = "target";

    /** 里程碑 id TAB 联想（tooltip 显示显示名——玩家无需记忆 id） */
    private static final SuggestionProvider<CommandSourceStack> MILESTONE_SUGGESTIONS = (ctx, builder) -> {
        for (Milestone m : MilestoneRegistry.getAll()) {
            builder.suggest(m.id(), Component.literal(m.name()));
        }
        return builder.buildFuture();
    };

    /** 被禁用能力 id TAB 联想（/ap unlock ability 仅允许这些能力） */
    private static final SuggestionProvider<CommandSourceStack> DISABLED_ABILITY_SUGGESTIONS = (ctx, builder) -> {
        for (String id : MilestoneRegistry.getDisabledAbilities()) {
            builder.suggest(id);
        }
        return builder.buildFuture();
    };

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(buildCommand("adventure_power"));
        dispatcher.register(buildCommand("ap"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildCommand(String name) {
        return Commands.literal(name)
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("unlock")
                .then(Commands.literal("milestone")
                    .then(Commands.argument(ARG_MILESTONE, StringArgumentType.word())
                        .suggests(MILESTONE_SUGGESTIONS)
                        .executes(ctx -> unlockMilestone(ctx, getPlayer(ctx, null)))
                        .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                            .executes(ctx -> unlockMilestone(ctx, getPlayer(ctx, ARG_TARGET))))))
                .then(Commands.literal("ability")
                    .then(Commands.argument(ARG_ABILITY, StringArgumentType.word())
                        .suggests(DISABLED_ABILITY_SUGGESTIONS)
                        .executes(ctx -> unlockAbility(ctx, getPlayer(ctx, null)))
                        .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                            .executes(ctx -> unlockAbility(ctx, getPlayer(ctx, ARG_TARGET)))))))
            .then(Commands.literal("disabled")
                .executes(AdventurePowerCommand::listDisabled));
    }

    /** 解析目标玩家：指定了 target 参数用目标，否则用指令执行者自己 */
    private static ServerPlayer getPlayer(CommandContext<CommandSourceStack> ctx, String targetArg)
            throws CommandSyntaxException {
        return targetArg != null
            ? EntityArgument.getPlayer(ctx, targetArg)
            : ctx.getSource().getPlayerOrException();
    }

    // ===== /ap unlock milestone <id> [target] =====

    private static int unlockMilestone(CommandContext<CommandSourceStack> ctx, ServerPlayer player)
            throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, ARG_MILESTONE);
        Milestone m = MilestoneRegistry.getById(id);
        if (m == null) {
            ctx.getSource().sendFailure(Component.translatable("command.adventure_power.unknown_milestone", id));
            return 0;
        }
        if (!AdventureProgressCapability.isAdventurer(player)) {
            ctx.getSource().sendFailure(
                Component.translatable("command.adventure_power.need_curio", player.getDisplayName()));
            return 0;
        }
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) {
            ctx.getSource().sendFailure(
                Component.translatable("command.adventure_power.need_curio", player.getDisplayName()));
            return 0;
        }
        if (progressOpt.get().isMilestoneUnlocked(m.id())) {
            ctx.getSource().sendSuccess(
                () -> Component.translatable("command.adventure_power.milestone_already", m.name()), false);
            return 0;
        }
        // grantMilestone 为唯一入口：完成 Capability 更新 + NBT + 饰品 + 客户端同步 + 觉醒级联
        AdventureProgressCapability.grantMilestone(player, m.id());
        ctx.getSource().sendSuccess(
            () -> Component.translatable("command.adventure_power.milestone_unlocked", m.name()), false);
        return 1;
    }

    // ===== /ap unlock ability <id> [target]（仅限被禁用的能力） =====

    private static int unlockAbility(CommandContext<CommandSourceStack> ctx, ServerPlayer player)
            throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, ARG_ABILITY);
        if (AbilityRegistry.get(id) == null) {
            ctx.getSource().sendFailure(Component.translatable("command.adventure_power.unknown_ability", id));
            return 0;
        }
        // 防绕过硬门禁：只有数据包 disabled_abilities 黑名单中的能力允许指令解锁，
        // 未被禁用的能力必须通过解锁里程碑正常获得
        if (!MilestoneRegistry.isAbilityDisabled(id)) {
            ctx.getSource().sendFailure(Component.translatable("command.adventure_power.not_disabled", id));
            return 0;
        }
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) {
            ctx.getSource().sendFailure(
                Component.translatable("command.adventure_power.need_curio", player.getDisplayName()));
            return 0;
        }
        IAdventureProgress progress = progressOpt.get();
        if (!progress.isAdventurer() && !progress.isFullyUnlocked()) {
            ctx.getSource().sendFailure(
                Component.translatable("command.adventure_power.need_curio", player.getDisplayName()));
            return 0;
        }
        if (progress.isAbilityEnabled(id)) {
            ctx.getSource().sendSuccess(
                () -> Component.translatable("command.adventure_power.ability_already", id), false);
            return 0;
        }
        if (!progress.grantAbilityByCommand(id)) {
            // 已指令解锁过但当前在 P 面板中被手动关闭：提示真实状态，不误报成功
            ctx.getSource().sendSuccess(
                () -> Component.translatable("command.adventure_power.ability_toggled_off", id), false);
            return 0;
        }
        SyncUtil.syncCapabilityToPersistent(player, progress);
        // 同步物品 NBT 第三层备份（MME_CommandGranted）——否则指令解锁记录只存在于
        // Capability + persistentData，物品兜底路径（维度切换/Clone 极端场景）会静默丢失
        AdventureItemNbtUtil.syncAllAdventureItemNbt(player, progress);
        SyncUtil.syncToClient(player);
        ctx.getSource().sendSuccess(
            () -> Component.translatable("command.adventure_power.ability_unlocked", id), false);
        return 1;
    }

    // ===== /ap disabled =====

    private static int listDisabled(CommandContext<CommandSourceStack> ctx) {
        Set<String> disabled = MilestoneRegistry.getDisabledAbilities();
        if (disabled.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("command.adventure_power.no_disabled"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("command.adventure_power.disabled_list_header"), false);
        for (String id : disabled) {
            var ability = AbilityRegistry.get(id);
            Component name = ability != null ? ability.name() : Component.literal(id);
            // withStyle 直接作用于新构建的组件（translatable/literal 每次调用都是新实例，无需 copy）
            ctx.getSource().sendSuccess(
                () -> Component.literal(" - ").append(name).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(" (" + id + ")").withStyle(ChatFormatting.GRAY)),
                false);
        }
        return 1;
    }
}
