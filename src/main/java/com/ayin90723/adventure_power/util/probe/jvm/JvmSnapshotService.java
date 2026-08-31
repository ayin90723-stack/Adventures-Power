package com.ayin90723.adventure_power.util.probe.jvm;

import com.ayin90723.adventure_power.config.ModConfig;
import sun.misc.Unsafe;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * JVM 只读字节码快照服务（v1.4.8，{@code jvm_snapshot_enabled} 默认关）。
 * <p>
 * 能力：dump <b>mod 层类</b>的"运行时真身"字节码——经 Mixin 与（若存在）对方 javaagent
 * 全部 transformation 处理后的最终类形态，供 {@code GateAnalyzer} 的覆写/存储情报分析
 * 消费（此前只能类路径读 transformation 前字节码 = 下界，Mixin/agent 注入不在视野）。
 * <p>
 * <b>只读纪律（零修改承诺）</b>：
 * <ul>
 *   <li>dump transformer 对一切类返回 {@code null}（声明不改），retransform 后落地字节码
 *       与快照前完全一致；</li>
 *   <li><b>只对 mod 层类 retransform</b>（{@code net/minecraft/}、{@code com/mojang/} 等
 *       平台前缀硬排除）——retransform 会让链上所有 {@code canRetransform=true} 的第三方
 *       transformer 重跑：对 MC 核心类，重跑=对方钩子在已注入字节码上二次叠层（对方
 *       transformer 无幂等标记时），虽非我们注入仍构成事实上的字节码变化，违反零修改
 *       承诺。mod 层类不在对方钩子的目标清单（javaagent 钩 MC 管线类才有意义），重跑
 *       全部短路返回 null，无叠层风险。GateAnalyzer 的分析对象（Boss mod 类）恰好全部
 *       落在 mod 层，覆盖无损；</li>
 *   <li>不 appendToBootstrapClassLoaderSearch、不改类可见性、不常驻 transformer
 *       （每次快照注册→dump→removeTransformer，用后即拆）。</li>
 * </ul>
 * <p>
 * <b>自附加</b>：{@code Unsafe} 直改 {@code HotSpotVirtualMachine.ALLOW_ATTACH_SELF}
 * （static final，JVM 默认禁止自附加的开关，正常只能靠 -Djdk.attach.allowAttachSelf
 * 启动参数打开）→ 反射 VirtualMachine.attach(自身 PID) → loadAgent(本 mod jar) → detach。
 * loadAgent 使 JVM 把本 jar 加入 system classloader 搜索路径并用其加载
 * {@link JvmReadAgent}（零依赖入口，只存 Instrumentation 句柄），本服务经 system
 * classloader 反射取回句柄——两个类空间（system / TransformingClassLoader）以入口类
 * 静态字段为唯一通道。全链路 try-catch 优雅降级：附加失败仅日志告警，快照退回类路径
 * 读（现状行为），其余功能零影响。
 * <p>
 * <b>边界</b>：hidden class（{@code isModifiableClass == false}，类名带 {@code /0x}
 * 后缀）不可 dump，退回类路径读原始字节码（GateAnalyzer 既有处理）；快照只拿"类形态
 * 层"的知情权，不涉及对象图/方法事件/native 层。
 * <p>
 * <b>消费方</b>：{@code GateAnalyzer.readClassNode}（全部下游：覆写分析 / die 链 /
 * 合成血分量 / 存储情报自动受益）。per-class 结果缓存（正：字节码；负：不可快照标记）。
 */
public final class JvmSnapshotService {

    private static final Logger LOGGER = LoggerFactory.getLogger("adventure_power.jvm_snapshot");

    private JvmSnapshotService() {
    }

    /** 快照存储：类 internal 名（a/b/C）→ 运行时真身字节码。 */
    private static final Map<String, byte[]> SNAPSHOTS = new ConcurrentHashMap<>();

    /** 负缓存：不可快照（附加失败全局态 / hidden class / retransform 拒绝）的类名。 */
    private static final Set<String> UNAVAILABLE = ConcurrentHashMap.newKeySet();

    /** 附加状态：0=未尝试，1=成功，-1=失败（全局，失败后不再重试）。 */
    private static volatile int attachState = 0;

