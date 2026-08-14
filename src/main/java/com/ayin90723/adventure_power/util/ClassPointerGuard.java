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
 *   <li>布局检测（v1.4.0）：-XX:-UseCompressedClassPointers（非压缩类指针，klass
 *       槽为 8B）时禁用——旧实现 putInt 只写低 4B 会写坏对象头。检测 = 显式 VM
 *       参数扫描 + 未显式指定时按 HotSpot 默认（heap≤32G 开启压缩）假定。</li>
 *   <li>恢复写入（v1.4.0）：putInt 改为「读当前 8B、替换低 4B、putLong 写回」——
 *       压缩布局下 offset 12-15 是相邻字段，putInt 不受影响但 putLong 全量写回
 *       会回滚该字段；保留高 4B 现值则相邻字段零破坏，非压缩下也不会写坏高 4B。</li>
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

    /** 探测失败已判定（非压缩类指针布局/非标准 JVM 布局/探测异常）——短路标志。
     *  v1.4.0 审查修复：record 由 TrueHealthMixin 每 tick 对每个真血玩家调用，
     *  失败分支原先不置位，非标准 JVM 上会每 tick 重复 synchronized + VM 参数
     *  读取 + warn 写盘（20 TPS × 玩家数 条/秒）。 */
    private static volatile boolean disabled = false;

    private ClassPointerGuard() {}

    /**
     * 记录并验证玩家类（首次通过 true_health 门禁时调用一次，之后空操作）。
     */
    public static void record(Player player) {
        if (expectedClass != null || disabled || UNSAFE == null) return;
        synchronized (ClassPointerGuard.class) {
            if (expectedClass != null || disabled) return;
            if (!isCompressedClassPointerLayout()) {
                LOGGER.warn("[ClassPointerGuard] 检测到非压缩类指针布局（-XX:-UseCompressedClassPointers），"
                    + "klass 槽为 8B，类指针守卫禁用");
                disabled = true;
                return;
            }
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
                    disabled = true;
                }
            } catch (Exception e) {
                LOGGER.warn("[ClassPointerGuard] klass 槽探测异常，类指针守卫禁用", e);
                disabled = true;
            }
        }
    }

    /**
     * 类指针布局检测：显式 VM 参数优先；未显式指定时按 HotSpot 默认
     * （heap ≤ 32G 时 UseCompressedClassPointers 默认开启，klass 槽 4B）。
     * 边界（v1.4.0 注）：-XX:-UseCompressedOops 会隐式关闭压缩类指针且不出现
     * 显式参数——此场景未显式关闭 UseCompressedClassPointers 时按压缩假定，
     * 若实际为非压缩，restore 的"保留高 4B + 写低 4B"最坏结果是换回失败 +
     * 永久禁用（写入后 getClass 验证拦截），不会写坏对象头（恢复值保留当前
     * 高 4B，无跨字段覆盖）。
     */
    private static boolean isCompressedClassPointerLayout() {
        try {
            for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if ("-XX:-UseCompressedClassPointers".equalsIgnoreCase(arg)) return false;
                if ("-XX:+UseCompressedClassPointers".equalsIgnoreCase(arg)) return true;
            }
        } catch (Exception e) {
            // 管理接口不可用（罕见），按默认压缩布局放行，restore 二次验证兜底
            LOGGER.warn("[ClassPointerGuard] VM 参数读取失败，按默认压缩类指针布局继续", e);
        }
        return true;
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
            // 保留当前高 4B（压缩布局下为相邻字段现值，非压缩下为指针高 4B），
            // 只替换低 4B 为记录的 klass 槽值——避免 putLong 全量写回回滚相邻字段
            long current = UNSAFE.getLong(player, KLASS_OFFSET);
            UNSAFE.putLong(player, KLASS_OFFSET,
                (current & 0xFFFFFFFF00000000L) | (klassValue & 0xFFFFFFFFL));
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
