package com.rosycentury.adventure_power.milestone;

/**
 * 冒险里程碑 — 10 个节点，覆盖从开局到终局的完整游戏进程。
 * 每个里程碑对应的能力解锁见设计文档。
 */
public enum Milestone {
    FIRST_NIGHT("first_night", "初次夜冕"),
    FIRST_DEATH("first_death", "初尝败绩"),
    FIRST_TRADE("first_trade", "初次交易"),
    FIRST_DEEP("first_deep", "初探地底"),
    FIRST_ENCHANT("first_enchant", "初次附魔"),
    NETHER("nether", "炽热之门"),
    WITHER("wither", "凋零之陨"),
    WARDEN("warden", "幽匿之惧"),
    DRAGON("dragon", "终末之翼"),
    ELYTRA("elytra", "苍穹之证");

    private final String id;
    private final String name;

    Milestone(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    /** 里程碑序号（0-9），使用 Enum 内置的 ordinal() */
    public int index() { return ordinal(); }

    /** 根据 id 字符串查找里程碑，未找到返回 null */
    public static Milestone fromId(String id) {
        for (Milestone m : values()) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    @Override
    public String toString() { return id; }
}
