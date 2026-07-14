package com.ayin90723.adventure_power.util;

import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
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
    private static boolean initialized = false;

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

            for (String abilityId : abilities) {
                AbilityRegistry.setCountAtUnlock(abilityId, i + 1);
            }
        }

        milestones = List.copyOf(loaded);
        byId = new HashMap<>();
        byAdvancement = new HashMap<>();
        for (Milestone m : loaded) {
            byId.put(m.id(), m);
            if (m.advancement() != null) {
                byAdvancement.put(m.advancement(), m);
            }
        }
        initialized = true;

        LOGGER.info("[MilestoneRegistry] 加载完成: {} 个里程碑", milestones.size());
    }

    /** 客户端从网络包接收里程碑数据 */
    public static void clientInit(List<String> serializedMilestones) {
        StringBuilder json = new StringBuilder("{\"milestones\":[");
        for (int i = 0; i < serializedMilestones.size(); i++) {
            if (i > 0) json.append(",");
            json.append(serializedMilestones.get(i));
        }
        json.append("]}");
        JsonObject root = GSON.fromJson(json.toString(), JsonObject.class);
        loadFromJson(AdventurePower.MODID, root);
    }

    /** 数据包重载前清除现有数据 */
    public static void clear() {
        milestones = List.of();
        byAdvancement = Map.of();
        byId = Map.of();
        initialized = false;
        AbilityRegistry.clearCountAtUnlockOverrides();
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
                if (element != null && element.isJsonObject()) {
                    MilestoneRegistry.loadFromJson(AdventurePower.MODID, element.getAsJsonObject());
                } else {
                    LOGGER.warn("[MilestoneRegistry] 未找到 milestones.json，使用内置默认");
                    loadBuiltinDefaults();
                }
            }
        });
    }

    /** 当数据包中无 milestones.json 时使用模组内置默认 */
    private static void loadBuiltinDefaults() {
        String json = "{ \"milestones\": ["
            + "{\"id\":\"first_night\",\"name\":\"初次夜冕\",\"abilities\":[\"agility\",\"digging_power\",\"perpetual_blessing\"],\"trigger\":{\"type\":\"survive_night\"}},"
            + "{\"id\":\"first_death\",\"name\":\"初尝败绩\",\"abilities\":[\"void_step\",\"rapid_recovery\"],\"trigger\":{\"type\":\"first_death\"}},"
            + "{\"id\":\"first_trade\",\"name\":\"初次交易\",\"abilities\":[\"soul_bind\",\"knockback_resist\"],\"trigger\":{\"type\":\"first_trade\"}},"
            + "{\"id\":\"first_deep\",\"name\":\"初探地底\",\"abilities\":[\"damage_resist\",\"extended_reach\"],\"trigger\":{\"type\":\"y_below\",\"y\":0}},"
            + "{\"id\":\"first_enchant\",\"name\":\"初次附魔\",\"abilities\":[\"undying_gear\",\"fortune_favor\"],\"advancement\":\"minecraft:story/enchant_item\"},"
            + "{\"id\":\"nether\",\"name\":\"炽热之门\",\"abilities\":[\"env_immunity\",\"lifesteal\"],\"advancement\":\"minecraft:story/enter_the_nether\"},"
            + "{\"id\":\"wither\",\"name\":\"凋零之陨\",\"abilities\":[\"healing_block\",\"vitality\"],\"trigger\":{\"type\":\"first_kill\",\"entity\":\"minecraft:wither\"}},"
            + "{\"id\":\"warden\",\"name\":\"幽匿之惧\",\"abilities\":[\"resilience\",\"purified_soul\"],\"trigger\":{\"type\":\"first_kill\",\"entity\":\"minecraft:warden\"}},"
            + "{\"id\":\"dragon\",\"name\":\"终末之翼\",\"abilities\":[\"soar\",\"soul_quench\",\"piercing_gaze\",\"death_defy\"],\"advancement\":\"minecraft:end/kill_dragon\"},"
            + "{\"id\":\"elytra\",\"name\":\"苍穹之证\",\"abilities\":[\"shadow_kill\",\"true_health\",\"reject_manip\",\"active_skill\"],\"advancement\":\"minecraft:end/elytra\"}"
            + "]}";
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        loadFromJson(AdventurePower.MODID, root);
    }
}
