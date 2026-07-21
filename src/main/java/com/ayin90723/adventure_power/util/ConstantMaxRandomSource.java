package com.ayin90723.adventure_power.util;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 恒最大 RandomSource - 所有随机调用返回最大值。
 * <p>
 * 用于"满载而归"觉醒效果：注入 LootContext 后，让 SetItemCountFunction 等
 * 取掉落表最大数量，而非随机值。仅在 {@link com.ayin90723.adventure_power.handler.LootAllHandler#AWAKEN}
 * 标志位为 true 时由 {@code LootContextBuilderMixin} 注入。
 * <p>
 * 取值策略：
 * <ul>
 *   <li>{@code nextInt(bound)} 返回 {@code bound-1} -- UniformInt 取 max，数量最大化</li>
 *   <li>{@code nextInt(origin, bound)} 返回 {@code bound-1}</li>
 *   <li>{@code nextFloat/nextDouble} 返回接近 1.0 的最大值 -- 附魔等选索引走 nextInt 已最大化</li>
 *   <li>{@code nextBoolean} 返回 true</li>
 * </ul>
 * 注意：entry/pool 级 LootItemCondition 已被 canRun Mixin 绕过，故概率条件不执行，
 * nextFloat 的取值不会影响条件判断。
 */
public final class ConstantMaxRandomSource implements RandomSource {

    public static final ConstantMaxRandomSource INSTANCE = new ConstantMaxRandomSource();

    private ConstantMaxRandomSource() {}

    @Override
    public RandomSource fork() {
        return INSTANCE;
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return ConstantMaxFactory.INSTANCE;
    }

    @Override
    public void setSeed(long seed) {
        // 恒最大不依赖种子
    }

    @Override
    public int nextInt() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int nextInt(int bound) {
        return bound <= 0 ? 0 : bound - 1;
    }

    @Override
    public int nextInt(int origin, int bound) {
        if (bound <= origin) return origin;
        return bound - 1;
    }

    @Override
    public long nextLong() {
        return Long.MAX_VALUE;
    }

    @Override
    public boolean nextBoolean() {
        return true;
    }

    @Override
    public float nextFloat() {
        return 0.99999994F; // Float 最大 < 1.0，避免越界
    }

    @Override
    public double nextDouble() {
        return 0.9999999999999999; // Double 最大 < 1.0
    }

    @Override
    public double nextGaussian() {
        return 0.0;
    }

    /** 常量 PositionalRandomFactory，所有方法返回 ConstantMaxRandomSource。 */
    private static final class ConstantMaxFactory implements PositionalRandomFactory {
        static final ConstantMaxFactory INSTANCE = new ConstantMaxFactory();

        @Override
        public RandomSource fromHashOf(String name) {
            return ConstantMaxRandomSource.INSTANCE;
        }

        @Override
        public RandomSource at(int x, int y, int z) {
            return ConstantMaxRandomSource.INSTANCE;
        }

        @Override
        public void parityConfigString(StringBuilder builder) {
            // 无操作
        }
    }
}
