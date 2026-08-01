package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.network.NetworkHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 空中二段跳客户端输入检测。
 * <p>
 * 钩子使用 {@link MovementInputUpdateEvent}（原版输入更新后、aiStep 前，与原版跳跃同 tick 生效），
 * 比 ClientTickEvent.END 更早，二段跳与原版跳跃无缝衔接，无 1 tick 延迟，手感丝滑。
 * </p>
 * <p>
 * 采用<b>边沿检测</b>：追踪 {@code input.jump}「按下瞬间」，按一下跳一次，松开再按再触发。
 * 地面起跳仍走原版（input.jump 持续由原版处理），空中跳必须松开空格再按。
 * </p>
 * <p>
 * <b>客户端跳数限制</b>：维护 {@code clientJumpsUsed}，落地清零，空中仅允许跳 1 次。
 * </p>
 * <p>
 * <b>御风</b>：觉醒且疾跑时，二段跳附加水平冲刺（{@link VoidStepMovement#applyJump} dash=true）。
 * 松开疾跑 = 纯垂直二段跳，按住疾跑 = 带水平冲刺，玩家可自由选择。
 * </p>
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class JumpInputHandler {
    /** 客户端已消耗的空中跳跃次数，落地清零，空中仅允许 1 次 */
    private static int clientJumpsUsed = 0;
    /** 上一 tick 的 input.jump 状态，用于边沿检测 */
    private static boolean wasJumpDown = false;
    /** 上一 tick 的玩家引用，用于检测死亡重生/跨维度导致的实体替换 */
    private static Player lastPlayer = null;

    @SubscribeEvent
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        Player player = event.getEntity();

        // 玩家引用变化（死亡重生/跨维度）-> 重置跳数与按键状态
        if (player != lastPlayer) {
            lastPlayer = player;
            clientJumpsUsed = 0;
            wasJumpDown = false;
        }

        boolean onGround = player.onGround();
        // 落地 -> 重置跳数
        if (onGround) {
            clientJumpsUsed = 0;
        }

        // 边沿检测 input.jumping（原版跳跃标志）
        boolean jumpDown = event.getInput().jumping;
        boolean jumpEdge = jumpDown && !wasJumpDown;
        wasJumpDown = jumpDown;

        // 门禁：必须已激活冒险者 + 已解锁虚空踏步能力
        var progress = AdventureProgressCapability.getAdventureProgress(player);
        boolean abilityReady = progress.map(p -> p.isAdventurer() && p.isAbilityEnabled(AbilityIds.VOID_STEP)).orElse(false);
        // 觉醒状态（御风）
        boolean awakened = progress.map(p -> p.isFullyUnlocked()).orElse(false);

        if (!abilityReady) return;

        if (jumpEdge
            && !onGround
            && !player.getAbilities().flying
            && !player.isPassenger()
            && !player.isInWater()
            && !player.isFallFlying()   // 鞘翅滑翔不触发二段跳，避免干扰滑翔物理
            && !player.onClimbable()    // 攀爬（梯子/藤蔓）不触发二段跳
            && clientJumpsUsed < 1) {

            // 客户端预测：Y 直接覆盖（与服务端同公式），御风在觉醒+疾跑时附加冲刺
            float power = VoidStepMovement.calculateJumpPower(player);
            boolean dash = awakened && player.isSprinting();
            VoidStepMovement.applyJump(player, power, dash);

            clientJumpsUsed++;
            // 发送请求到服务端（服务端只设 Y，不冲刺）
            NetworkHandler.sendDoubleJumpRequest();
        }
    }
}
