package com.ayin90723.adventure_power.input;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.network.NetworkHandler;
import com.ayin90723.adventure_power.ui.ActiveSkillHudOverlay;
import com.ayin90723.adventure_power.ui.AdventureMainScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent.Key;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class InputHandler {
   // 按键状态追踪器（封装玩家引用变化重置 + 上升沿检测）
   private static final KeyButton abilityMgmt = new KeyButton();
   private static final KeyButton skillSwitch = new KeyButton();
   private static final KeyButton skillActivate = new KeyButton();

   // 玩家引用追踪（按重置分组）
   private static Player lastPlayer = null;
   private static Player lastActiveSkillPlayer = null;

   @SubscribeEvent
   public static void onKeyInput(Key event) {
      if (Minecraft.getInstance().player != null) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.level != null && mc.player != null) {
            // 玩家引用变化（死亡重生/跨维度）-> 重置面板按键状态
            if (mc.player != lastPlayer) {
               lastPlayer = mc.player;
               abilityMgmt.reset();
            }
            // P 键：冒险统一面板（开关式：已打开则关闭，避免重建面板跳回能力 tab）
            if (abilityMgmt.consumePress(ClientModEvents.ABILITY_MANAGEMENT.isDown())) {
               if (mc.screen instanceof AdventureMainScreen) {
                  mc.setScreen(null);
                  return;
               }
               if (AdventureProgressCapability.isAdventurer(mc.player)
                   || AdventureProgressCapability.isFullyUnlocked(mc.player)) {
                  mc.setScreen(new AdventureMainScreen());
               } else {
                  AdventureProgressCapability
                     .requestSyncAndOpenScreen(AdventureProgressCapability.PENDING_ABILITY);
               }
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
}
