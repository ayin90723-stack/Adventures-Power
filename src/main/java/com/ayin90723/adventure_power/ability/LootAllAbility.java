package com.ayin90723.adventure_power.ability;

import net.minecraft.network.chat.Component;

/**
 * 满载而归 - 击杀生物后，在原版掉落基础上额外按掉落表"每样一份"追加掉落。
 * <p>
 * 实际效果由 {@link com.ayin90723.adventure_power.handler.LootAllHandler} 监听
 * LivingDropsEvent + 3 个 Mixin 协作完成：
 * <ul>
 *   <li>{@code LootPoolEntryContainerMixin} - 无视 entry 级 LootItemCondition</li>
 *   <li>{@code LootPoolMixin} - 无视 pool 级条件 + 打破 weight 单选（遍历所有 entries 各生成一份）</li>
 *   <li>{@code LootContextBuilderMixin} - 觉醒时注入恒最大 RandomSource，取掉落表最大数量</li>
 * </ul>
 * <p>
 * 全兼容：用原版 createItemStack 解析，模组自定义 entry 与 function 均能正常生成。
 * <p>
 * 边界：Forge GlobalLootModifier (GLM) 类掉落独立于 loot table 结构，本能力无法覆盖。
 * 详见 docs/loot-all-ability-design.md。
 * <p>
 * 解锁里程碑：warden（幽匿之惧）
 */
public class LootAllAbility extends AbstractAbility {

    public LootAllAbility() {
        super(8);
    }

    @Override
    public String id() {
        return "loot_all";
    }

    @Override
    public Component name() {
        return Component.translatable("ability.adventure_power.loot_all");
    }

    @Override
    public Component description() {
        return Component.translatable("ability.adventure_power.loot_all.desc");
    }
}