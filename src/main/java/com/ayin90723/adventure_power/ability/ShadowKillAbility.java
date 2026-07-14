package com.ayin90723.adventure_power.ability;

import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * 影杀 — 影子血量系统，归零时饱和式斩杀。
 * 解锁条件：10 里程碑
 * 数值从配置读取，默认：固伤 4，HP 比例 4%
 */
public class ShadowKillAbility implements Ability {

    @Override
    public String id() {
        return "shadow_kill";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.shadow_kill");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.shadow_kill.desc");
    }

    /**
     * 影杀是双参数能力（固伤 + 比例），handler 直接调用 {@link #flatDamage()} 和
     * {@link #hpRatio()} 获取两个配置值。{@code value()} 仅满足接口契约，实际不被调用。
     */

    private int countAtUnlock = 10;

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return flatDamage();
    }

    /** 每次攻击削减影子血量的固定值，从配置读取 */
    public int flatDamage() {
        return ModConfig.SHADOW_KILL_FLAT_DAMAGE.get();
    }

    /** 每次攻击额外削减目标最大生命值的比例，从配置读取 */
    public float hpRatio() {
        return ModConfig.SHADOW_KILL_HP_RATIO.get().floatValue();
    }
}
