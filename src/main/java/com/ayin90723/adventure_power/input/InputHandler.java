package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.network.NetworkHandler;
import com.ayin90723.adventure_power.ui.AbilityManagementScreen;
import com.ayin90723.adventure_power.ui.ActiveSkillHudOverlay;
import com.ayin90723.adventure_power.ui.BuffManagementScreen;
import com.ayin90723.adventure_power.ui.MilestoneProgressScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent.Key;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class InputHandler {
   private static boolean lastBuffMgmtState = false;
   private static boolean lastAbilityMgmtState = false;
   private static Player lastPlayer = null;
   private static boolean lastSkillSwitchState = false;
   private static boolean lastSkillActivateState = false;
   private static Player lastActiveSkillPlayer = null;
   private static boolean lastMilestoneProgressState = false;
   private static Player lastMilestonePlayer = null;

   @SubscribeEvent
   public static void onKeyInput(Key event) {
      if (Minecraft.getInstance().player != null) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.level != null && mc.player != null) {
            // 玩家引用变化（死亡重生/跨维度）→ 重置按键追踪状态，
            // 防止旧按键状态残留导致重生后首次按键不触发
            if (mc.player != lastPlayer) {
               lastPlayer = mc.player;
               lastBuffMgmtState = false;
               lastAbilityMgmtState = false;
            }
            // Buff 管理菜单
            boolean bmState = ClientModEvents.BUFF_MANAGEMENT.isDown();
            if (bmState != lastBuffMgmtState) {
               lastBuffMgmtState = bmState;
               if (bmState) {
                  if (AdventureProgressCapability.isAdventurer(mc.player)
                      || AdventureProgressCapability.isFullyUnlocked(mc.player)) {
                     mc.setScreen(new BuffManagementScreen());
                  } else {
                     // Capability 为空 → 请求服务端同步，同步完成后自动打开屏幕
                     AdventureProgressCapability
                        .requestSyncAndOpenScreen(AdventureProgressCapability.PENDING_BUFF);
                  }
               }
            }
            // 能力管理菜单
            boolean amState = ClientModEvents.ABILITY_MANAGEMENT.isDown();
            if (amState != lastAbilityMgmtState) {
               lastAbilityMgmtState = amState;
               if (amState) {
                  if (AdventureProgressCapability.isAdventurer(mc.player)
                      || AdventureProgressCapability.isFullyUnlocked(mc.player)) {
                     mc.setScreen(new AbilityManagementScreen());
                  } else {
                     // Capability 为空 → 请求服务端同步，同步完成后自动打开屏幕
                     AdventureProgressCapability
                        .requestSyncAndOpenScreen(AdventureProgressCapability.PENDING_ABILITY);
                  }
               }
            }
            // M 键：冒险进度界面
            if (mc.player != lastMilestonePlayer) {
                lastMilestonePlayer = mc.player;
                lastMilestoneProgressState = false;
            }
            boolean mpState = ClientModEvents.MILESTONE_PROGRESS.isDown();
            if (mpState != lastMilestoneProgressState) {
                lastMilestoneProgressState = mpState;
                if (mpState) {
                    if (AdventureProgressCapability.isAdventurer(mc.player)
                        || AdventureProgressCapability.isFullyUnlocked(mc.player)) {
                        mc.setScreen(new MilestoneProgressScreen());
                    } else {
                        AdventureProgressCapability
                            .requestSyncAndOpenScreen(AdventureProgressCapability.PENDING_MILESTONE);
                    }
                }
            }

            // 主动技能 — 门禁检查
            if (AdventureProgressCapability.isFullyUnlocked(mc.player)) {
                // 玩家引用变化 → 重置追踪状态
                if (mc.player != lastActiveSkillPlayer) {
                    lastActiveSkillPlayer = mc.player;
                    lastSkillSwitchState = false;
                    lastSkillActivateState = false;
                }
                // Y 键：切换技能
                boolean swState = ClientModEvents.SKILL_SWITCH.isDown();
                if (swState != lastSkillSwitchState) {
                    lastSkillSwitchState = swState;
                    if (swState) {
                        mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
                            if (progress.isAbilityEnabled("active_skill")) {
                                int current = progress.getActiveSkillIndex();
                                progress.setActiveSkillIndex(current == 0 ? 1 : 0); // 0↔1 切换
                                ActiveSkillHudOverlay.onSkillSwitched(mc.level.getGameTime());
                            }
                        });
                    }
                }
                // G 键：释放技能
                boolean acState = ClientModEvents.SKILL_ACTIVATE.isDown();
                if (acState != lastSkillActivateState) {
                    lastSkillActivateState = acState;
                    if (acState) {
                        mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
                            if (progress.isAbilityEnabled("active_skill")) {
                                long currentTime = mc.level.getGameTime();
                                // 客户端预检：GCD
                                long gcdEnd = progress.getActiveSkillGcdEnd();
                                if (gcdEnd > 0 && currentTime < gcdEnd) return;
                                int idx = progress.getActiveSkillIndex();
                                // 客户端预检：对应技能冷却
                                if (idx == 0) {
                                    long cd = progress.getJudgmentCooldownEnd();
                                    if (cd > 0 && currentTime < cd) return;
                                } else {
                                    long cd = progress.getSanctuaryCooldownEnd();
                                    if (cd > 0 && currentTime < cd) return;
                                }
                                NetworkHandler.sendActiveSkill(idx);
                            }
                        });
                    }
                }
            }
         }
      }
   }
}
