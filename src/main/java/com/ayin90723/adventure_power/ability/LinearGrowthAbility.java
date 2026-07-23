package com.ayin90723.adventure_power.ability;

/**
 * 线性成长能力基类 - {@code value = base + perMilestone × (count - countAtUnlock)}。
 * <p>
 * 子类实现 {@link #base()} / {@link #perMilestone()}（通常从各自 {@code ModConfig} 字段读取），
 * 无需重复 {@code value()} 公式。
 */
public abstract class LinearGrowthAbility extends AbstractAbility {

    protected LinearGrowthAbility(int countAtUnlock) {
        super(countAtUnlock);
    }

    /** 基础数值（count == countAtUnlock 时的值） */
    protected abstract float base();

    /** 每多解锁一个里程碑的增量 */
    protected abstract float perMilestone();

    @Override
    public float value(int count) {
        return base() + perMilestone() * (count - countAtUnlock);
    }
}
