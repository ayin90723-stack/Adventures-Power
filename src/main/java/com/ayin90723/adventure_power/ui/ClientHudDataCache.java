package com.ayin90723.adventure_power.ui;

import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 客户端 HUD 数据快照 - 每客户端 tick 末从 Capability 抓取一次 HUD 所需字段，
 * HUD 渲染时读快照，避免每帧 {@code getCapability} 查找。
 * <p>
 * 字段仅在客户端渲染/tick 线程访问，无需同步。玩家为 null 时（菜单/断线）字段归零。
 * <p>
 * 觉醒全视之眼威胁雷达：限频扫描 mc.level 的 Monster，按距离排序取最近 N，
 * 算 3D 方位文字（水平 8 × 垂直 3 自由组合），存入 {@link #radarTargets} 供
 * {@link AllSeeingRadarOverlay} 渲染。
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = Bus.FORGE)
public class ClientHudDataCache {

    // ===== 主动技能 HUD =====
    public static boolean activeSkillReady;   // (adventurer||fullyUnlocked) && abilityEnabled("active_skill")
    public static int activeSkillIndex;
    public static long judgmentCdEnd;
    public static long sanctuaryCdEnd;

    // ===== 死亡抗拒 HUD =====
    public static boolean deathDefyEnabled;   // abilityEnabled("death_defy")
    public static long deathDefyInvulEnd;

    // ===== 全视之眼（客户端去雾） =====
    public static boolean allSeeingEnabled;   // (adventurer||fullyUnlocked) && abilityEnabled("all_seeing")
    public static boolean fullyUnlocked;      // 是否觉醒（雷达仅觉醒显示）

    // ===== 全视之眼威胁雷达 =====
    public static final List<RadarTarget> radarTargets = new ArrayList<>();

