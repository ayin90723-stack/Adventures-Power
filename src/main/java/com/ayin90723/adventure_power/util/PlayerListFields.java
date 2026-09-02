package com.ayin90723.adventure_power.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PlayerList 双表反射 accessor（v1.4.9 容器审计 A9 / 重建第 7 步配套）。
 * <p>
 * 1.20.1 PlayerList 双表并存（javap 核实）：{@code players}（List，{@code /list}/存档/
 * 广播的枚举源）与 {@code playersByUUID}（Map，{@code getPlayer(UUID)} 的来源表）。
 * 已记载的容器抹除形态只删列表；若对手两表同删，仅重塞列表会留不一致——同审计同重塞。
 * <p>
 * 走 HealthUtil 惯例 SRG+dev 双名反射（不新增 mixin：两字段本就有公共读方法
 * {@code getPlayers()}/{@code getPlayer(UUID)}，但审计需要直拿表本体做 contains/重塞）。
 * 反射不可用时返回 null（审计基建失效按"跳过该半边"处理，不误报）。
 */
public final class PlayerListFields {

    private static final Field PLAYERS = HealthUtil.reflectField(PlayerList.class, "f_11196_", "players");
    private static final Field PLAYERS_BY_UUID = HealthUtil.reflectField(PlayerList.class, "f_11197_", "playersByUUID");

    private PlayerListFields() {
    }

    /** PlayerList.players 表本体（getPlayers 的来源字段）；反射不可用返回 null。 */
    @SuppressWarnings("unchecked")
    public static List<ServerPlayer> players(PlayerList list) {
        if (PLAYERS == null) return null;
        try {
            return (List<ServerPlayer>) PLAYERS.get(list);
        } catch (Exception e) {
            return null;
        }
    }

    /** PlayerList.playersByUUID 表本体（getPlayer(UUID) 的来源表）；反射不可用返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<UUID, ServerPlayer> playersByUUID(PlayerList list) {
        if (PLAYERS_BY_UUID == null) return null;
        try {
            return (Map<UUID, ServerPlayer>) PLAYERS_BY_UUID.get(list);
        } catch (Exception e) {
            return null;
        }
    }
}
