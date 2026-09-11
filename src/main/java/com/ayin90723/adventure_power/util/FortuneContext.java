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
    private static final ThreadLocal<Long> SET_AT_TICK = ThreadLocal.withInitial(() -> 0L);

    /**
     * @param gameTime 当前 tick（服务端主线程），用于时效校验——上下文必须跨过
     *        BreakEvent 回调存活到掉落计算（回调返回后才发生），无法在"破坏完成
     *        栈尾"清理；消费侧（{@code FortuneFavorMixin}）只认同 tick 上下文，
     *        把"陈旧上下文继承上一位玩家时运加成"的窗口从"直到下一次破坏/登出"
     *        收窄到"破坏发生的同一 tick 内"
     */
    public static void setBreaker(Player player, long gameTime) {
        CURRENT_BREAKER.set(player);
        SET_AT_TICK.set(gameTime);
    }

    /**
     * 消费侧取用（带 tick 时效校验；过期返回 null 视为无上下文）。
     * 当前 tick 从 breaker 所在 level 取（Mixin 消费点无 server 引用可用）。
     * 容差 ±1 tick（±1 防跨 tick 边界误杀正常掉落链；即破坏后下一 tick 内的
     * 查询仍放行——收窄非彻底消除，再往后的陈旧上下文不再继承）。
     */
    public static Player getBreaker() {
        Player player = CURRENT_BREAKER.get();
        if (player == null) return null;
        return Math.abs(player.level().getGameTime() - SET_AT_TICK.get()) <= 1 ? player : null;
    }

    public static void setAwakened(boolean val) { AWAKENED.set(val); }
    public static boolean isAwakened() { return AWAKENED.get(); }

    public static void clear() {
        CURRENT_BREAKER.remove();
        AWAKENED.remove();
        SET_AT_TICK.remove();
    }
}