    /** 当前游戏时间（HUD 渲染读此值，避免每帧取 level.getGameTime） */
    public static long currentGameTime;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            resetToZero();
            radarTargets.clear();
            currentGameTime = 0;
            return;
        }
        currentGameTime = mc.level.getGameTime();
        // capability 可解析时更新快照；不可解析时（跨维度瞬间等）清零，
        // 避免 HUD 显示上一个实体/上一玩家的残留数据。
        // 统一走 resolve().ifPresentOrElse：isPresent true 但 resolve 为空时同样归零
        //（原实现该路径 ifPresent 空转，旧字段残留）
        mc.player.getCapability(AdventureProgressCapability.CAPABILITY).resolve()
            .ifPresentOrElse(p -> {
                activeSkillReady = (p.isAdventurer() || p.isFullyUnlocked()) && p.isAbilityEnabled(AbilityIds.ACTIVE_SKILL);
                activeSkillIndex = p.getActiveSkillIndex();
                judgmentCdEnd = p.getJudgmentCooldownEnd();
                sanctuaryCdEnd = p.getSanctuaryCooldownEnd();
                deathDefyEnabled = p.isAbilityEnabled(AbilityIds.DEATH_DEFY);
                deathDefyInvulEnd = p.getDeathDefyInvulEnd();
                allSeeingEnabled = (p.isAdventurer() || p.isFullyUnlocked()) && p.isAbilityEnabled(AbilityIds.ALL_SEEING);
                fullyUnlocked = p.isFullyUnlocked();
            }, ClientHudDataCache::resetToZero);

        // 觉醒威胁雷达：限频扫描
        if (allSeeingEnabled && fullyUnlocked) {
            int interval = ModConfig.AWAKEN_ALL_SEEING_RADAR_SCAN_INTERVAL.get();
            if (currentGameTime % interval == 0) {
                scanRadar(mc);
            }
        } else {
            radarTargets.clear();
        }
    }

    /** 扫描附近 Monster，按距离排序取最近 N，算 3D 方位文字存入 radarTargets */
    private static void scanRadar(Minecraft mc) {
        int radius = ModConfig.AWAKEN_ALL_SEEING_RADIUS.get();
        int max = ModConfig.AWAKEN_ALL_SEEING_RADAR_MAX.get();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        AABB box = new AABB(px - radius, py - radius, pz - radius,
                            px + radius, py + radius, pz + radius);
        List<Monster> monsters = mc.level.getEntitiesOfClass(Monster.class, box);

        // 先按 3D 距离排序并截断到 max，再算方向/名称——避免对全部实体做翻译查找。
        // 排序键与显示距离一致（3D），保证「最近目标」就是列表第一个。
        float yaw = mc.player.getYRot();
        List<Monster> sorted = new ArrayList<>();
        for (Monster m : monsters) {
            if (m.isRemoved() || !m.isAlive()) continue;
            double dx = m.getX() - px;
            double dy = m.getY() - py;
            double dz = m.getZ() - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > radius) continue; // 3D 半径过滤（AABB 角落的目标不应超出配置半径）
            sorted.add(m);
        }
        sorted.sort(Comparator.comparingDouble(m -> {
            double dx = m.getX() - px, dy = m.getY() - py, dz = m.getZ() - pz;
            return dx * dx + dy * dy + dz * dz;
        }));

        radarTargets.clear();
        int limit = Math.min(max, sorted.size());
        for (int i = 0; i < limit; i++) {
            Monster m = sorted.get(i);
            double dx = m.getX() - px;
            double dy = m.getY() - py;
            double dz = m.getZ() - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String dir = getDirection(dx, dy, dz, yaw);
            String name = m.getType().getDescription().getString();
            radarTargets.add(new RadarTarget(name, (int) Math.round(dist), dir));
        }
    }

    /** 清零全部 HUD 快照字段（玩家缺失/capability 不可解析时调用，防残留上一玩家数据） */
    private static void resetToZero() {
        activeSkillReady = false;
        activeSkillIndex = 0;
        judgmentCdEnd = 0;
        sanctuaryCdEnd = 0;
        deathDefyEnabled = false;
        deathDefyInvulEnd = 0;
        allSeeingEnabled = false;
        fullyUnlocked = false;
    }

    /**
     * 计算 3D 方位文字（水平 8 × 垂直 3 自由组合）。MC yaw 系：0=南(+Z)，90=西(-X)，-90=东(+X)。
     * <p>
     * 水平：前/后/左/右/左前/右前/左后/右后（相对角 0=正前）。<br>
     * 垂直：pitch>60°=正上，<-60°=正下，>30°=上方（水平词+上），<-30°=下方（水平词+下），否则同层。<br>
     * 同层 4 正加「正」（正前/正后/正左/正右），对角直接（左前等）。
     * <p>
     * 通过 lang 键本地化（hud.adventure_power.dir.*），保证英文客户端正确显示。
     */
    private static String getDirection(double dx, double dy, double dz, float yawDeg) {
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double pitchDeg = horizDist > 0.5
            ? Math.toDegrees(Math.atan2(dy, horizDist))
            : (dy > 0 ? 90 : (dy < 0 ? -90 : 0));

        double mobYaw = Math.toDegrees(Math.atan2(-dx, dz));  // 与 MC yaw 同系
        double rel = mobYaw - yawDeg;
        rel = ((rel % 360) + 540) % 360 - 180;  // normalize -180~180

        // 水平基础词（逻辑 ID，翻译走 lang 键）
        String base;
        if (rel >= -22.5 && rel < 22.5) base = "front";
        else if (rel >= 22.5 && rel < 67.5) base = "right_front";
        else if (rel >= 67.5 && rel < 112.5) base = "right";
        else if (rel >= 112.5 && rel < 157.5) base = "right_back";
        else if (rel >= 157.5 || rel < -157.5) base = "back";
        else if (rel >= -157.5 && rel < -112.5) base = "left_back";
        else if (rel >= -112.5 && rel < -67.5) base = "left";
        else base = "left_front";
        // 四正：front/back/left/right（按字符长度判断会漏掉 5 字符的 front/right）
        boolean cardinal = "front".equals(base) || "back".equals(base)
            || "left".equals(base) || "right".equals(base);

        // 垂直
        if (pitchDeg > 60) return key("up_straight");
        if (pitchDeg < -60) return key("down_straight");
        if (pitchDeg > 30) return key(base + "_up");      // 前上 / 右前上 / ...
        if (pitchDeg < -30) return key(base + "_down");   // 前下 / 右前下 / ...
        return cardinal ? key(base + "_cardinal") : key(base);  // 正前 / 左前 / ...
    }

    /** 方位词翻译缓存（30 个键，首次使用时解析；语言切换需重开游戏生效，可接受） */
    private static final java.util.Map<String, String> DIR_KEY_CACHE = new java.util.HashMap<>();

    private static String key(String suffix) {
        return DIR_KEY_CACHE.computeIfAbsent(suffix, s -> net.minecraft.network.chat.Component
            .translatable("hud.adventure_power.dir." + s).getString());
    }

    /** 雷达目标条目 */
    public static class RadarTarget {
        public final String name;
        public final int distance;
        public final String arrow;
        public RadarTarget(String name, int distance, String arrow) {
            this.name = name;
            this.distance = distance;
            this.arrow = arrow;
        }
    }
}
