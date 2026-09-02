package com.ayin90723.adventure_power.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 假死画面重生按钮收尾（2026-09-02，用户实测定案）——原版 DeathScreen「重生」按钮回调
 * （{@code LocalPlayer.m_7583_}）只发 PERFORM_RESPAWN 包、<b>不关画面</b>：画面关闭依赖
 * 服务端 RespawnPacket 到达；重生门禁（{@code PlayerListRespawnGateMixin}，backup&gt;0
 * 拒绝未死之身）拒发该包 → 客户端画面永开（"直接点重生不行，要绕回「回到标题」确认框"——
 * 确认框的「重生」分支是 m_7583_()+setScreen(null)，唯一能关画面的原版路径；用户实测
 * 绕道该路径即原地恢复）。
 * <p>
 * 本 Mixin 把确认框分支的收尾补到 {@code m_7583_} 通用路径：清死态残留
 * （dead/deathTime/死亡姿态——切断原版 setScreen(null) 被 isDeadOrDying 翻译回
 * DeathScreen 的链）+ 关闭画面——直接点「重生」同样原地恢复（服务端门禁拒绝生效，
 * 玩家坐标零偏移）。真死流程不受影响：服务端随后发 RespawnPacket 正常重建重生。
 * <p>
 * 收尾条件（防御误伤）：仅在死亡画面开启或客户端死态残留（dead/deathTime/DYING
 * 任一）时执行——正常游戏里任何 mod 调 m_7583_ 均无副作用。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(LocalPlayer.class)
public class ClientRespawnMixin {

    @Inject(method = "m_7583_", at = @At("RETURN"))
    private void adventure_power$finishFakeRespawn(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        var acc = (LivingEntityFieldsAccessor) self;
        Minecraft mc = Minecraft.getInstance();
        boolean deadState = acc.adventure_power$isDead()
            || acc.adventure_power$getDeathTime() > 0
            || self.getPose() == Pose.DYING;
        if (!(mc.screen instanceof DeathScreen) && !deadState) {
            return; // 非死亡语境（第三方 mod 调用等）：零副作用
        }
        if (acc.adventure_power$isDead()) {
            acc.adventure_power$setDead(false);
        }
        if (acc.adventure_power$getDeathTime() > 0) {
            acc.adventure_power$setDeathTime(0);
        }
        if (self.getPose() == Pose.DYING) {
            self.setPose(Pose.STANDING);
        }
        mc.setScreen(null);
        // 收尾窗口（600ms，防御各 tick 驱动）——mixin 类内禁止非 private static 字段，
        // 标记放 handler（DeathScreenAutoDismissHandler），此处单向写
        com.ayin90723.adventure_power.handler.DeathScreenAutoDismissHandler
            .setFakeRespawnFinisherUntil(System.currentTimeMillis() + 600L);
    }
}
