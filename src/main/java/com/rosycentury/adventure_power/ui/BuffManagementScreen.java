package com.rosycentury.adventure_power.ui;

import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.network.NetworkHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Buff 管理菜单 — 按 O 键打开，管理冒险饰品对正面效果的永续状态。
 * 默认所有正面效果均被延长（永续），单击可切换为正常到期。
 * <p>
 * 当效果数量超出屏幕时自动启用垂直滚动。
 */
public class BuffManagementScreen extends Screen {
   private Set<String> excludedEffects = new HashSet<>();
   private final List<MobEffectInstance> displayEffects = new ArrayList<>();
   private int leftX;
   private int panelWidth = 240;
   private int refreshTick = 0;
   private boolean ready = false;
   private boolean buffExtendEnabled = true;
   private static final int ROW_HEIGHT = 22;
   private static final int TOP_Y = 40;
   private static final int BOTTOM_PADDING = 36;
   private static final int COLOR_GREEN = 0x55FF55;
   private static final int COLOR_GRAY = 0xAAAAAA;
   private static final int COLOR_WHITE = 0xFFFFFF;
   private static final int COLOR_YELLOW = 0xFFFF55;

   /** 当前滚动偏移（像素） */
   private int scrollOffset;

   public BuffManagementScreen() {
      super(Component.translatable("screen.adventure_power.buff"));
   }

