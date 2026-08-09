package com.ayin90723.adventure_power.util;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * 类指针守卫 —— 对抗「类指针替换」型击杀（如终焉秩序维系者的 killPlayer：
 * 把玩家实体的 klass 指针替换为隐藏类，覆写 getHealth/isAlive/isDeadOrDying
 * 返回假值，绕过一切事件层与 Mixin 注入点）。
 * <p>
 * 原理（HotSpot x64 默认布局）：对象头 = mark word 8B（offset 0）+ klass 指针
 * （offset 8，默认压缩类指针 4B）。同一 JVM 内所有 {@code Player} 实例的 klass
 * 槽值相同——首次通过门禁时记录该值并验证（异类对象槽值不同），检测到实体
 * 类被替换后把槽值写回，恢复方法分派。
 * <p>
 * 安全设计：
 * <ul>
 *   <li>探测验证：记录时用「异类对象槽值不同」验证 offset 8 确实是 klass 槽，
 *       验证失败（非标准 JVM 布局）则整体禁用，不做任何写入。</li>
 *   <li>恢复后二次验证：{@code getClass()} 回到预期类才算成功；失败立即禁用
 *       守卫（防重复写入损坏内存），并记录日志。</li>
 *   <li>恢复是最后手段：{@code TrueHealthMixin} 的 remove 拦截负责先保实体，
 *       本类只负责把方法分派恢复原状。</li>
 * </ul>
 * <p>
 * <b>已知边界</b>：expectedClass 取第一个通过门禁的玩家类。若同服出现「合法」
 * 的异类玩家子类（职业/种族类模组且未统一替换所有玩家类），会被误判为被
 * 替换而执行恢复（klass 槽值同 JVM 一致，写入不破坏内存布局，但方法分派
 * 会切回 expectedClass）。此类模组通常统一替换玩家类（record 时即记录子类），
 * 混合场景极罕见，属可接受风险。restore 失败即永久禁用，不会反复写入。</p>
 * <p>
 * 参考实现（思路借鉴，非照搬）：守护线程自检 + Unsafe 恢复模式；本类改为被动检测
 * （仅玩家 tick 自检时调用），无守护线程，且写入前有布局验证与写入后二次验证两道安全闸。
 */
public final class ClassPointerGuard {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** HotSpot x64 对象头：mark word 8B @0，klass 紧随 @8 */
    private static final long KLASS_OFFSET = 8L;

    private static final sun.misc.Unsafe UNSAFE = getUnsafe();

    /** 玩家类的全局唯一 Class 对象（同一 JVM 内所有玩家同类，客户端 ServerPlayer 亦然） */
    private static volatile Class<?> expectedClass;

    /** 正常玩家对象的 klass 槽值（压缩类指针，int） */
    private static volatile int klassValue;

    /** 探测已验证（offset 8 是 klass 槽且已记录槽值）才允许恢复 */
    private static volatile boolean verified = false;

    private ClassPointerGuard() {}

    /**
     * 记录并验证玩家类（首次通过 true_health 门禁时调用一次，之后空操作）。
     */
    public static void record(Player player) {
        if (expectedClass != null || UNSAFE == null) return;
        synchronized (ClassPointerGuard.class) {
            if (expectedClass != null) return;
            Class<?> cls = player.getClass();
            try {
                int klassInt = UNSAFE.getInt(player, KLASS_OFFSET);
                // 验证：任意异类对象（world 必与 Player 异类）的同一槽值应不同
                Object other = player.level();
                if (other != null && other.getClass() != cls
                    && UNSAFE.getInt(other, KLASS_OFFSET) != klassInt) {
                    expectedClass = cls;
                    klassValue = klassInt;
                    verified = true;
                } else {
                    LOGGER.warn("[ClassPointerGuard] klass 槽探测失败（非标准 JVM 布局？），类指针守卫禁用");
                }
            } catch (Exception e) {
                LOGGER.warn("[ClassPointerGuard] klass 槽探测异常，类指针守卫禁用", e);
            }
        }
    }

    /** 实体类是否被替换（klass 指针被换到隐藏类）。未初始化/禁用时恒 false。 */
    public static boolean isReplaced(Player player) {
        return verified && player.getClass() != expectedClass;
    }

    /** 预期玩家类名（日志用）；未记录时返回 null。 */
    public static String expectedClassName() {
        return expectedClass != null ? expectedClass.getName() : null;
    }

    /** 换回类指针；成功返回 true。失败后永久禁用（防重复写入损坏内存）。 */
    public static boolean restore(Player player) {
        if (!verified || UNSAFE == null) return false;
        try {
            UNSAFE.putInt(player, KLASS_OFFSET, klassValue);
            if (player.getClass() == expectedClass) {
                return true;
            }
            // 写入后验证失败：非压缩类指针等布局差异，停止尝试防止内存损坏
            verified = false;
            LOGGER.error("[ClassPointerGuard] klass 换回失败（class={}），类指针守卫已禁用——"
                + "玩家仍受 setRemoved 拦截与 tick 状态自检保护，但读数可能停留在假死状态",
                player.getClass().getName());
            return false;
        } catch (Exception e) {
            verified = false;
            LOGGER.error("[ClassPointerGuard] klass 换回异常，类指针守卫已禁用", e);
            return false;
        }
    }

    private static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            LOGGER.error("[ClassPointerGuard] 无法获取 Unsafe，类指针守卫不可用", e);
            return null;
        }
    }
}
