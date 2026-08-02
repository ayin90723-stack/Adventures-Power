package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.network.NetworkHandler;
import com.ayin90723.adventure_power.ui.ActiveSkillHudOverlay;
import com.ayin90723.adventure_power.ui.AdventureMainScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent.Key;
import net.minecraftforge.client.event.InputEvent.MouseButton;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class InputHandler {
   // 按键状态追踪器（封装玩家引用变化重置 + 上升沿检测）
   private static final KeyButton skillSwitch = new KeyButton();
   private static final KeyButton skillActivate = new KeyButton();

   // 玩家引用追踪（按重置分组）
   private static Player lastActiveSkillPlayer = null;

   @SubscribeEvent
   public static void onKeyInput(Key event) {
      if (Minecraft.getInstance().player != null) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.level != null && mc.player != null) {
            // P 键：冒险统一面板（开关式：已打开则关闭，避免重建面板跳回能力 tab）
            // 注意：屏幕打开期间 KeyMapping.isDown() 恒 false（按键状态冻结 + setScreen 时
            // releaseAll 清空），原 consumePress 的"已打开则关闭"分支不可达——
            // 改用输入事件 key/action 直接匹配（action==1 = GLFW_PRESS，过滤长按 REPEAT）。
            // 仅匹配键盘类型绑定：InputEvent.Key 只由键盘触发，鼠标绑定走 onMouseButton
            InputConstants.Key bound = ClientModEvents.ABILITY_MANAGEMENT.getKey();
            if (bound.getType() == InputConstants.Type.KEYSYM
                && event.getKey() == bound.getValue() && event.getAction() == 1) {
               handleAbilityScreenKey(mc);
            }

            // 主动技能 - 门禁检查（体验预检，服务端另有校验）
            if (AdventureProgressCapability.isAdventurer(mc.player)
                || AdventureProgressCapability.isFullyUnlocked(mc.player)) {
               // 玩家引用变化 -> 重置技能按键状态
               if (mc.player != lastActiveSkillPlayer) {
                  lastActiveSkillPlayer = mc.player;
                  skillSwitch.reset();
                  skillActivate.reset();
               }
               // Y 键：切换技能（本地乐观更新 + 发包由服务端持久化，避免被后续 sync 覆盖）
               if (skillSwitch.consumePress(ClientModEvents.SKILL_SWITCH.isDown())) {
                  mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
                     if (progress.isAbilityEnabled(AbilityIds.ACTIVE_SKILL)) {
                        int next = progress.getActiveSkillIndex() == 0 ? 1 : 0;
                        progress.setActiveSkillIndex(next); // 0↔1 切换（本地即时反馈）
                        ActiveSkillHudOverlay.onSkillSwitched(mc.level.getGameTime());
                        NetworkHandler.sendSkillSwitch(next); // 服务端持久化 + 回同步
                     }
                  });
               }
               // G 键：释放技能
               if (skillActivate.consumePress(ClientModEvents.SKILL_ACTIVATE.isDown())) {
                  mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
                     if (progress.isAbilityEnabled(AbilityIds.ACTIVE_SKILL)) {
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

   /**
    * P 键打开/关闭冒险统一面板（键盘与鼠标绑定共用）。
    * 其他屏幕（聊天/物品栏等）打开时忽略，避免误触面板。
    */
   private static void handleAbilityScreenKey(Minecraft mc) {
      if (mc.screen instanceof AdventureMainScreen) {
         mc.setScreen(null);
      } else if (mc.screen == null) {
         if (AdventureProgressCapability.isAdventurer(mc.player)
             || AdventureProgressCapability.isFullyUnlocked(mc.player)) {
            mc.setScreen(new AdventureMainScreen());
         } else {
            AdventureProgressCapability
               .requestSyncAndOpenScreen(AdventureProgressCapability.PENDING_ABILITY);
         }
      }
   }

   /**
    * P 键（鼠标绑定）：InputEvent.Key 仅由键盘触发，玩家把 P 绑到鼠标键时
    * 键盘事件永远匹配不到——补一路鼠标事件（action==1 = GLFW_PRESS）。
    * 注意：InputEvent.MouseButton 在 MouseHandler.onPress 最开头触发（先于
    * screen.mouseClicked）——面板打开时的任何点击都会先进入本事件，若在此
    * 处理"关闭"会把面板点没且点击穿透到世界。故鼠标路径**只处理无屏幕时打开**，
    * 不处理关闭（关闭靠 ESC 或键盘 P）；面板打开时对鼠标 P 绑定玩家是 no-op，
    * 面板内点击正常工作。
    */
   @SubscribeEvent
   public static void onMouseButton(MouseButton event) {
      if (Minecraft.getInstance().player != null) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.level != null && mc.player != null && mc.screen == null) {
            InputConstants.Key bound = ClientModEvents.ABILITY_MANAGEMENT.getKey();
            if (bound.getType() == InputConstants.Type.MOUSE
                && event.getButton() == bound.getValue() && event.getAction() == 1) {
               handleAbilityScreenKey(mc);
            }
         }
      }
   }
}
