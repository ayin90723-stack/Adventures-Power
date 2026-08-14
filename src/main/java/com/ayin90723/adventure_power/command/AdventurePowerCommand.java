package com.ayin90723.adventure_power.command;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.handler.CapabilityLifecycleHandler;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.AdventureItemNbtUtil;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
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
 * 仅提供状态变更功能，无法绕过里程碑解锁未被禁用的能力（一键全解锁除外，属运维功能）：
 * <ul>
 *   <li>{@code /ap unlock milestone <id> [target]} — 解锁里程碑（走 grantMilestone 唯一入口，含觉醒级联）</li>
 *   <li>{@code /ap unlock all [target]} — 一键全解锁全部里程碑（含觉醒；原持有终点物品的测试入口已移除，
 *       全解锁能力迁移至此指令，op 专用）</li>
 *   <li>{@code /ap unlock ability <id> [target]} — 解锁一个<b>被数据包禁用</b>的能力（per-player，NBT 持久化；
 *       该能力保持其归属里程碑的 countAtUnlock，成长公式按原设计计算）</li>
 *   <li>{@code /ap activate [target]} — 补发冒险的开始并激活冒险者身份（应对首次发放物品丢失后的
 *       GOT_BEGIN_KEY 锁死；已激活玩家返回提示）</li>
 *   <li>{@code /ap disabled} — 列出当前被禁用的能力</li>
 * </ul>
 * 目标省略时默认指令执行者自己。非禁用能力、未知 ID、未佩戴冒险饰品的玩家都会被拒绝
 * （activate 与 unlock all 不要求已佩戴饰品）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AdventurePowerCommand {

    private static final String ARG_MILESTONE = "milestone";
    private static final String ARG_ABILITY = "ability";
    private static final String ARG_TARGET = "target";

    /** 里程碑 id TAB 联想（tooltip 显示显示名——玩家无需记忆 id） */
    private static final SuggestionProvider<CommandSourceStack> MILESTONE_SUGGESTIONS = (ctx, builder) -> {
        for (Milestone m : MilestoneRegistry.getAll()) {
            builder.suggest(m.id(), m.displayName());
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
                .then(Commands.literal("all")
                    .executes(ctx -> unlockAll(ctx, getPlayer(ctx, null)))
                    .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                        .executes(ctx -> unlockAll(ctx, getPlayer(ctx, ARG_TARGET)))))
                .then(Commands.literal("ability")
                    .then(Commands.argument(ARG_ABILITY, StringArgumentType.word())
                        .suggests(DISABLED_ABILITY_SUGGESTIONS)
                        .executes(ctx -> unlockAbility(ctx, getPlayer(ctx, null)))
                        .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                            .executes(ctx -> unlockAbility(ctx, getPlayer(ctx, ARG_TARGET)))))))
            .then(Commands.literal("activate")
                .executes(ctx -> activate(ctx, getPlayer(ctx, null)))
                .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                    .executes(ctx -> activate(ctx, getPlayer(ctx, ARG_TARGET)))))
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
                () -> Component.translatable("command.adventure_power.milestone_already", m.displayName()), false);
            return 0;
        }
        // grantMilestone 为唯一入口：完成 Capability 更新 + NBT + 饰品 + 客户端同步 + 觉醒级联
        AdventureProgressCapability.grantMilestone(player, m.id());
        ctx.getSource().sendSuccess(
            () -> Component.translatable("command.adventure_power.milestone_unlocked", m.displayName()), false);
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
            // 已指令解锁过但当前被手动关闭（disabledAbilities 含该能力）：直接补开
            //（v1.4.0——此前仅提示"去面板手动开启"，若该能力在面板无 UI 入口则永久关着；
            // 走到此分支时 isAbilityEnabled 必为 false，toggle 一次即恢复开启）
            progress.toggleAbility(id);
            SyncUtil.syncCapabilityToPersistent(player, progress);
            AdventureItemNbtUtil.syncAllAdventureItemNbt(player, progress);
            SyncUtil.syncToClient(player);
            ctx.getSource().sendSuccess(
                () -> Component.translatable("command.adventure_power.ability_unlocked", id), false);
            return 1;
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

    // ===== /ap unlock all [target]（一键全解锁，op 运维功能） =====

    /**
     * 一键全解锁：逐里程碑解锁 + 觉醒级联（终点替换/计分板/同步走 activateFinalStageIfReady
     * 唯一级联入口）。原 PlayerTickHandler「持有冒险的终点→全解锁」测试入口已移除，
     * 终点物品不再有测试作用（且已移出创造物品栏），全解锁能力仅保留于此指令。
     */
    private static int unlockAll(CommandContext<CommandSourceStack> ctx, ServerPlayer player)
            throws CommandSyntaxException {
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) {
            ctx.getSource().sendFailure(
                Component.translatable("command.adventure_power.need_curio", player.getDisplayName()));
            return 0;
        }
        IAdventureProgress progress = progressOpt.get();
        if (progress.isFullyUnlocked()) {
            ctx.getSource().sendSuccess(
                () -> Component.translatable("command.adventure_power.already_fully_unlocked"), false);
            return 0;
        }
        if (!progress.isAdventurer()) {
            progress.activateAdventurer();
        }
        int unlocked = 0;
        for (Milestone m : MilestoneRegistry.getAll()) {
            if (!progress.isMilestoneUnlocked(m.id())) {
                progress.unlockMilestone(m.id());
                unlocked++;
            }
        }
        // 觉醒级联唯一入口：终点替换 + fullyUnlocked + 计分板 + 同步
        AdventureProgressCapability.activateFinalStageIfReady(player, progress);
        // 物品 NBT 第三层备份同步（v1.4.0 发布前审查补）：activateFinalStageIfReady 的
        // replaceStack 只把旧 begin NBT 原样拷给新 end——本次解锁的里程碑不写入任何
        // 饰品，capability+persistentData 双丢的深边界会少恢复本次解锁进度
        AdventureItemNbtUtil.syncAllAdventureItemNbt(player, progress);
        // 防御：注册表为空/未初始化时级联无操作（activateAdventurer 内存态无同步会
        // 登出即丢）。实际不可达——AddReloadListener 启动即初始化且内置兜底 ≥10 里程碑
        if (!progress.isFullyUnlocked()) {
            SyncUtil.syncCapabilityToPersistent(player, progress);
            SyncUtil.syncToClient(player);
        }
        // 翱翔立即同步（与 grantMilestone 同款，防两处 END handler 顺序竞态）
        if (progress.isAbilityEnabled(AbilityIds.SOAR) && !player.getAbilities().mayfly
            && !player.getAbilities().instabuild && !player.isSpectator()) {
            player.getAbilities().mayfly = true;
            player.onUpdateAbilities();
        }
        final int count = unlocked;
        ctx.getSource().sendSuccess(
            () -> Component.translatable("command.adventure_power.unlock_all_done", count), false);
        return 1;
    }

    // ===== /ap activate [target]（补发饰品 + 激活冒险者） =====

    /**
     * 补发冒险的开始并激活冒险者身份。
     * <p>
     * 应对首次发放锁死场景：GOT_BEGIN_KEY 在发放瞬间置位，未激活玩家若丢失饰品
     * （落地消失/丢弃）将永不补发、永远无法激活。本指令先清除该标记再走
     * {@code giveAdventureBeginIfNeeded} 补发链，之后由 {@code checkAndActivateAdventurer}
     * 完成激活（含 fullyUnlocked 版本飞升玩家直接补激活的分支）。
     */
    private static int activate(CommandContext<CommandSourceStack> ctx, ServerPlayer player)
            throws CommandSyntaxException {
        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) {
            ctx.getSource().sendFailure(
                Component.translatable("command.adventure_power.need_curio", player.getDisplayName()));
            return 0;
        }
        IAdventureProgress progress = progressOpt.get();
        if (progress.isAdventurer()) {
            ctx.getSource().sendSuccess(
                () -> Component.translatable("command.adventure_power.already_adventurer"), false);
            return 0;
        }
        // 清除首次发放标记以绕过锁死（仅未激活玩家执行；后续由 giveAdventureBeginIfNeeded 重新置位）
        player.getPersistentData().remove(PersistentDataKeys.GOT_BEGIN_KEY);
        CapabilityLifecycleHandler.giveAdventureBeginIfNeeded(player);
        CapabilityLifecycleHandler.checkAndActivateAdventurer(player);
        if (progress.isAdventurer()) {
            ctx.getSource().sendSuccess(
                () -> Component.translatable("command.adventure_power.activated"), false);
            return 1;
        }
        ctx.getSource().sendFailure(
            Component.translatable("command.adventure_power.activate_failed", player.getDisplayName()));
        return 0;
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
