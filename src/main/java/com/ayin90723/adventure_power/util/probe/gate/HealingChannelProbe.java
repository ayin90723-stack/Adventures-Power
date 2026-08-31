package com.ayin90723.adventure_power.util.probe.gate;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回血通道探查（v1.4.8 探查回血增量）：对禁疗目标的类链做<b>纯静态 ASM</b> 扫描，
 * 识别其回血代码的写入通道形态，per-class 缓存 + 一次性诊断日志。
 * <p>
 * 三分类（按类链方法体内的调用形态，LivingEntity 前截止）：
 * <ul>
 *   <li>{@link Channel#HEAL}——调 {@code heal(F)V}（SRG m_5634_）：回血走原版 heal 事件链，
 *       禁疗的 LivingHealEvent/heal 拦截层<b>源头有效</b>。<b>候选语义，非运行时实证</b>：
 *       类链方法体内出现 heal 调用即归此分类，包括覆写体内的 super 委托与客户端分支
 *       调用（如 ShadowOfDisorderApocalypse 的 heal 覆写体内客户端分支 super.m_5634_
 *       委托——扫描器如实命中但路径不敏感，服务端主通道实为 setHealPassive→setHealth，
 *       HEAL 分类对此类是语义误报），实际通道以运行时拦截日志为准；</li>
 *   <li>{@link Channel#SETTER}——调 {@code setHealth(F)V}（SRG m_21153_）而无 heal：
 *       回血走 setter（覆写若经基类方法体则 setHealth HEAD 拦截<b>源头有效</b>，
 *       不调 super 的覆写则源头不可达、tick 钳制接管——静态无法判定虚分派落点，
 *       以运行时拦截记录为准）；</li>
 *   <li>{@link Channel#DIRECT}——类链（LivingEntity 前截止）无 heal/setHealth 调用：回血走
 *       字段直写/内部轨（含经 {@code SynchedEntityData.set} 的镜像回写），源头方法拦截天然
 *       不可达，<b>数据层升写拦截 + tick 级钳制</b>是主通道；注意只靠原版自然回血
 *       （heal 在 LivingEntity 基类体内）的普通生物也会落此分类——其 heal 事件源头本已拦截，
 *       诊断时勿按字面当"字段直写"解读。</li>
 * </ul>
 * <p>
 * <b>情报定位（不进关键路径）</b>：分类只用于诊断日志与钳制策略说明——三层拦截
 * （heal 事件 / setHealth HEAD / SED.set 数据层）本就无条件按层生效，本情报的
 * 误报面（如对其他实体调 setter、降向调用）不产生行为影响。静态零扰动
 * （不调用任何目标方法），复用 {@link GateAnalyzer#readClassNode} 的字节码读取
 * （含 hidden class 剥离 / JVM 快照优先）。
 */
public final class HealingChannelProbe {

    /** 回血通道分类。 */
    public enum Channel { HEAL, SETTER, DIRECT }

    private HealingChannelProbe() {
    }

    private static final Map<Class<?>, Channel> CHANNELS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> LOGGED = new ConcurrentHashMap<>();

    /** per-class 惰性探查（缓存直返）。 */
    public static Channel probe(Class<?> cls) {
        return CHANNELS.computeIfAbsent(cls, HealingChannelProbe::scan);
    }

    /** 探查 + 一次性诊断日志（禁疗标记首次对某类挂上时调用）。 */
    public static void probeAndLog(Class<?> cls) {
        Channel ch = probe(cls);
        if (LOGGED.putIfAbsent(cls, Boolean.TRUE) == null) {
            com.ayin90723.adventure_power.util.DebugLog.healingBlock(
                "[禁疗] 回血通道探查 {}：{}（{}）", cls.getSimpleName(), ch, describe(ch));
        }
    }

    private static String describe(Channel ch) {
        return switch (ch) {
            case HEAL -> "走 heal 事件链，源头拦截有效（含类链内 super 委托/客户端分支的 heal 调用——"
                + "通道候选语义而非运行时实证，实际通道以拦截日志为准）";
            case SETTER -> "走 setHealth 通道，源头拦截视覆写是否经基类，未拦到的由数据层+tick 钳制兜底";
            case DIRECT -> "类链未见 heal/setHealth 调用——字段直写型，或回血位于 LivingEntity 基类"
                + "（原版自然回血，heal 事件源头已拦）；数据层升写拦截 + tick 级钳制兜底";
        };
    }

    private static Channel scan(Class<?> cls) {
        boolean callsHeal = false;
        boolean callsSetHealth = false;
        for (Class<?> c = cls; c != null && c != Object.class
             && c != net.minecraft.world.entity.LivingEntity.class; c = c.getSuperclass()) {
            ClassNode cn = GateAnalyzer.readClassNodeForProbe(c);
            if (cn == null) continue;
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (!(insn instanceof MethodInsnNode min) || !"(F)V".equals(min.desc)) continue;
                    if (min.name.equals("m_5634_") || min.name.equals("heal")) callsHeal = true;
                    if (min.name.equals("m_21153_") || min.name.equals("setHealth")) callsSetHealth = true;
                }
            }
        }
        if (callsHeal) return Channel.HEAL;
        if (callsSetHealth) return Channel.SETTER;
        return Channel.DIRECT;
    }
}