   @Override
   protected void init() {
      super.init();
      this.leftX = this.width / 2 - panelWidth / 2;
      NetworkHandler.sendBuffBlacklistRequest();
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         mc.player.getCapability(AdventureProgressCapability.CAPABILITY).ifPresent(progress -> {
            this.buffExtendEnabled = progress.isAbilityEnabled("perpetual_blessing");
         });
      }
      refreshDisplayEffects();
   }

   public void onSyncReceived(Set<String> blacklist) {
      this.excludedEffects = blacklist != null ? new HashSet<>(blacklist) : new HashSet<>();
      this.ready = true;
      refreshDisplayEffects();
   }

   private void refreshDisplayEffects() {
      displayEffects.clear();
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null) return;
      for (MobEffectInstance effect : mc.player.getActiveEffects()) {
         if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
            displayEffects.add(effect);
         }
      }
      displayEffects.sort((a, b) -> Integer.compare(b.getDuration(), a.getDuration()));
   }

   private int visibleHeight() { return this.height - TOP_Y - BOTTOM_PADDING; }
   private int contentHeight() { return displayEffects.size() * ROW_HEIGHT; }
   private int maxScroll() { return Math.max(0, contentHeight() - visibleHeight()); }
   private void clampScroll() { scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll())); }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
      scrollOffset -= (int)(scrollDelta * ROW_HEIGHT);
      clampScroll();
      return true;
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      refreshTick++;
      if (refreshTick % 20 == 0) {
         refreshDisplayEffects();
      }

      this.renderBackground(graphics);

      // 标题
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, COLOR_WHITE);

      if (!buffExtendEnabled) {
         graphics.drawCenteredString(this.font,
            Component.literal("§c⚠ Buff延长已在能力面板中关闭 — 以下设置不生效")
               .withStyle(ChatFormatting.RED),
            this.width / 2, 27, 0xFF5555);
      }

      if (!ready) {
         graphics.drawCenteredString(this.font,
            Component.literal("§7正在加载排除列表..."),
            this.width / 2, TOP_Y + 20, COLOR_GRAY);
         super.render(graphics, mouseX, mouseY, partialTick);
         return;
      }

      if (displayEffects.isEmpty()) {
         graphics.drawCenteredString(this.font,
            Component.literal("§7暂无正面效果可管理"),
            this.width / 2, this.height / 2, COLOR_GRAY);
         super.render(graphics, mouseX, mouseY, partialTick);
         return;
      }

      // 列标题（固定位置）
      graphics.drawString(this.font, "●/○", leftX, TOP_Y - 14, COLOR_GRAY);
      graphics.drawString(this.font, "效果", leftX + 22, TOP_Y - 14, COLOR_GRAY);
      graphics.drawString(this.font, "剩余", leftX + 100, TOP_Y - 14, COLOR_GRAY);
      graphics.drawString(this.font, "状态", leftX + 155, TOP_Y - 14, COLOR_GRAY);

      int visibleRows = visibleHeight() / ROW_HEIGHT;
      int startRow = Math.max(0, scrollOffset / ROW_HEIGHT);
      int endRow = Math.min(displayEffects.size(), startRow + visibleRows + 1);

      for (int i = startRow; i < endRow; i++) {
         MobEffectInstance effect = displayEffects.get(i);
         int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;
         String effectId = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()).toString();
         boolean isExcluded = excludedEffects.contains(effectId);

         // 鼠标悬浮高亮
         if (mouseX >= leftX && mouseX <= leftX + panelWidth
               && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
            graphics.fill(leftX, y - 1, leftX + panelWidth, y + ROW_HEIGHT - 1, 0x22FFFFFF);
         }

         // 绿点/灰点
         String dot = isExcluded ? "§7○" : "§a●";
         graphics.drawString(this.font, dot, leftX + 5, y, isExcluded ? COLOR_GRAY : COLOR_GREEN);

         // 效果名 + 等级
         String name = effect.getEffect().getDisplayName().getString();
         if (effect.getAmplifier() > 0) {
            name += " " + toRoman(effect.getAmplifier() + 1);
         }
         graphics.drawString(this.font, name, leftX + 22, y, COLOR_WHITE);

         // 剩余时间
         int seconds = effect.getDuration() / 20;
         String timeStr = seconds >= 60
            ? String.format("%d:%02d", seconds / 60, seconds % 60)
            : String.format("0:%02d", seconds);
         graphics.drawString(this.font, timeStr, leftX + 100, y,
            effect.getDuration() < 60 ? COLOR_YELLOW : COLOR_WHITE);

         // 永续/正常到期
         String status = isExcluded
            ? Component.literal("正常到期").getString()
            : Component.literal("永续").getString();
         graphics.drawString(this.font, status, leftX + 140, y,
            isExcluded ? COLOR_GRAY : COLOR_GREEN);
      }

      // 滚动条
      int maxScroll = maxScroll();
      if (maxScroll > 0) {
         int barX = leftX + panelWidth + 4;
         int barHeight = visibleHeight();
         int thumbHeight = Math.max(16, barHeight * visibleHeight() / Math.max(1, contentHeight()));
         int thumbY = TOP_Y + (int)((barHeight - thumbHeight) * (float)scrollOffset / maxScroll);
         graphics.fill(barX, TOP_Y, barX + 4, TOP_Y + barHeight, 0x44FFFFFF);
         graphics.fill(barX, thumbY, barX + 4, thumbY + thumbHeight, 0xAAFFFFFF);
      }

      // 底部提示（固定位置）
      graphics.drawCenteredString(this.font,
         Component.literal("§7单击切换效果排除状态"),
         this.width / 2, this.height - 22, COLOR_GRAY);
      graphics.drawCenteredString(this.font,
         Component.literal("§7按 P 键打开能力管理面板"),
         this.width / 2, this.height - 10, COLOR_GRAY);

      super.render(graphics, mouseX, mouseY, partialTick);
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (button == 0) {
         for (int i = 0; i < displayEffects.size(); i++) {
            int y = TOP_Y + i * ROW_HEIGHT - scrollOffset;
            if (y < TOP_Y - ROW_HEIGHT || y > TOP_Y + visibleHeight()) continue;
            if (mouseX >= leftX && mouseX <= leftX + panelWidth
                  && mouseY >= y - 1 && mouseY < y + ROW_HEIGHT - 1) {
               MobEffectInstance effect = displayEffects.get(i);
               String effectId = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect()).toString();
               NetworkHandler.sendBuffToggle(effectId);
               return true;
            }
         }
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   private static String toRoman(int n) {
      return switch (n) {
         case 1 -> "I";
         case 2 -> "II";
         case 3 -> "III";
         case 4 -> "IV";
         case 5 -> "V";
         case 6 -> "VI";
         case 7 -> "VII";
         case 8 -> "VIII";
         case 9 -> "IX";
         default -> String.valueOf(n);
      };
   }
}
