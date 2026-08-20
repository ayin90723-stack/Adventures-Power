package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.mixin.PiercingGazeLivingEntityAccessor;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import com.ayin90723.adventure_power.util.probe.BloodWriteEngine;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 破敌之眼穿透结算公共工具。
 * <p>
 * 将原本散落在 {@link com.ayin90723.adventure_power.mixin.PiercingGazeMixin}、
 * {@link com.ayin90723.adventure_power.mixin.PiercingGazePlayerAttackMixin}、
 * {@link com.ayin90723.adventure_power.mixin.PiercingGazeLivingEntityMixin} 三处的
 * 重复逻辑（攻击者追溯 / 门禁检查 / 发事件 / 直写伤害 / 血量兜底 / 清无敌字段）
 * 统一收口于此，保证各层穿透行为一致。
 *
 * <h3>穿透结算两段式</h3>
 * <ul>
 *   <li>{@link #invokeActuallyHurt} - 直写伤害，绕过 hurt() 内的护甲/无敌判定</li>
 *   <li>{@link #afterPierceFallback} - 血量直写兜底（防 Boss 注入 setHealth 恢复血量）
 *       + 清自定义无敌计时器（防下次 hurt 提前 return false 锁死影杀 NBT）</li>
 * </ul>
 * 调用方按场景组合：actuallyHurt 未执行时（Layer 0 / Layer 2 情况 A/C）两段都调；
 * actuallyHurt 已由原版管线执行时（Layer 2 情况 B）只调兜底段，避免重复扣血。
 *
 * @see com.ayin90723.adventure_power.mixin.PiercingGazeMixin
 * @see com.ayin90723.adventure_power.mixin.PiercingGazePlayerAttackMixin
 * @see com.ayin90723.adventure_power.mixin.PiercingGazeLivingEntityMixin
 */
public final class PiercingGazeUtil {

    private PiercingGazeUtil() {
    }

    /**
     * 本次 hurt 是否已 post 事件（原版 ForgeHooks.onLivingHurt 或各手动 post 路径）。
     * 由 Layer 0 消费式读取决定是否补发 LivingHurtEvent——正常环境原版已 post，
     * 重复补发会让淬魂/嗜血/禁疗等监听器双倍结算（影杀已有 SHADOW_KILL_TICKED 去重）；
     * 仅当 ASM 跳过 ForgeHooks 的环境（如 fantasy_ending）计数无新增才需要补发。
     * <p>
     * <b>实现：单调递增计数 + 基线比较，而非布尔</b>。全局布尔无法区分"本层/外层"——
     * 嵌套 hurt（Boss 在 hurt 管线内对另一实体 AoE、风暴守卫跳过的递归）清除/恢复布尔
     * 会污染外层判定（外层误判"未 post"→ 补 post + 再次 actuallyHurt 双倍结算）。
     * 计数按"本层开始时的基准"比较增量，天然栈式隔离：本层帧保存压栈时的计数，
     * 本层 posted = 当前计数 > 基准，嵌套层的新增 post 只影响其自身基准，外层不受污染，
     * 且无需压栈恢复（计数单调递增，外层基准始终有效）。
     * <p>
     * 标记来源（v1.3.3 单一来源）：{@code CombatAbilityHandler.onLivingHurt} 监听器入口
     * （任何 post 的 LivingHurtEvent 都触发该监听器，计数 = 事件已发的直接证据）。
     * <p>
     * 放在本工具类而非 Mixin 类：@Mixin 类禁止非 private static 方法
     * （Mixin Applicator 会尝试混入目标类导致 InvalidMixinException）。
     */
    private static final ThreadLocal<Long> VANILLA_HURT_EVENT_POST_COUNT = ThreadLocal.withInitial(() -> 0L);
    /** Layer 0（Player.attack）的本次攻击作用域基线（begin 时记录，consume 比较增量） */
    private static final ThreadLocal<Long> VANILLA_HURT_SCOPE_BASE = ThreadLocal.withInitial(() -> 0L);

    /** 每个实体最近一次 post 事件时的全局计数（WeakHashMap 弱 key：实体 unload/死亡后自动回收）。
     *  用途：嵌套 hurt（Boss 在 hurt 管线内对另一实体 AOE）的 post 也会推高全局计数，
     *  纯计数方案会把嵌套增量误判为本层已 post——按实体隔离后，本层只认"本实体的 post 增量"，
     *  Layer 0 的 consume 与 Layer 2 的 posted 判定不再被嵌套实体的事件污染 */
    private static final ThreadLocal<java.util.Map<Entity, Long>> LAST_POSTED_PER_ENTITY =
        ThreadLocal.withInitial(java.util.WeakHashMap::new);

    /** 风暴守卫（由 PiercingGazeLivingEntityMixin 迁移至此，Layer 0/2 共用）：
     *  手动 post 事件前置 true——post 期间第三方监听器递归 target.hurt() 时，
     *  递归层 HEAD 压栈捕获到本层 IN_PIERCING=true 即可跳过穿透阻断递归 */
    public static final ThreadLocal<Boolean> IN_PIERCING = ThreadLocal.withInitial(() -> false);

    /** 由 {@code CombatAbilityHandler.onLivingHurt} 入口调用（事件 post 即触发）：
     *  本次 hurt 已 post 事件（全局计数 +1 并按实体记录，供 per-entity 判定） */
    public static void markVanillaHurtEventPosted(LivingEntity target) {
        long next = VANILLA_HURT_EVENT_POST_COUNT.get() + 1;
        VANILLA_HURT_EVENT_POST_COUNT.set(next);
        LAST_POSTED_PER_ENTITY.get().put(target, next);
    }

    /** 读取当前 post 计数（Layer 2 onHurtEnter 压栈时记录本层基准用） */
    public static long getVanillaHurtEventPostCount() {
        return VANILLA_HURT_EVENT_POST_COUNT.get();
    }

    /** Layer 0（Player.attack 重定向）专用：记录本次攻击作用域的 post 计数基线。
     *  consume 只反映"基线之后的增量"——环境噪声 hurt（怪物互殴等）在两次 attack
     *  之间的 post 计入基线，不会被下一次 attack 误消费 */
    public static void beginVanillaHurtScope() {
        VANILLA_HURT_SCOPE_BASE.set(VANILLA_HURT_EVENT_POST_COUNT.get());
    }

    /**
     * Layer 0（Player.attack 重定向）专用：本次攻击作用域内目标是否已 post 事件
     * （该实体的最近 post 计数 > 基线）。未走原版管线时（Boss 完全重写 hurt() 不调 super）
     * 无新增 post，返回 false。
     */
    public static boolean consumeVanillaHurtEventPosted(LivingEntity self) {
        Long last = LAST_POSTED_PER_ENTITY.get().get(self);
        return last != null && last > VANILLA_HURT_SCOPE_BASE.get();
    }

    /**
     * Layer 2（hurt RETURN）专用：本层 hurt 期间是否已 post 事件（当前计数 > 本层基准）。
     * <p>
     * 用于判断原版管线（{@code actuallyHurt -> ForgeHooks.onLivingHurt}）是否已完整结算——
     * 是则 Boss 仅改了返回值（放行不重复结算），否则是 Boss 完全拦截（需补 post + 直写）。
     * <p>
     * 非消费式：计数单调递增，本层基准由调用方（栈帧）持有，读取不影响后续判定。
     *
     * @param base 本层 hurt 开始时的计数（onHurtEnter 压栈时记录）
     * @param self 本层 hurt 的目标实体（只认该实体自身的 post 增量，防嵌套实体事件污染）
     */
    public static boolean peekVanillaHurtEventPosted(long base, LivingEntity self) {
        Long last = LAST_POSTED_PER_ENTITY.get().get(self);
        return last != null && last > base;
    }

    /**
     * 追溯真正的攻击者：直接实体 -> 间接实体 -> 弹射物发射者。
     * <p>
     * 弹射物（箭/弩箭/火球等）的 {@code getEntity()} 通常是弹射物本身，
     * 需进一步取 {@code getOwner()} 才是持破敌之眼的发射者。
     */
    public static Entity resolveAttacker(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        if (attacker instanceof Projectile projectile) {
            attacker = projectile.getOwner();
        }
        return attacker;
    }

    /**
     * 检查 LivingEntity 是否持有破敌之眼附魔且满足能力门禁。
     * <p>
     * 玩家需同时满足"冒险饰品激活 + piercing_gaze 能力可用"；
     * 非玩家实体（理论上不会持有，但保留兼容）只检查附魔。
     */
    public static boolean hasPiercingGaze(LivingEntity entity) {
        // AdventurePower.hasPiercingGaze 已含 (isAdventurer||isFullyUnlocked) && isAbilityEnabled 检查
        // isAbilityEnabled 内部已蕴含 isAbilityUnlocked（enabledAbilityCache.contains），无需再查 isAbilityAvailable
        return AdventurePower.hasPiercingGaze(entity);
    }

    /**
     * 破敌之眼穿透门禁统一入口：攻击者持有破敌之眼 + 非友伤 + 非玩家目标（PVP 禁用）。
     * <p>
     * Layer 0（Player.attack）/ Layer 1（isInvulnerableTo）/ Layer 2（hurt RETURN）
     * 三个注入点共用此方法，门禁规则单一来源——新增排除条件只改这一处。
     *
     * @param source 伤害源（内部追溯真正的攻击者）
     * @param target 受击目标
     * @return true 表示这是一次应当穿透的破敌之眼攻击
     */
    public static boolean shouldPierce(DamageSource source, LivingEntity target) {
        // PVP 禁用：穿透三连会绕过玩家 hurt 被取消时的保护（PVP 保护类模组），
        // 且觉醒禁无敌帧让对手无敌帧失效——对玩家目标一律不穿透
        if (target instanceof Player) {
            return false;
        }
        Entity attacker = resolveAttacker(source);
        if (!(attacker instanceof LivingEntity living)) {
            return false;
        }
        if (!hasPiercingGaze(living)) {
            return false;
        }
        // 友好火力保护：不穿透自己驯服生物
        if (FriendlyFireProtection.isOwnerTarget(living, target)) {
            return false;
        }
        return true;
    }

    /**
     * 手动 post {@link LivingHurtEvent}，返回取 {@code max(原值, 事件值)} 后的有效伤害。
     * <p>
     * 破敌之眼下伤害只能涨不能降--淬魂/影杀 等追加的伤害保留，Boss 限伤被忽略。
     * 即使事件被其他 mod 取消，破敌之眼仍穿透（万能钥匙不受外部取消影响），
     * 故不检查 {@code event.isCanceled()}。
     *
     * @param target 受击目标
     * @param source 伤害源
     * @param amount 原始伤害量
     * @return 经事件处理、取 max 后的有效伤害量
     */
    public static float postHurtEvent(LivingEntity target, DamageSource source, float amount) {
        LivingHurtEvent event = new LivingHurtEvent(target, source, amount);
        MinecraftForge.EVENT_BUS.post(event);
        return Math.max(amount, event.getAmount());
    }

    /**
     * 直写伤害 - 调用 {@link LivingEntity#actuallyHurt} 绕过 hurt() 内的护甲/无敌判定。
     * <p>
     * 通过 {@link PiercingGazeLivingEntityAccessor} 的 {@code @Invoker} 调用，
     * Java 虚分派会一路走到 {@code LivingEntity.actuallyHurt}（绝大多数 Boss 未重写此方法），
     * 完全绕过 Boss 在 hurt() 中设置的 vulnerable/护盾/角度等防御关卡。
     *
     * @param target 目标实体
     * @param source 伤害源
     * @param amount 伤害量（建议为 {@link #postHurtEvent} 取 max 后的值）
     */
    public static void invokeActuallyHurt(LivingEntity target, DamageSource source, float amount) {
        ((PiercingGazeLivingEntityAccessor) target).invokeActuallyHurt(source, amount);
    }

    /**
     * 穿透后兜底 - 血量直写 + 清自定义无敌字段。
     * <p>
     * <b>血量直写兜底</b>：部分 Boss（如亚波伦 RevelationFix）通过注入 {@code setHealth()}
     * 在 {@code actuallyHurt()} 后将血量恢复至损伤前水平。若检测到血量未实际扣除，
     * 强制直写 {@code DataItem.value} 字段绕过 {@code SynchedEntityData.set()} 及一切
     * {@code setHealth()} 覆写拦截。
     * <p>
     * <b>清自定义无敌字段</b>：部分 Boss（如 Goety Apostle）在 {@code actuallyHurt()} 中设置
     * {@code moddedInvul} 等字段，不清除会导致下次 hurt() 检测到 >0 直接 return false 且不调
     * super.hurt()，锁死影杀 NBT 影子血量更新。
     *
     * @param target 目标实体
     * @param effectiveAmount 实际生效的伤害量
     * @param healthBefore actuallyHurt 执行前的血量（用于检测血量是否被恢复）
     */
    public static void afterPierceFallback(LivingEntity target, float effectiveAmount, float healthBefore) {
        // 架空参照读数：自定义血条 Boss（亚波伦）原版槽被架空，getHealthDirect 读到不动值，
        // 会导致"血量未下降"检测恒成立而每击触发直写兜底（数值错位）；取真实血量判断
        if (effectiveAmount > 0.0F && HealthUtil.getEffectiveHealth(target) >= healthBefore && target.isAlive()) {
            DebugLog.piercingGaze("[破敌] 穿透后血量未降（{} >= {}）-> 直写兜底 {}",
                HealthUtil.getEffectiveHealth(target), healthBefore,
                Math.max(0.0F, healthBefore - effectiveAmount));
            // v1.4.2：五层引擎（磨血语义）--L3/L4 覆盖静态 Map/加密存储型高级 Boss；
            // 全层失败退 raw（与原 setAllHealthLikeRaw 行为等价）
            BloodWriteEngine.execute(target, Math.max(0.0F, healthBefore - effectiveAmount));
        }
        InvulClearUtil.clearCustomInvulTimers(target);
    }

    /**
     * 穿透反馈：屏障破碎音效 + 玻璃碎片粒子（服务端广播）。
     * <p>
     * <b>只由穿透分支显式调用</b>（Layer 0 穿透三连 / Layer 2 情况 A 与 posted+blocked）——
     * 三个结算收口（postHurtEvent / invokeActuallyHurt / afterPierceFallback）同时被
     * Layer 2 情况 B（原版管线正常结算的普通命中）调用，不能作为反馈挂载点，
     * 否则破敌之眼持有者的每次普通命中都会误触发"屏障破碎"。
     * <p>
     * 节流：{@link WeakHashMap} 按"同目标同 tick"只反馈一次（一次穿透三连
     * 多次调用不刷屏），实体 GC/卸载后条目自动释放，不持久化不泄漏。
     */
    private static final Map<Entity, Long> LAST_PIERCE_FEEDBACK_TICK =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 穿透反馈（服务端广播，仅穿透分支调用） */
    public static void pierceFeedback(LivingEntity target) {
        if (!ModConfig.PIERCING_GAZE_FEEDBACK_ENABLED.get()) return;
        if (!(target.level() instanceof ServerLevel serverLevel)) return;

        // 同目标同 tick 只反馈一次（穿透三连不刷屏）
        long now = serverLevel.getGameTime();
        Long last = LAST_PIERCE_FEEDBACK_TICK.get(target);
        if (last != null && last == now) return;
        LAST_PIERCE_FEEDBACK_TICK.put(target, now);

        // "屏障碎了"：玻璃破碎音效 + 玻璃碎片粒子
        serverLevel.playSound(null, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
            SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.0F, 1.1F);
        int count = ModConfig.PIERCING_GAZE_FEEDBACK_PARTICLE_COUNT.get();
        if (count > 0) {
            serverLevel.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.GLASS_PANE)),
                target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                count, 0.4, 0.4, 0.4, 0.1);
        }
    }
}
