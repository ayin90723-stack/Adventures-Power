package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.mixin.LivingEntityFieldsAccessor;
import com.ayin90723.adventure_power.util.DebugLog.EngineCaller;
import com.ayin90723.adventure_power.util.probe.gate.GateAnalyzer;
import com.mojang.logging.LogUtils;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.slf4j.Logger;

/**
 * 绕过 hurt() 管线的致死收尾：补完原版 {@code die()} 结算（掉落/经验/事件/死亡动画）。
 *
 * <h3>根因（v1.4.5 修正）</h3>
 * 原版 {@code die()} 在服务端的唯一调用点是 {@code hurt()} 尾部。破敌穿透三连
 * （actuallyHurt 直调 / 兜底直写）与审判的引擎磨血兜底都绕过了 hurt()--目标血量
 * 归零后无人调 die，实体只会走 {@code tickDeath()} 在 20 tick 后被
 * {@code remove(KILLED)} 静默移除：<b>零掉落、零经验、零 LivingDeathEvent</b>
 * （墓碑/任务/击杀统计模组全部感知不到，满载而归也不触发）。v1.4.3 十七轮
 * "磨血不主动调 die"定调中"写 0 后对面自然死接管"的前提即错--对面自然死是个别
 * Boss 自带死亡检查的个例，普通怪没有任何 0 血自死逻辑。
 *
 * <h3>修正后的原则</h3>
 * <ul>
 *   <li>磨血区间（写值 &gt; 0）不碰死亡流程（不变）；</li>
 *   <li>致死一刀补完原版 die（本工具）--只做 hurt() 尾部本来会做的事，死亡表现
 *       与正常击杀完全一致（DYING 姿态 + 20 tick 死亡动画 + 死亡音效），<b>不是处决</b>；</li>
 *   <li>拦死者（die/isAlive/isDeadOrDying 有模组层覆写）不裸调 die--会触发对面的
 *       中断/复活逻辑（十七轮半开门同款根因）。处决只在影杀（玩家没开影杀就是
 *       没要这个服务，能力开关自由选择）；防复活在禁疗之触（FORCE_KILL 链）。</li>
 * </ul>
 *
 * <h3>幂等</h3>
 * dead 标志经 {@link LivingEntityFieldsAccessor} 直读自守卫--{@code die()} 的事件
 * post 在其内部 dead 守卫<b>之前</b>，靠 die 自身守卫挡不住第二次调用（会双发
 * LivingDeathEvent，击杀回馈类监听器双结算）。
 */
public final class DeathFinalizer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DeathFinalizer() {
    }

    /**
     * 补完原版死亡结算：目标处于"血量归零但 die 未执行"状态时调 {@code die(source)}。
     * <p>
     * 调用时机 = 各绕过管线击杀路径的致死一刀（破敌穿透三连收口 / 审判兜底写 0）；
     * 已正规死亡（dead 置位）、已移除、非致死态均直接返回，可安全地在每次结算尾部调用。
     *
     * @param target 击杀目标
     * @param source 击杀伤害源（决定 die 的掉落归属：KILLER_ENTITY=source.getEntity()，
     *               抢夺等级经它结算；死亡消息同源）
     * @param caller 调用方能力（日志归属，同引擎探针的调用方归属原则）
     */
    public static void completeVanillaDeath(LivingEntity target, DamageSource source, EngineCaller caller) {
        try {
            if (target.isRemoved()) return;
            if (((LivingEntityFieldsAccessor) target).adventure_power$isDead()) return;
            if (!target.isDeadOrDying()) return;

            // 归属补偿（拦死者同样生效：对面若稍后自然死，lastHurtBy 已就位，掉落按玩家
            // 击杀结算；穿透无敌帧早退路径不经过 hurt() 的归属块，此处兜住。
            // setLastHurtByPlayer 同时置 lastHurtByPlayerTime，killed_by_player 条件与经验依赖它）
            Entity attacker = source.getEntity();
            if (attacker == null) attacker = source.getDirectEntity();
            if (attacker instanceof Projectile projectile) attacker = projectile.getOwner();
            if (attacker instanceof Player player) {
                target.setLastHurtByMob(player);
                target.setLastHurtByPlayer(player);
            }

            // 拦死者门禁：die/isAlive/isDeadOrDying 有模组层覆写即跳过（per-class 缓存，
            // 普通怪首次反射扫描后终身命中缓存）。hurt/remove/kill 覆写不算--拦伤害
            // 不拦死亡（fdbosses 调 super 扣血型）/死亡表演延迟移除都不阻止 die 完整走完
            if (GateAnalyzer.analyze(target).hasDeathInterception()) {
                DebugLog.deathFinalize(caller, "[死亡结算] {} 存在拦截型死亡覆写（die 不调 super / liveness 覆写），跳过 die 补完（处决=影杀）", target);
                return;
            }

            DebugLog.deathFinalize(caller, "[死亡结算] {} 血量归零但 die 未执行（绕过 hurt 管线击杀）-> 补完原版死亡", target);
            target.die(source);
        } catch (Exception e) {
            // 分段异常保护（与影杀饱和链同款纪律）：死亡补完失败不阻断调用方后续结算
            LOGGER.error("[DeathFinalizer] 死亡结算补完异常 target={}", target, e);
        }
    }
}
