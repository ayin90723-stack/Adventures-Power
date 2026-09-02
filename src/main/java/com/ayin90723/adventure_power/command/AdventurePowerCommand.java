package com.ayin90723.adventure_power.command;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.handler.CapabilityLifecycleHandler;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.util.AdventureItemNbtUtil;
import com.ayin90723.adventure_power.util.HealthUtil;
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
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冒险的力量 — 指令后门。
 * <p>
 * <b>权限结构（v1.4.9 requires 下沉）</b>：根节点 {@code /ap} 不再整体 requires(2)——
 * {@code unlock}/{@code activate}/{@code disabled} 各子分支自挂 requires(2)（逐分支
 * 枚举防漏，漏挂即权限放大），{@code die} 无参变体权限 0（死亡本就是玩家可自行达成
 * 的事，等价跳岩浆，无滥用面；指定他人 op2 供管理员救援）。非 op 玩家由此能在 TAB
 * 联想中看到 {@code /ap die}——逃生出口的可见性即设计意图。
 * <p>
 * 状态变更功能（无法绕过里程碑解锁未被禁用的能力，一键全解锁除外，属运维功能）：
 * <ul>
 *   <li>{@code /ap unlock milestone <id> [target]} — 解锁里程碑（走 grantMilestone 唯一入口，含觉醒级联）</li>
 *   <li>{@code /ap unlock all [target]} — 一键全解锁全部里程碑（含觉醒；op 专用）</li>
 *   <li>{@code /ap unlock ability <id> [target]} — 解锁一个<b>被数据包禁用</b>的能力（per-player，NBT 持久化）</li>
 *   <li>{@code /ap activate [target]} — 补发冒险的开始并激活冒险者身份</li>
 *   <li>{@code /ap disabled} — 列出当前被禁用的能力</li>
 *   <li>{@code /ap die [player]} — <b>受困态逃生指令</b>（v1.4.9，权限 0）：保证能死的保底出口</li>
 * </ul>
 * 目标省略时默认指令执行者自己。非禁用能力、未知 ID、未佩戴冒险饰品的玩家都会被拒绝
 * （activate 与 unlock all 不要求已佩戴饰品）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AdventurePowerCommand {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final String ARG_MILESTONE = "milestone";
    private static final String ARG_ABILITY = "ability";
    private static final String ARG_TARGET = "target";

    /** /ap die 待恢复能力表：uuid → 三防御能力旧态 [true_health, reject_manip, death_defy]。 */
    private static final Map<UUID, boolean[]> DIE_PENDING_RESTORE = new ConcurrentHashMap<>();

    /**
     * /ap die 观察哨记录（七轮后补）：本轮指令触发的 LivingDeathEvent 被第三方 cancel。
     * 判据用事件观察哨而非死亡状态字段——玩家侧 dead 标志不可用（置 dead 的
     * LivingEntity.die 被 ServerPlayer.die 完整覆写不调 super，die 全方法体零 dead/
     * deathTime 写入），deathTime/isDeadOrDying 亦不可用（血量驱动，0 血未死与正常死亡
     * 服务端同型，区分不出）。
     */
    private static final Set<UUID> DIE_EVENT_CANCELLED = ConcurrentHashMap.newKeySet();

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
        // v1.4.9 requires 下沉：根节点无权限要求，各管理子分支自挂 requires(2)，
        // die 无参权限 0 / 带玩家参数 op2（逃生出口对全部玩家可见可用）
        return Commands.literal(name)
            .then(Commands.literal("unlock")
                .requires(source -> source.hasPermission(2))
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
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> activate(ctx, getPlayer(ctx, null)))
                .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                    .executes(ctx -> activate(ctx, getPlayer(ctx, ARG_TARGET)))))
            .then(Commands.literal("disabled")
                .requires(source -> source.hasPermission(2))
                .executes(AdventurePowerCommand::listDisabled))
            .then(Commands.literal("die")
                .executes(ctx -> dieCommand(ctx, getPlayer(ctx, null)))
                .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> dieCommand(ctx, getPlayer(ctx, ARG_TARGET)))));
    }

    /** 解析目标玩家：指定了 target 参数用目标，否则用指令执行者自己 */
    private static ServerPlayer getPlayer(CommandContext<CommandSourceStack> ctx, String targetArg)
            throws CommandSyntaxException {
        return targetArg != null
            ? EntityArgument.getPlayer(ctx, targetArg)
            : ctx.getSource().getPlayerOrException();
    }

    // ===== /ap die [player]（受困态逃生指令，v1.4.9 计划 2.6） =====

    /**
     * 保证能死的保底出口。
     * <p>
     * <b>背景（死锁实证）</b>：读侧调用点劫持形态下，death 名单玩家的一切 hurt 调用在
     * HEAD 即被谎报的 isDeadOrDying 早退拦下——包括 /kill（kill() 内部走 hurt）与虚空
     * 伤害；die()/setHealth()/remove() 直调被我方能力门禁拦截；名单唯一清除点是重生事件，
     * 而重生需要先死——无逃生通道时该状态为永久受困（跨登出与服务器重启持续）。
     * <p>
     * <b>为什么用"临时关能力"而不是"加自杀标记进各门禁"</b>：全部防御拦截器的门禁
     * 本就读 {@code isAbilityEnabled}（既有已验证行为）——关能力=所有拦截器自然失效，
     * 零防御代码改动；同时兑现"能力可自由开关"的核心承诺（玩家关掉的能力不许生效——
     * 包括对本指令）。
     * <p>
     * 流程：①临时关闭 true_health / reject_manip / death_defy ②setHealth(0)（纯原版
     * 写入）③die() 直调（不走 hurt——HEAD 劫持早退不影响；补全掉落/事件/死亡画面等
     * 完整原版死亡流程，与正常击杀一致，<b>非处决语义</b>）③.5 die 被第三方
     * LivingDeathEvent cancel 时同栈补偿 ④能力状态恢复挂 PlayerRespawnEvent。
     * 图腾不在此链路生效（checkTotemDeathProtection 仅被 hurt() 调用——字节码核实
     * 唯一 invoke 点位于 hurt 体内，die 体内零调用），直调 setHealth(0)+die() 完全绕过
     * 图腾，逃生语义无"没死成需重跑"分支。
     */
    private static int dieCommand(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        UUID uuid = player.getUUID();

        // 复查修 P2-2 审查修订（P1-1）：已死早退必须先于 stale 恢复——同 tick 二次执行时
        // （命令方块/schedule function 连发）若先恢复三能力（backup>0），本死亡动画中的
        // 玩家会在下一 tick 被真血 tick 自检"复活"（服务端实体活了而客户端挂死亡画面）。
        // 早退判据用容器事实 isRemoved（死亡流程完成=已移除）而非 isDeadOrDying（血量驱动）
        // ——vp 零血拉锯期（攻防两端写 0 vs 修复）isDeadOrDying 恒 true，旧判据使逃生指令
        // 在该核心受困场景不可达（逃生指令的宗旨反被破坏）；拉锯中实体仍在，
        // isRemoved=false → 指令可执行。已死（已移除）时直接 return 且**不移除**条目
        // ——交给 onPlayerRespawnRestore 在重生时恢复
        if (player.isRemoved()) {
            return 1;
        }

        // 入口防重：同一实体已有待恢复条目（上次 /ap die 未走完重生流程）——先按旧条目
        // 恢复能力再继续，防旧态被覆盖丢失（能力保持关闭、玩家无法自动恢复）
        boolean[] stale = DIE_PENDING_RESTORE.remove(uuid);
        if (stale != null) {
            AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                restoreAbility(progress, AbilityIds.TRUE_HEALTH, stale[0]);
                restoreAbility(progress, AbilityIds.REJECT_MANIP, stale[1]);
                restoreAbility(progress, AbilityIds.DEATH_DEFY, stale[2]);
                SyncUtil.syncCapabilityToPersistent(player, progress);
                SyncUtil.syncToClient(player);
            });
        }

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        boolean suspended = false;
        if (progressOpt.isPresent()) {
            IAdventureProgress progress = progressOpt.get();
            boolean th = progress.isAbilityEnabled(AbilityIds.TRUE_HEALTH);
            boolean rm = progress.isAbilityEnabled(AbilityIds.REJECT_MANIP);
            boolean dd = progress.isAbilityEnabled(AbilityIds.DEATH_DEFY);
            if (th || rm || dd) {
                if (th) progress.toggleAbility(AbilityIds.TRUE_HEALTH);
                if (rm) progress.toggleAbility(AbilityIds.REJECT_MANIP);
                if (dd) progress.toggleAbility(AbilityIds.DEATH_DEFY);
                DIE_PENDING_RESTORE.put(uuid, new boolean[]{th, rm, dd});
                SyncUtil.syncCapabilityToPersistent(player, progress);
                suspended = true;
            }
        }
        if (suspended) {
            // 中断流程提示（计划 2.6 步骤 4）：登出/重启打断流程则能力保持关闭态，
            // P 面板可见、可手动恢复
            player.displayClientMessage(
                Component.translatable("command.adventure_power.die_defenses_suspended")
                    .withStyle(ChatFormatting.GOLD), false);
        }

        // ② setHealth(0)：能力已关，我方全部拦截层（方法级/数据级/属性级/tick 自检/
        // getHealth 重初始化）门禁同步失效，纯原版写入
        player.setHealth(0.0F);

        // ③ die() 直调；③.5 同栈判定被 cancel（观察哨在 LOWEST + receiveCanceled 记录）
        DIE_EVENT_CANCELLED.remove(uuid);
        try {
            player.die(player.damageSources().genericKill());
        } catch (Exception e) {
            // die 内部异常（第三方覆写抛出）：复查修 P2-1——异常可能发生在 LivingDeathEvent
            // post 之前（无任何事件分发 → 观察哨无记录），显式走补偿收尾（与"被取消"路径
            // 同构：三能力回写 + 血量经 getHealthDirect 判据修复 + 提示；若事件已 post 且
            // 死亡流程已推进——isDeadOrDying 的补偿回写救活从"该死未死"语境看无害且收敛）
            LOGGER.warn("[/ap die] die() 调用异常（按被拦截处理走补偿）", e);
            compensateDieIntercept(player);
        }
        if (DIE_EVENT_CANCELLED.remove(uuid)) {
            compensateDieIntercept(player);
        }
        return 1;
    }

    /**
     * die 被第三方 LivingDeathEvent cancel 的同栈补偿（步骤 3.5）。
     * <p>
     * 0 血未死不是稳态：baseTick 的 isDeadOrDying 分支（辅 level.shouldTickDeath 门）
     * 驱动 tickDeath——deathTime 到 20 即 remove(KILLED)，此刻三防御已关、我方 remove
     * 拦截（backup&gt;0 cancel KILLED）同关，约 1 秒后幽灵化。不与拦截方对抗（玩家装备
     * 的合法机制）：①三能力立即回写（PlayerRespawnEvent 不会来）②血量仅在 DataItem
     * 直读仍 ≤0 时修复（判据必须数据层直读——补偿①已回写能力、真血读层即刻接管，
     * 此后 getHealth() 恒 &gt;0：DataItem≤0+backup&gt;0 时 onGetHealth 假死分支返回
     * backup 且顺带触发自愈修复，拦截方已回血则返回回血值——用它判"仍 ≤0"是恒假哑
     * 分支；拦截方若已自行回血则尊重其写入、不覆盖）③聊天栏提示。LOWEST 之后注册更晚
     * 的同优先级拦截方为已知残余，可忽略。
     */
    private static void compensateDieIntercept(ServerPlayer player) {
        boolean[] saved = DIE_PENDING_RESTORE.remove(player.getUUID());
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            restoreAbility(progress, AbilityIds.TRUE_HEALTH, saved != null && saved[0]);
            restoreAbility(progress, AbilityIds.REJECT_MANIP, saved != null && saved[1]);
            restoreAbility(progress, AbilityIds.DEATH_DEFY, saved != null && saved[2]);
            SyncUtil.syncCapabilityToPersistent(player, progress);
            if (HealthUtil.getHealthDirect(player) <= 0.0F) {
                float backup = progress.getBackupHealth();
                HealthUtil.repairHealth(player,
                    backup > 0.0F && Float.isFinite(backup) ? backup : player.getMaxHealth());
            }
            SyncUtil.syncToClient(player);
        });
        player.displayClientMessage(
            Component.translatable("command.adventure_power.die_intercepted")
                .withStyle(ChatFormatting.GOLD), false);
    }

    /** 能力恢复（wasEnabled=false 时不动——本来就是关的；已开启不重复 toggle）。 */
    private static void restoreAbility(IAdventureProgress progress, String id, boolean wasEnabled) {
        if (wasEnabled && !progress.isAbilityEnabled(id)) {
            progress.toggleAbility(id);
        }
    }

    /**
     * die 拦截观察哨：LOWEST 排在几乎全部拦截方之后、receiveCanceled=true 被 cancel 也
     * 收得到——命中本次指令标记表时记录 isCanceled，供 dieCommand 同栈判定。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeathObserve(LivingDeathEvent event) {
        if (event.isCanceled() && event.getEntity() instanceof ServerPlayer sp
            && DIE_PENDING_RESTORE.containsKey(sp.getUUID())) {
            DIE_EVENT_CANCELLED.add(sp.getUUID());
        }
    }

    /** 重生后恢复三能力（计划 2.6 步骤 4：内存 Map 重生后回写）。 */
    @SubscribeEvent
    public static void onPlayerRespawnRestore(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        boolean[] saved = DIE_PENDING_RESTORE.remove(player.getUUID());
        if (saved == null) return;
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            restoreAbility(progress, AbilityIds.TRUE_HEALTH, saved[0]);
            restoreAbility(progress, AbilityIds.REJECT_MANIP, saved[1]);
            restoreAbility(progress, AbilityIds.DEATH_DEFY, saved[2]);
            SyncUtil.syncCapabilityToPersistent(player, progress);
            SyncUtil.syncToClient(player);
        });
    }

    /** 登出丢弃待恢复条目：中断流程能力保持关闭态（P 面板可见、可手动恢复）。 */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        DIE_PENDING_RESTORE.remove(uuid);
        DIE_EVENT_CANCELLED.remove(uuid);
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
        // 审查修 P2#1：首次指令解锁时同步清除玩家个人"手动关闭"标记——首次解锁走不到
        // 上方 toggle 分支，disabledAbilities 残留会导致命令报成功但能力实际仍关闭，
        // 且该能力已脱离里程碑归属、面板无 UI 入口，玩家无法自救
        if (!progress.isAbilityEnabled(id)) {
            progress.toggleAbility(id);
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
