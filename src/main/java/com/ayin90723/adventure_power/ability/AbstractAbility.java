package com.ayin90723.adventure_power.ability;

/**
 * 能力基类 - 封装 {@code countAtUnlock} 字段与 {@code setCountAtUnlock} 样板。
 * <p>
 * 子类只需实现 {@link #id()} / {@link #name()} / {@link #description()}，
 * 并按需覆写 {@link #value(int)}。默认 {@code value()} 返回 -1（无成长）。
 * <p>
 * 线性成长能力继承 {@link LinearGrowthAbility}，阶梯成长能力继承
 * {@link StepGrowthAbility}，可省去 {@code value()} 公式样板。
 *
 * @see LinearGrowthAbility
 * @see StepGrowthAbility
 */
public abstract class AbstractAbility implements Ability {

    /** 该能力解锁所需的里程碑数；由 MilestoneRegistry 加载 JSON 后覆盖，构造值为兜底 */
    protected int countAtUnlock;

    protected AbstractAbility(int countAtUnlock) {
        this.countAtUnlock = countAtUnlock;
    }

    @Override
    public void setCountAtUnlock(int n) {
        this.countAtUnlock = n;
    }

    @Override
    public float value(int count) {
        return -1;
    }
}
