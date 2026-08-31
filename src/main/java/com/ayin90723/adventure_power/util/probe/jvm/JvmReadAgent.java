package com.ayin90723.adventure_power.util.probe.jvm;

import java.lang.instrument.Instrumentation;

/**
 * JVM 只读快照·agentmain 入口（v1.4.8，配置 {@code jvm_snapshot_enabled} 默认关）。
 * <p>
 * <b>零依赖纪律</b>：本类不 import 任何 MC/mod 类——运行时自附加
 * （{@link JvmSnapshotService} 经 Unsafe 解禁 ALLOW_ATTACH_SELF + VirtualMachine.attach
 * 自身 PID + loadAgent）时，JVM 把本 mod jar 追加到 <b>system classloader</b> 搜索路径后
 * 用它加载本类；而 mod 其余类在 ModLauncher 的 TransformingClassLoader 里。两个加载器
 * 各有一份类空间，唯一可靠的通信通道是本类自身的静态字段（同一 AppClassLoader 实例
 * 加载的同一份 Class）——mod 侧经 {@code Class.forName(name, false, systemClassLoader)}
 * 反射取 {@link #INST} 句柄。任何非 JDK import 都会让本类在 AppClassLoader 下链接失败。
 * <p>
 * 入口只做一件事：存句柄。dump transformer / 快照存储 / 日志全在 mod 侧
 * （{@link JvmSnapshotService}），Instrumentation API 本身跨 classloader 互通（操作的是
 * JVM 全局）。
 * <p>
 * Manifest 键 {@code Agent-Class} + {@code Can-Retransform-Classes: true} 由
 * build.gradle 的 jar manifest 注入（本类不在 MANIFEST 出现时 loadAgent 直接拒绝）。
 */
public final class JvmReadAgent {

    /** loadAgent 成功后由 JVM 调 agentmain 写入；mod 侧反射读取。 */
    public static volatile Instrumentation INST;

    private JvmReadAgent() {
    }

    /** 运行时自附加入口（仅存句柄；premain 场景不设——本 mod 不支持也不需要 -javaagent 启动加载）。 */
    public static void agentmain(String args, Instrumentation inst) {
        INST = inst;
    }
}