    /** dump transformer 的当前请求集（快照调用栈内设置，调用结束清空；主线程串行）。 */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    /** dump-only transformer：请求集内类存字节码，其余/全部返回 null（零修改）。 */
    private static final ClassFileTransformer DUMP_ONLY = new ClassFileTransformer() {
        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            // 只关心 retransform 回调（classBeingRedefined != null）且命中请求集的类；
            // 初次类定义流经本 transformer 时一律返回 null（不参与定义期链路）
            if (classBeingRedefined != null && className != null && PENDING.contains(className)) {
                SNAPSHOTS.put(className, classfileBuffer);
            }
            return null;
        }
    };

    /**
     * 查询类运行时真身字节码（懒快照）：配置开且 mod 层类 → 首次访问时触发附加+快照；
     * 不可用（配置关/平台类/hidden/附加失败）返回 null，消费方退回类路径读。
     * <p>
     * 线程：服务端主线程（GateAnalyzer 调用语境）；retransform 是 safepoint 操作，
     * mod 类毫秒级、per-class 一次性；首类快照另含一次性自附加开销（本地进程通信典型
     * 10~100ms，全部发生在战斗 tick 主线程——默认关的配置策略即为此）。
     *
     * @param cls 目标 Class（取其加载态）
     * @return 真身字节码；null = 无快照可用
     */
    public static byte[] getRuntimeBytes(Class<?> cls) {
        if (cls == null) return null;
        String internal = cls.getName().replace('.', '/');
        byte[] cached = SNAPSHOTS.get(internal);
        if (cached != null) return cached;
        if (UNAVAILABLE.contains(internal)) return null;
        // 平台类硬排除（零修改承诺——见类注释只读纪律第 2 条）
        // 复查修（P2）：net/minecraftforge/ 必须排除——Forge 平台类（FakePlayer 在实体类链上、
        // analyzeDelegate 的 Class.forName 可触达任意 owner），对方 agent 钩 Forge 事件/网络类
        // 的可能性真实存在，retransform 触达即叠层风险；排除零功能损失（分析对象是 Boss mod 类）
        if (internal.startsWith("net/minecraft/") || internal.startsWith("com/mojang/")
            || internal.startsWith("net/minecraftforge/")
            || internal.startsWith("java/") || internal.startsWith("jdk/")
            || internal.startsWith("sun/") || internal.startsWith("com/sun/")
            || internal.startsWith("org/objectweb/asm/") || internal.startsWith("org/spongepowered/")) {
            UNAVAILABLE.add(internal);
            return null;
        }
        if (!ModConfig.JVM_SNAPSHOT_ENABLED.get()) {
            // 配置关：不附加不快照，也不写负缓存（复查修 P3：写入后运行时 /reload 开启配置
            // 时旧类永不快照——Forge ConfigValue.get 有内部缓存，此处每次直读开销可忽略）
            return null;
        }
        if (!ensureAttached()) {
            UNAVAILABLE.add(internal);
            return null;
        }
        Instrumentation inst = peekInstrumentation();
        if (inst == null) {
            UNAVAILABLE.add(internal);
            return null;
        }
        // hidden class（/0x 后缀）与 JVM 内部类不可 retransform——负缓存退类路径读
        if (cls.getName().indexOf('/') >= 0 || !inst.isModifiableClass(cls)) {
            UNAVAILABLE.add(internal);
            LOGGER.warn("[JVM快照] {} 不可 retransform（hidden class？），退回类路径读原始字节码", cls.getName());
            return null;
        }
        try {
            PENDING.add(internal);
            inst.addTransformer(DUMP_ONLY, true);
            try {
                inst.retransformClasses(cls);
            } finally {
                inst.removeTransformer(DUMP_ONLY);
                PENDING.remove(internal);
            }
        } catch (Throwable t) {
            UNAVAILABLE.add(internal);
            LOGGER.warn("[JVM快照] {} retransform 失败：{}，退回类路径读", cls.getName(), t.toString());
            return null;
        }
        byte[] bytes = SNAPSHOTS.get(internal);
        if (bytes == null) {
            // transformer 未回收（理论不应发生——防御性负缓存）
            UNAVAILABLE.add(internal);
            return null;
        }
        LOGGER.info("[JVM快照] 运行时真身快照命中 {}（{} 字节，含 Mixin/agent 全部 transformation）",
            cls.getName(), bytes.length);
        return bytes;
    }

    /** 附加状态查询（诊断用）。 */
    public static boolean isAttached() {
        return attachState == 1;
    }

    // ==================== 自附加（一次性） ====================

    /** 幂等自附加：成功后句柄缓存于 {@link JvmReadAgent#INST}（system classloader 侧）。 */
    private static synchronized boolean ensureAttached() {
        if (attachState != 0) return attachState == 1;
        attachState = -1; // 先置失败，全链路成功才翻正（异常路径不再重试）
        try {
            // 已有句柄（-javaagent 提前加载 / 重复调用）直接复用
            if (peekInstrumentation() != null) {
                attachState = 1;
                LOGGER.info("[JVM快照] Instrumentation 已就绪（-javaagent 或先前附加），跳过自附加");
                return true;
            }
            String agentJar = locateModJar();
            if (agentJar == null) {
                LOGGER.warn("[JVM快照] 自附加失败：无法定位本 mod jar（CodeSource 非 file 协议？），快照功能关闭");
                return false;
            }
            enableSelfAttach();
            String pid = Long.toString(ProcessHandle.current().pid());
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
            try {
                vmClass.getMethod("loadAgent", String.class, String.class).invoke(vm, agentJar, "");
            } finally {
                try {
                    vmClass.getMethod("detach").invoke(vm);
                } catch (Throwable ignored) {
                }
            }
            if (peekInstrumentation() == null) {
                LOGGER.warn("[JVM快照] 自附加 loadAgent 返回但 Instrumentation 未就绪（agentmain 未执行？），快照功能关闭");
                return false;
            }
            attachState = 1;
            LOGGER.info("[JVM快照] 自附加成功（pid={}，agent={}），只读快照通道就绪", pid, agentJar);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[JVM快照] 自附加失败：{}——快照功能关闭（类路径读兜底，其余功能不受影响）", t.toString());
            return false;
        }
    }

    /** 经 system classloader 反射读 {@link JvmReadAgent#INST}（两类空间唯一通道）。
     *  注意：本方法内的 JvmReadAgent.class.getName() 类字面量会让 TransformingClassLoader 侧
     *  也加载一份同名类——那是另一个 Class 实例、其 INST 恒 null，任何代码都不得从 TCL 侧
     *  读 INST 误判附加失败；本方法固定经 system classloader 反射，指向正确实例。 */
    private static Instrumentation peekInstrumentation() {
        try {
            Class<?> agent = Class.forName(JvmReadAgent.class.getName(), false, ClassLoader.getSystemClassLoader());
            Field f = agent.getDeclaredField("INST");
            f.setAccessible(true);
            return (Instrumentation) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 定位本 mod jar（三级 fallback，实机修复：Forge 生产环境 mod 类的 CodeSource 是
     * securejarhandler 的 union: 协议——首版只认 jar:/file: 直接返回 null，自附加在
     * 实机恒败于"无法定位本 mod jar"）。
     * ① CodeSource：jar:/file:/union:（union 剥 !/ 段 + URLDecoder 解 %23 井号 + Windows
     *    盘符前导斜杠修正）
     * ② ModList API：getModFileById("adventure_power").getFile().getFilePath()（精确）
     * ③ FMLPaths.MODSDIR 扫描 adventure_power*.jar（dev 环境 classes 目录则 ①② 均自然
     *    失败降级，快照关闭——dev 无 attach 意义）
     */
    private static String locateModJar() {
        // ① CodeSource（jar / file / union 三协议）
        try {
            URL loc = JvmSnapshotService.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                File f = null;
                if ("jar".equals(loc.getProtocol())) {
                    loc = ((JarURLConnection) loc.openConnection()).getJarFileURL();
                }
                if ("file".equals(loc.getProtocol())) {
                    f = new File(loc.toURI());
                } else if ("union".equals(loc.getProtocol())) {
                    f = fileFromUnionUrl(loc);
                }
                if (f != null && f.isFile()) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        // ② ModList API（反射——FML loading 类运行时可见，避免编译期依赖细节）
        try {
            Object modList = Class.forName("net.minecraftforge.fml.ModList").getMethod("get").invoke(null);
            Object fileInfo = modList.getClass().getMethod("getModFileById", String.class).invoke(modList, "adventure_power");
            if (fileInfo != null) {
                Object modFile = fileInfo.getClass().getMethod("getFile").invoke(fileInfo);
                Object path = modFile.getClass().getMethod("getFilePath").invoke(modFile);
                if (path instanceof java.nio.file.Path p) {
                    File f = p.toFile();
                    if (f.isFile()) return f.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        // ③ MODSDIR 扫描
        try {
            Object modsDir = Class.forName("net.minecraftforge.fml.loading.FMLPaths").getField("MODSDIR").get(null);
            if (modsDir instanceof java.nio.file.Path dir) {
                try (java.util.stream.Stream<java.nio.file.Path> list = java.nio.file.Files.list(dir)) {
                    java.util.Optional<java.nio.file.Path> hit = list
                        .filter(pp -> {
                            String n = pp.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                            return n.startsWith("adventure_power") && n.endsWith(".jar");
                        })
                        .findFirst();
                    if (hit.isPresent() && hit.get().toFile().isFile()) return hit.get().toFile().getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** union:/D:/path/xx.jar%23123!/entry → File（剥 !/ 段、URLDecoder 解码、盘符前导 / 修正）。 */
    private static File fileFromUnionUrl(URL loc) {
        try {
            String raw = loc.getPath();
            if (raw == null || raw.isEmpty()) return null;
            int bang = raw.indexOf("!/");
            if (bang >= 0) raw = raw.substring(0, bang);
            String decoded = java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
            // union 路径形如 /D:/game/...（盘符前导斜杠），Windows 下需剥掉
            if (decoded.length() > 2 && decoded.charAt(0) == '/'
                && Character.isLetter(decoded.charAt(1)) && decoded.charAt(2) == ':') {
                decoded = decoded.substring(1);
            }
            return new File(decoded);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Unsafe 直改 HotSpotVirtualMachine.ALLOW_ATTACH_SELF（自附加解禁，无启动参数依赖）。 */
    private static void enableSelfAttach() throws Exception {
        try {
            Class<?> hsvm = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
            Field flag = hsvm.getDeclaredField("ALLOW_ATTACH_SELF");
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            Object base = unsafe.staticFieldBase(flag);
            long offset = unsafe.staticFieldOffset(flag);
            unsafe.putBoolean(base, offset, true);
        } catch (Throwable t) {
            // 解禁失败不阻断：部分发行版/安全策略下 attach 自身本就允许，留给 attach 调用定夺
            LOGGER.info("[JVM快照] ALLOW_ATTACH_SELF 解禁未生效（{}），尝试直接附加", t.toString());
        }
    }
}
