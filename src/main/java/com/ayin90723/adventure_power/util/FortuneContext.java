package com.ayin90723.adventure_power.util;

import net.minecraft.world.entity.player.Player;

/**
 * 线程局部存储，用于在方块破坏→附魔查询之间传递玩家引用。
 * <p>
 * Mixin 无法直接在 EnchantmentHelper 中获取破坏者（ItemStack 不持有所有者的引用），
 * 通过此上下文在 BreakEvent 处理前设置玩家，供 Mixin 读取。
 */
public class FortuneContext {
    private static final ThreadLocal<Player> CURRENT_BREAKER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AWAKENED = ThreadLocal.withInitial(() -> false);

    public static void setBreaker(Player player) {
        CURRENT_BREAKER.set(player);
    }

    public static Player getBreaker() {
        return CURRENT_BREAKER.get();
    }

    public static void setAwakened(boolean val) { AWAKENED.set(val); }
    public static boolean isAwakened() { return AWAKENED.get(); }

    public static void clear() {
        CURRENT_BREAKER.remove();
        AWAKENED.remove();
    }
}
