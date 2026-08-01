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
        // 下限保护：乱序解锁里程碑时 count - countAtUnlock 可能为负，
        // 否则会出现反向效果（如坚韧之躯裁剪最大生命、鸿运当头扣附魔等级）
        int growth = Math.max(0, count - countAtUnlock);
        return base() + perMilestone() * growth;
    }
}
