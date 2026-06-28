package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拒绝篡改 —— 拦截 {@link AttributeInstance#setBaseValue(double)}，
 * 阻止外部将玩家属性（尤其是最大血量）设为异常低的值。
 * <p>
 * <b>归属查找</b>：不再依赖 {@code RejectHealthManipMixin} 的 {@code trackAttrOwner}
 * （仅拦截 {@code LivingEntity.getAttribute()}，无法覆盖直接访问 {@code AttributeMap}
 * 或其他绕过路径），改为遍历在线玩家反向匹配 AttributeInstance。
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

        // 遍历在线玩家查找此 AttributeInstance 的所有者
        // 替代旧的 ATTR_OWNER 映射——后者仅在 LivingEntity.getAttribute()
        // 被调用时建立，绕过该方法的路径（如直接访问 AttributeMap）会漏掉
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (Player player : server.getPlayerList().getPlayers()) {
            if (player.level().isClientSide()) continue;
            AttributeInstance playerMaxHealth = player.getAttribute(Attributes.MAX_HEALTH);
            if (playerMaxHealth != self) continue;

            // 找到所有者，检查能力
            AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
                if (progress.isFullyUnlocked()
                      && progress.isAbilityEnabled("reject_manip")) {
                    ci.cancel();
                }
            });
            return; // 已匹配，无需继续遍历
        }
    }
}
