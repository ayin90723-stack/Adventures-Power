package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 休养生息 — 脱战后直写 SynchedEntityData 回血 + 恢复饱食度。
 * 解锁条件：2 里程碑
 * 成长公式：amplifier = base + step × ((count - required) / 2)
 * 默认：里程碑2=1HP/3s, 4=2HP/3s, 10=5HP/3s
 * <p>
 * 不使用药水效果（addEffect），避免被 MobEffectEvent / removeAllEffects 拦截。
 */
public class RapidRecoveryAbility implements Ability {

    @Override
    public String id() {
        return "rapid_recovery";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.rapid_recovery");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.rapid_recovery.desc");
    }

    /**
     * 返回直写回血的 amplifier 等级。
     * 每 2 个里程碑升一级，handler 按 (amplifier + 1) HP/3s 执行。
     */

    private int countAtUnlock = 2;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        int steps = (count - countAtUnlock) / 2;
        return ModConfig.RAPID_RECOVERY_AMPLIFIER_BASE.get()
            + ModConfig.RAPID_RECOVERY_AMPLIFIER_STEP.get() * steps;
    }
}
