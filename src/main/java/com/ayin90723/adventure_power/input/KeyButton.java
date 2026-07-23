package com.ayin90723.adventure_power.input;

/**
 * 按键状态追踪器，封装两套重复样板：
 * <ul>
 *   <li>玩家引用变化时重置按键状态（防止死亡重生/跨维度后旧状态残留）</li>
 *   <li>按键上升沿检测（按下瞬间返回 true 一次，按住不重复）</li>
 * </ul>
 * <p>
 * 用法：
 * <pre>{@code
 * private static final KeyButton myKey = new KeyButton();
 *
 * // 玩家引用变化时：
 * myKey.reset();
 *
 * // 每帧检测：
 * if (myKey.consumePress(KEY_MAPPING.isDown())) {
 *     // 按下瞬间触发一次
 * }
 * }</pre>
 */
public class KeyButton {
    private boolean lastPressed;

    /**
     * 重置按键状态（玩家引用变化时调用）。
     * 将上次按下状态置为 false，确保新玩家实体首次按键能被正确检测。
     */
    public void reset() {
        lastPressed = false;
    }

    /**
     * 检测按键按下瞬间（上升沿）。
     *
     * @param isKeyDown 当前帧按键是否按下
     * @return true 当按键从"松开"变为"按下"的瞬间；按住不动或松开均返回 false
     */
    public boolean consumePress(boolean isKeyDown) {
        boolean edge = isKeyDown && !lastPressed;
        lastPressed = isKeyDown;
        return edge;
    }
}
