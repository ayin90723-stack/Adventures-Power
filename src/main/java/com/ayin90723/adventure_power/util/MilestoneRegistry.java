package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import javax.annotation.Nullable;
import java.io.Reader;
import java.util.*;

/**
 * 里程碑动态注册表 — 从数据包 JSON 加载里程碑定义。
 * 在 AddReloadListenerEvent 时初始化，支持 /reload 热更新。
 * <p>
 * 支持数据包叠加：同一路径的 milestones.json 可被多个数据包提供，
 * 默认后加载（高优先级）整体替换；文件含 {@code "merge": true} 时按 id 合并
 * （同 id 后者覆盖，新 id 追加）。顶层文件含 {@code "enabled": false} 时
 * 整个里程碑系统被禁用（不回退内置）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MilestoneRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** 已知 trigger type 白名单（未知类型加载时告警，避免数据包作者 typo 导致里程碑静默不可达） */
    private static final Set<String> KNOWN_TRIGGER_TYPES = Set.of(
        "survive_night", "first_death", "first_trade", "y_below", "first_kill",
        "enter_dimension", "reach_y", "obtain_item");

    private static List<Milestone> milestones = List.of();
    private static Map<ResourceLocation, Milestone> byAdvancement = Map.of();
    private static Map<String, Milestone> byId = Map.of();
    private static Map<String, List<Milestone>> byTriggerType = Map.of();
    private static boolean initialized = false;

    /** 当前生效的禁用能力黑名单（数据包 disabled_abilities，由 loadFromJson 维护；指令后门可解锁这些能力） */
    private static Set<String> disabledAbilities = Set.of();

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

    /** 该能力是否在数据包禁用黑名单中（指令后门只允许解锁这类能力，防绕过里程碑） */
    public static boolean isAbilityDisabled(String id) {
        return disabledAbilities.contains(id);
    }

    /** 当前被禁用的能力 ID 集合（供指令 /ap disabled 展示） */
    public static Set<String> getDisabledAbilities() {
        return disabledAbilities;
    }

    // ===== 加载逻辑 =====

    /**
     * 从数据包 JSON 加载里程碑数据。
     * 同样用于客户端接收同步数据后的初始化。
     */
    public static void loadFromJson(JsonObject root) {
        List<Milestone> loaded = new ArrayList<>();

        // 数据包禁用能力黑名单：从所有里程碑 abilities 中移除。
        // 被禁用的能力不会出现在任何里程碑中，觉醒后也不会重新获得；
        // 指令后门（/ap unlock ability）可逐玩家解锁这类能力。
        Set<String> disabled = new HashSet<>();
        if (root.has("disabled_abilities") && root.get("disabled_abilities").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("disabled_abilities")) {
                if (!e.isJsonPrimitive()) continue;
                String abilityId = e.getAsString();
                if (AbilityRegistry.get(abilityId) == null) {
                    LOGGER.warn("[MilestoneRegistry] disabled_abilities 中的未知能力 ID: {}，忽略", abilityId);
                } else {
                    disabled.add(abilityId);
                }
            }
            if (!disabled.isEmpty()) {
                LOGGER.info("[MilestoneRegistry] 已禁用能力黑名单: {}", disabled);
            }
        }
        disabledAbilities = Set.copyOf(disabled);

        JsonArray arr = root.getAsJsonArray("milestones");
        if (arr == null || arr.size() == 0) {
            LOGGER.error("[MilestoneRegistry] milestones 数组为空或缺失，回退到内置默认");
            loadBuiltinDefaults();
            return;
        }

        Set<String> seenIds = new HashSet<>();
        // 能力 ID → 首次出现的里程碑（检测同一能力挂多个里程碑的归属歧义）
        Map<String, String> abilityFirstSeen = new HashMap<>();
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
                        if (disabled.contains(abilityId)) {
                            // 被数据包禁用：不加入能力列表，但保持其归属位置的 countAtUnlock，
                            // 指令后门解锁该能力后成长公式仍按原设计计算（不出现数值错乱）
                            AbilityRegistry.setCountAtUnlock(abilityId, i + 1);
                            continue;
                        }
                        if (AbilityRegistry.get(abilityId) == null) {
                            LOGGER.warn("[MilestoneRegistry] 未知的 ability ID: {}，跳过", abilityId);
                        } else {
                            abilities.add(abilityId);
                            String prev = abilityFirstSeen.putIfAbsent(abilityId, id);
                            if (prev != null) {
                                LOGGER.warn("[MilestoneRegistry] 能力 {} 同时属于里程碑 {} 与 {}，countAtUnlock 取后者位置", abilityId, prev, id);
                            }
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
                    ResourceLocation entity = parseOptionalLocation(trigObj, "entity");
                    ResourceLocation dimension = parseOptionalLocation(trigObj, "dimension");
                    ResourceLocation item = parseOptionalLocation(trigObj, "item");
                    trigger = new TriggerDef(type, y, entity, dimension, item);
                }

                if (trigger != null && !KNOWN_TRIGGER_TYPES.contains(trigger.type())) {
                    LOGGER.warn("[MilestoneRegistry] milestone {} 使用了未知 trigger type: {}，将永远无法触发", id, trigger.type());
                }
                warnMissingTriggerParams(id, trigger);

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

    /** 从 trigger 对象中按 key 读取 ResourceLocation 字段（缺省/为 null 时返回 null） */
    private static ResourceLocation parseOptionalLocation(JsonObject trigObj, String key) {
        if (trigObj.has(key) && !trigObj.get(key).isJsonNull()) {
            return new ResourceLocation(trigObj.get(key).getAsString());
        }
        return null;
    }

    /** 各 trigger type 必填字段缺失或指向未知注册表项时告警（缺省会导致无法触发，需尽早提示数据包作者） */
    private static void warnMissingTriggerParams(String milestoneId, @Nullable TriggerDef trigger) {
        if (trigger == null) return;
        switch (trigger.type()) {
            case "y_below", "reach_y" -> {
                if (trigger.y() == null) {
                    LOGGER.warn("[MilestoneRegistry] milestone {} 的 {} trigger 未指定 y，默认 0", milestoneId, trigger.type());
                }
            }
            case "enter_dimension" -> {
                if (trigger.dimension() == null) {
                    LOGGER.warn("[MilestoneRegistry] milestone {} 的 enter_dimension trigger 未指定 dimension，将无法触发", milestoneId);
                }
            }
            case "first_kill" -> {
                if (trigger.entity() == null) {
                    LOGGER.warn("[MilestoneRegistry] milestone {} 的 first_kill trigger 未指定 entity，将无法触发", milestoneId);
                } else if (!net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(trigger.entity())) {
                    LOGGER.warn("[MilestoneRegistry] milestone {} 的 first_kill trigger 指向未知实体: {}，将无法触发", milestoneId, trigger.entity());
                }
            }
            case "obtain_item" -> {
                if (trigger.item() == null) {
                    LOGGER.warn("[MilestoneRegistry] milestone {} 的 obtain_item trigger 未指定 item，将无法触发", milestoneId);
                } else if (!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(trigger.item())) {
                    LOGGER.warn("[MilestoneRegistry] milestone {} 的 obtain_item trigger 指向未知物品: {}，将无法触发", milestoneId, trigger.item());
                }
            }
            default -> {}
        }
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
            // 里程碑系统被禁用（enabled=false）-> 清空注册表，避免跨服务器/跨存档残留旧里程碑
            LOGGER.warn("[MilestoneRegistry] 客户端收到的里程碑元数据为空，清空注册表");
            applyMilestones(List.of());
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
                ResourceLocation dimension = trigTag.contains("dimension")
                    ? new ResourceLocation(trigTag.getString("dimension")) : null;
                ResourceLocation item = trigTag.contains("item")
                    ? new ResourceLocation(trigTag.getString("item")) : null;
                trigger = new TriggerDef(type, y, entity, dimension, item);
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
        disabledAbilities = Set.of();
        initialized = false;
        AbilityRegistry.clearCountAtUnlockOverrides();
        version++;
    }

    // ===== 事件监听 =====

    /**
     * 注册数据包重载监听器。
     * Forge 的 AddReloadListenerEvent 允许在服务端启动和数据包重载时重新加载。
     * <p>
     * 通过 getResourceStack 读取所有数据包提供的同路径文件（内置 → 世界数据包），
     * 支持 merge:true 合并与 enabled:false 整体禁用。
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<JsonElement>() {
            @Override
            protected JsonElement prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                ResourceLocation loc = new ResourceLocation(AdventurePower.MODID, "adventure_power/milestones.json");
                List<Resource> stack = resourceManager.getResourceStack(loc);
                if (stack.isEmpty()) return null;
                // 1.20.1 getResourceStack 返回低→高优先级（index 0 = 内置，末尾 = 最高优先级数据包），
                // 直接按此顺序收集，mergePackFiles 后处理优先（字节码核实：从 pack 末尾收集后 Lists.reverse）
                JsonArray ordered = new JsonArray();
                for (Resource r : stack) {
                    try (Reader reader = r.openAsReader()) {
                        JsonElement el = GSON.fromJson(reader, JsonElement.class);
                        if (el != null && el.isJsonObject()) {
                            ordered.add(el);
                        } else {
                            LOGGER.error("[MilestoneRegistry] {} 内容非法（应为 JSON 对象），跳过该文件", r.sourcePackId());
                        }
                    } catch (Exception e) {
                        LOGGER.error("[MilestoneRegistry] 读取 {} 失败: {}", r.sourcePackId(), e.toString());
                    }
                }
                return ordered;
            }

            @Override
            protected void apply(JsonElement element, ResourceManager resourceManager, ProfilerFiller profiler) {
                MilestoneRegistry.clear();
                try {
                    if (element instanceof JsonArray arr && !arr.isEmpty()) {
                        JsonObject merged = mergePackFiles(arr);
                        if (merged == null) {
                            // 顶层文件 enabled=false -> 里程碑系统整体禁用，不回退内置
                            LOGGER.warn("[MilestoneRegistry] 里程碑系统已被数据包禁用（enabled=false）");
                            applyMilestones(List.of());
                            return;
                        }
                        loadFromJson(merged);
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

    /**
     * 合并各数据包提供的 milestones.json（输入按优先级低→高排序）。
     * <ul>
     *   <li>文件含 {@code "merge": true}：与本文件以下的内容按 id 合并（同 id 后者覆盖，新 id 追加）</li>
     *   <li>文件不含 merge：作为新的基准整体替换其以下所有内容（默认行为，与旧版一致）</li>
     *   <li>文件含 {@code "enabled": false}：该文件不贡献任何内容；若为最高优先级文件，整体禁用里程碑系统</li>
     *   <li>{@code "disabled_abilities"} 在所有启用的文件中累加（去重），最终由 loadFromJson 应用</li>
     * </ul>
     * <p>
     * 注意：非 merge 文件若 milestones 数组为空且未提供 {@code disabled_abilities}，最终合并结果
     * 的注册表为空，loadFromJson 会<b>回退内置默认</b>——无法用空数组"清空全部里程碑"，
     * 想清空请用顶层 {@code "enabled": false} 整体禁用。
     *
     * @return 合并后的根对象；整体禁用时返回 null
     */
    private static JsonObject mergePackFiles(JsonArray files) {
        List<JsonObject> accumulated = new ArrayList<>();
        JsonArray disabledAbilities = new JsonArray();
        Set<String> disabledSeen = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.get(i).getAsJsonObject();
            boolean isTop = (i == files.size() - 1);

            if (file.has("enabled") && !file.get("enabled").getAsBoolean()) {
                if (isTop) return null; // 顶层禁用 -> 整个系统关闭
                continue; // 非顶层禁用 -> 该文件不贡献内容
            }

            if (file.has("disabled_abilities") && file.get("disabled_abilities").isJsonArray()) {
                for (JsonElement e : file.getAsJsonArray("disabled_abilities")) {
                    if (!e.isJsonPrimitive()) continue;
                    if (disabledSeen.add(e.getAsString())) {
                        disabledAbilities.add(e);
                    }
                }
            }

            JsonArray arr = (file.has("milestones") && file.get("milestones").isJsonArray())
                ? file.getAsJsonArray("milestones") : new JsonArray();
            boolean merge = file.has("merge") && file.get("merge").getAsBoolean();
            if (!merge && arr.size() == 0 && disabledAbilities.size() > 0) {
                // 替换模式文件只提供了 disabled_abilities（无 milestones）：视为「只改黑名单」，
                // 保留下方内容；否则空数组会回退内置默认，把已累加的黑名单一并清掉
                LOGGER.warn("[MilestoneRegistry] 文件未提供 milestones 但提供了 disabled_abilities，按 merge 语义保留下方内容");
                continue;
            }
            accumulated = merge ? mergeById(accumulated, arr) : copyArray(arr);
        }

        JsonObject result = new JsonObject();
        JsonArray finalArr = new JsonArray();
        for (JsonObject m : accumulated) {
            finalArr.add(m);
        }
        result.add("milestones", finalArr);
        if (disabledAbilities.size() > 0) {
            result.add("disabled_abilities", disabledAbilities);
        }
        return result;
    }

    /** 按 id 合并：已有 id 保持原位置（值被覆盖），新 id 追加；无 id 条目原样追加（后续解析失败会被跳过） */
    private static List<JsonObject> mergeById(List<JsonObject> base, JsonArray additions) {
        LinkedHashMap<String, JsonObject> byId = new LinkedHashMap<>();
        List<JsonObject> unnamed = new ArrayList<>();
        for (JsonObject m : base) {
            String id = tryGetString(m, "id");
            if (id != null) byId.put(id, m); else unnamed.add(m);
        }
        for (JsonElement e : additions) {
            if (!e.isJsonObject()) {
                unnamed.add(new JsonObject());
                continue;
            }
            JsonObject m = e.getAsJsonObject();
            String id = tryGetString(m, "id");
            if (id != null) byId.put(id, m); else unnamed.add(m);
        }
        List<JsonObject> result = new ArrayList<>(byId.values());
        result.addAll(unnamed);
        return result;
    }

    /** 原样复制 milestones 数组（替换模式：作为新的基准） */
    private static List<JsonObject> copyArray(JsonArray arr) {
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement e : arr) {
            list.add(e.isJsonObject() ? e.getAsJsonObject() : new JsonObject());
        }
        return list;
    }

    private static String tryGetString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    /**
     * /reload 后向所有在线玩家广播同步（含里程碑注册表元数据），
     * 刷新客户端 tooltip / P 面板，避免显示旧数据包内容。
     * 登录时的同步由 CapabilityLifecycleHandler 负责，这里只处理全体广播（player == null）。
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        for (ServerPlayer p : event.getPlayerList().getPlayers()) {
            // /reload 缩小注册表后最终阶段自愈：9/10 时数据包删掉第 10 个里程碑 -> 9/9 已满足
            // 全部解锁，但 activateFinalStageIfReady 的级联（grantMilestone/catchUp）不会再触发
            // ——在此补跑（幂等：未满足条件或已激活时无操作）
            AdventureProgressCapability.getAdventureProgress(p).ifPresent(progress ->
                AdventureProgressCapability.activateFinalStageIfReady(p, progress));
            SyncUtil.syncToClient(p);
        }
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
            loadFromJson(root);
        } catch (Exception e) {
            LOGGER.error("[MilestoneRegistry] 加载内置 milestones.json 失败", e);
        }
    }
}
