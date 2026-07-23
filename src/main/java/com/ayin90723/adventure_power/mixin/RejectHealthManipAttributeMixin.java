package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.util.RejectHealthManipUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拒绝篡改 - 拦截 {@link AttributeInstance#setBaseValue(double)}，
 * 阻止外部将玩家属性（尤其是最大血量）设为异常低的值。
 * <p>
 * <b>归属查找</b>：优先 O(1) 查 {@code RejectHealthManipUtil.ATTR_OWNER} 映射
 * （由 {@code RejectHealthManipMixin.trackAttrOwner} 在 getAttribute 时登记）；
 * ATTR_OWNER 未命中时（getAttribute 未被调用的路径）fallback 遍历在线玩家并补登记。
 * <p>
 * 阈值 6.0（3 颗心）：低于此值的 maxHealth 设定视为恶意操纵。
 * 终局玩家正常情况下 maxHealth 不会低于 20，装备变动也不会降至 6 以下。
 *
 * @see RejectHealthManipMixin
 */
@Mixin(value = AttributeInstance.class)
public class RejectHealthManipAttributeMixin {

    private static final double SUSPICIOUS_THRESHOLD = 6.0;

    @Inject(method = "m_22100_", at = @At("HEAD"), cancellable = true)
    private void rejectSetBaseValue(double newValue, CallbackInfo ci) {
        if (newValue >= SUSPICIOUS_THRESHOLD) return;

        AttributeInstance self = (AttributeInstance) (Object) this;

        // 优先 O(1) 查 ATTR_OWNER 映射（由 RejectHealthManipMixin.trackAttrOwner 在 getAttribute 时登记）
        LivingEntity owner = RejectHealthManipUtil.getOwner(self);
        if (owner instanceof Player player && !owner.level().isClientSide()) {
            checkAndReject(player, ci);
            return;
        }

        // fallback：ATTR_OWNER 未命中（getAttribute 未被调用的路径，如属性加载早期）-> 遍历在线玩家
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (Player player : server.getPlayerList().getPlayers()) {
            if (player.level().isClientSide()) continue;
            AttributeInstance playerMaxHealth = player.getAttribute(Attributes.MAX_HEALTH);
            if (playerMaxHealth != self) continue;

            // 补登记 ATTR_OWNER，下次命中 O(1)
            RejectHealthManipUtil.ATTR_OWNER.put(self, player);
            checkAndReject(player, ci);
            return; // 已匹配，无需继续遍历
        }
    }

    private static void checkAndReject(Player player, CallbackInfo ci) {
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if ((progress.isAdventurer() || progress.isFullyUnlocked())
                  && progress.isAbilityEnabled("reject_manip")) {
                ci.cancel();
            }
        });
    }
}
