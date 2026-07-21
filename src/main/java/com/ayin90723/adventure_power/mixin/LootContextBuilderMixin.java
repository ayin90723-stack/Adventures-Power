package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.handler.LootAllHandler;
import com.ayin90723.adventure_power.util.ConstantMaxRandomSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.loot.LootContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 满载而归 觉醒 - 取最大数量。
 * <p>
 * 注入 {@link LootContext.Builder#create}（SRG m_287259_），{@code @ModifyArg} 拦截内部
 * {@code new LootContext(params, randomsource, resolver, tableId)} 构造调用的第 2 个参数
 * （RandomSource，index=1），当 {@link LootAllHandler#BYPASS} 与 {@link LootAllHandler#AWAKEN}
 * 标志位同时为 true 时替换为 {@link ConstantMaxRandomSource}。
 * <p>
 * 效果：觉醒后满载而归额外滚取期间，SetItemCountFunction 等取掉落表最大数量。
 * <p>
 * 标志位隔离：
 * <ul>
 *   <li>原版掉落（BYPASS=false）-- 用原 random，不受影响</li>
 *   <li>基础能力（BYPASS=true, AWAKEN=false）-- 用原 random，数量随机</li>
 *   <li>觉醒（BYPASS=true, AWAKEN=true）-- 用 ConstantMaxRandomSource，数量最大化</li>
 * </ul>
 * target 为构造函数描述符，类名 / &lt;init&gt; / 参数类名在 SRG 下不变，无需 refmap 条目。
 */
@Mixin(LootContext.Builder.class)
public abstract class LootContextBuilderMixin {

    @ModifyArg(
        method = "m_287259_",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/loot/LootContext;<init>(Lnet/minecraft/world/level/storage/loot/LootParams;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/storage/loot/LootDataResolver;Lnet/minecraft/resources/ResourceLocation;)V"
        ),
        index = 1
    )
    private RandomSource adventure_power$lootAllMaxCount(RandomSource original) {
        if (LootAllHandler.BYPASS.get() && LootAllHandler.AWAKEN.get()) {
            return ConstantMaxRandomSource.INSTANCE;
        }
        return original;
    }
}
