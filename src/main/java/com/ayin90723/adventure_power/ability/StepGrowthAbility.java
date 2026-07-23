package com.ayin90723.adventure_power.ability;

/**
 * 阶梯成长能力基类 - {@code value = base + step × ((count - countAtUnlock) / divisor)}。
 * <p>
 * 默认 {@code divisor = 2}（每 2 个里程碑一跳）。子类实现 {@link #base()} / {@link #step()}。
 */
public abstract class StepGrowthAbility extends AbstractAbility {

    private final int divisor;

    protected StepGrowthAbility(int countAtUnlock) {
        this(countAtUnlock, 2);
    }

    protected StepGrowthAbility(int countAtUnlock, int divisor) {
        super(countAtUnlock);
        this.divisor = divisor;
    }

    /** 基础数值（count == countAtUnlock 时的值） */
    protected abstract float base();

    /** 每跳一阶的增量 */
    protected abstract float step();

    @Override
    public float value(int count) {
        int steps = (count - countAtUnlock) / divisor;
        return base() + step() * steps;
    }
}
