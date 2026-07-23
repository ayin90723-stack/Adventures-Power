package com.ayin90723.adventure_power.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * 计分板工具 - 维护"冒险解锁"计分板目标，供命令方块/数据包查询玩家是否全解锁。
 */
public final class ScoreboardUtil {

    private static final String UNLOCK_OBJECTIVE = "adventure_power_unlock";

    private ScoreboardUtil() {}

    /** 设置玩家解锁计分（1=全解锁，0=未全解锁）。仅服务端 ServerPlayer 生效。 */
    public static void updateScoreboard(Player player, boolean unlocked) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer)) return;
        Scoreboard sb = player.getScoreboard();
        Objective obj = sb.getObjective(UNLOCK_OBJECTIVE);
        if (obj == null) {
            obj = sb.addObjective(UNLOCK_OBJECTIVE, ObjectiveCriteria.DUMMY,
                Component.literal("MME Adventure"), ObjectiveCriteria.RenderType.INTEGER);
        }
        sb.getOrCreatePlayerScore(player.getScoreboardName(), obj).setScore(unlocked ? 1 : 0);
    }
}
