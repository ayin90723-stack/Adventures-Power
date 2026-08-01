package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import javax.annotation.Nullable;
import java.io.Reader;
import java.util.*;

/**
 * 里程碑动态注册表 — 从数据包 JSON 加载里程碑定义。
 * 在 AddReloadListenerEvent 时初始化，支持 /reload 热更新。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MilestoneRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static List<Milestone> milestones = List.of();
    private static Map<ResourceLocation, Milestone> byAdvancement = Map.of();
    private static Map<String, Milestone> byId = Map.of();
    private static Map<String, List<Milestone>> byTriggerType = Map.of();
    private static boolean initialized = false;

    /** 内置兜底递归保护：内置 JSON 自身损坏时避免无限递归 */
    private static boolean inBuiltinFallback = false;

    /** 版本计数器，里程碑列表每次变更时递增。
     *  用于 AdventureCurioItem 的 tooltip 缓存失效判断。
     *  volatile 确保客户端渲染线程能读到最新值。 */
    private static volatile int version = 0;

    /** 返回当前版本号。 */
    public static int getVersion() { return version; }

    // ===== 查询方法 =====

    public static List<Milestone> getAll() { return milestones; }
    public static int getMilestoneCount() { return milestones.size(); }
    public static boolean isInitialized() { return initialized; }

    @Nullable
    public static Milestone getByAdvancement(ResourceLocation advId) {
        return byAdvancement.get(advId);
    }

    @Nullable
    public static Milestone getById(String id) {
        return byId.get(id);
    }

    /**
     * 返回指定 trigger type 的所有里程碑（预索引，避免触发器每 tick 全遍历）。
     * type 为 null 或无匹配时返回空列表。
     */
    public static List<Milestone> getByTriggerType(String type) {
        if (type == null) return List.of();
        return byTriggerType.getOrDefault(type, List.of());
    }

    public static boolean contains(String id) {
        return byId.containsKey(id);
    }

    public static int getCountAtUnlock(String abilityId) {
        return AbilityRegistry.getCountAtUnlock(abilityId);
    }

    public static boolean isAbilityAvailable(String abilityId, int unlockedCount) {
        return unlockedCount >= getCountAtUnlock(abilityId);
    }

    /** 获取某里程碑包含的能力 ID 列表 */
    public static List<String> getAbilitiesForMilestone(String milestoneId) {
        Milestone m = byId.get(milestoneId);
        return m != null ? m.abilities() : List.of();
    }

    // ===== 加载逻辑 =====

    /**
     * 从数据包 JSON 加载里程碑数据。
     * 同样用于客户端接收同步数据后的初始化。
     */
    public static void loadFromJson(String namespace, JsonObject root) {
        List<Milestone> loaded = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("milestones");
        if (arr == null || arr.size() == 0) {
            LOGGER.error("[MilestoneRegistry] milestones 数组为空或缺失，回退到内置默认");
            loadBuiltinDefaults();
            return;
        }

        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            try {
                String id = obj.get("id").getAsString();

                if (seenIds.contains(id)) {
                    LOGGER.warn("[MilestoneRegistry] 重复的 milestone ID: {}，使用最后一个", id);
                }
                seenIds.add(id);

                String name = obj.get("name").getAsString();

                List<String> abilities = new ArrayList<>();
                JsonArray abilityArr = obj.getAsJsonArray("abilities");
                if (abilityArr != null) {
                    for (JsonElement e : abilityArr) {
                        String abilityId = e.getAsString();
                        if (AbilityRegistry.get(abilityId) == null) {
                            LOGGER.warn("[MilestoneRegistry] 未知的 ability ID: {}，跳过", abilityId);
                        } else {
                            abilities.add(abilityId);
                        }
                    }
                }

                String advStr = obj.has("advancement") && !obj.get("advancement").isJsonNull()
                    ? obj.get("advancement").getAsString() : null;
                ResourceLocation advancement = advStr != null ? new ResourceLocation(advStr) : null;

                TriggerDef trigger = null;
                if (obj.has("trigger") && !obj.get("trigger").isJsonNull()) {
                    JsonObject trigObj = obj.getAsJsonObject("trigger");
                    String type = trigObj.get("type").getAsString();
                    Integer y = trigObj.has("y") ? trigObj.get("y").getAsInt() : null;
                    ResourceLocation entity = trigObj.has("entity")
                        ? new ResourceLocation(trigObj.get("entity").getAsString()) : null;
                    trigger = new TriggerDef(type, y, entity);
                }

                if (advancement == null && trigger == null) {
                    LOGGER.warn("[MilestoneRegistry] milestone {} 无 advancement 且无 trigger，永远无法达成", id);
                }

                Milestone m = new Milestone(id, name, List.copyOf(abilities), advancement, trigger);
                loaded.add(m);
            } catch (Exception e) {
                // 逐条目容错：数据包中单条定义损坏（缺字段/类型错误）时跳过该条，不影响其余
                LOGGER.warn("[MilestoneRegistry] milestone #{} 解析失败，跳过: {}", i, e.toString());
            }
        }

        if (loaded.isEmpty()) {
            // 全部条目损坏 -> 回退内置默认，避免注册表为空引发全能力失效/误全解锁级联
            LOGGER.error("[MilestoneRegistry] 所有里程碑解析失败，回退到内置默认");
            if (!inBuiltinFallback) {
                inBuiltinFallback = true;
                try {
                    loadBuiltinDefaults();
                } finally {
                    inBuiltinFallback = false;
                }
            }
            return;
        }

        applyMilestones(loaded);
        LOGGER.info("[MilestoneRegistry] 加载完成: {} 个里程碑", milestones.size());
    }

    /**
     * 应用里程碑列表：构建所有索引 + 设置 countAtUnlock。
     * loadFromJson 和 clientInitFromNbt 共用，避免逻辑重复。
     */
    private static void applyMilestones(List<Milestone> loaded) {
        milestones = List.copyOf(loaded);
        byId = new HashMap<>();
        byAdvancement = new HashMap<>();
        byTriggerType = new HashMap<>();
        for (int i = 0; i < loaded.size(); i++) {
            Milestone m = loaded.get(i);
            byId.put(m.id(), m);
            if (m.advancement() != null) {
                byAdvancement.put(m.advancement(), m);
            }
            if (m.trigger() != null && m.trigger().type() != null) {
                byTriggerType.computeIfAbsent(m.trigger().type(), k -> new ArrayList<>()).add(m);
            }
            for (String abilityId : m.abilities()) {
                AbilityRegistry.setCountAtUnlock(abilityId, i + 1);
            }
        }
        // 冻结各 type 列表为不可变
        for (Map.Entry<String, List<Milestone>> entry : byTriggerType.entrySet()) {
            entry.setValue(List.copyOf(entry.getValue()));
        }
        initialized = true;
        version++;
    }

    /**
     * 客户端从网络包的 NBT 元数据直接构建里程碑注册表（不经 JSON 中转）。
     * 与 SyncUtil.syncToClient 的 _milestone_registry NBT 结构对应。
     */
    public static void clientInitFromNbt(CompoundTag registryMeta) {
        int count = registryMeta.getInt("count");
        if (count <= 0) {
            LOGGER.warn("[MilestoneRegistry] 客户端收到的里程碑元数据为空，保持现有注册表");
            return;
        }
        List<Milestone> loaded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CompoundTag mTag = registryMeta.getCompound("m_" + i);
            String id = mTag.getString("id");
            String name = mTag.getString("name");
            List<String> abilities = new ArrayList<>();
            if (mTag.contains("abilities")) {
                CompoundTag abTag = mTag.getCompound("abilities");
                int abCount = abTag.getInt("count");
                for (int j = 0; j < abCount; j++) {
                    abilities.add(abTag.getString("a_" + j));
                }
            }
            ResourceLocation advancement = mTag.contains("advancement")
                ? new ResourceLocation(mTag.getString("advancement")) : null;
            TriggerDef trigger = null;
            if (mTag.contains("trigger")) {
                CompoundTag trigTag = mTag.getCompound("trigger");
                String type = trigTag.getString("type");
                Integer y = trigTag.contains("y") ? trigTag.getInt("y") : null;
                ResourceLocation entity = trigTag.contains("entity")
                    ? new ResourceLocation(trigTag.getString("entity")) : null;
                trigger = new TriggerDef(type, y, entity);
            }
            loaded.add(new Milestone(id, name, List.copyOf(abilities), advancement, trigger));
        }
        applyMilestones(loaded);
        LOGGER.info("[MilestoneRegistry] 客户端从 NBT 加载完成: {} 个里程碑", milestones.size());
    }

    /** 数据包重载前清除现有数据 */
    public static void clear() {
        milestones = List.of();
        byAdvancement = Map.of();
        byId = Map.of();
        byTriggerType = Map.of();
        initialized = false;
        AbilityRegistry.clearCountAtUnlockOverrides();
        version++;
    }

    // ===== 事件监听 =====

    /**
     * 注册数据包重载监听器。
     * Forge 的 AddReloadListenerEvent 允许在服务端启动和数据包重载时重新加载。
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<JsonElement>() {
            @Override
            protected JsonElement prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                ResourceLocation loc = new ResourceLocation(AdventurePower.MODID, "adventure_power/milestones.json");
                Optional<Resource> opt = resourceManager.getResource(loc);
                if (opt.isPresent()) {
                    try (Reader reader = opt.get().openAsReader()) {
                        return GSON.fromJson(reader, JsonElement.class);
                    } catch (Exception e) {
                        LOGGER.error("[MilestoneRegistry] 读取 milestones.json 失败: {}", e.toString());
                    }
                }
                return null;
            }

            @Override
            protected void apply(JsonElement element, ResourceManager resourceManager, ProfilerFiller profiler) {
                MilestoneRegistry.clear();
                try {
                    if (element != null && element.isJsonObject()) {
                        MilestoneRegistry.loadFromJson(AdventurePower.MODID, element.getAsJsonObject());
                    } else {
                        LOGGER.warn("[MilestoneRegistry] 未找到 milestones.json，使用内置默认");
                        loadBuiltinDefaults();
                    }
                } catch (Exception e) {
                    // 顶层防护：任何解析异常都不允许留下空注册表
                    LOGGER.error("[MilestoneRegistry] 数据包 milestones.json 解析失败，回退内置默认", e);
                    loadBuiltinDefaults();
                }
            }
        });
    }

    /** 当数据包中无 milestones.json 时使用模组内置默认 */
    private static void loadBuiltinDefaults() {
        // 从 classpath 加载内置 milestones.json（与资源文件保持单一来源，避免重复维护）
        java.io.InputStream is = MilestoneRegistry.class.getResourceAsStream(
            "/data/adventure_power/adventure_power/milestones.json");
        if (is == null) {
            LOGGER.error("[MilestoneRegistry] 内置 milestones.json 未找到");
            return;
        }
        try (java.io.Reader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            loadFromJson(AdventurePower.MODID, root);
        } catch (Exception e) {
            LOGGER.error("[MilestoneRegistry] 加载内置 milestones.json 失败", e);
        }
    }
}
