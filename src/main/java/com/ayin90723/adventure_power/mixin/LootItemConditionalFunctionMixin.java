package com.ayin90723.adventure_power.mixin;

import com.ayin90723.adventure_power.handler.LootAllHandler;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

/**
 * 满载而归 - 绕过 function 级 conditions。
 * <p>
 * {@link LootItemConditionalFunction#apply} 是 final：
 * <pre>compositePredicates.test(context) ? run(stack, ctx) : stack</pre>
 * function 级 conditions（如 SmeltItemFunction 的 entity_on_fire）在此检查。
 * entry 级（canRun）与 pool 级（addRandomItems）Mixin 覆盖不到这一层，
 * 导致非火杀时熔炼 function 不执行（生肉不变熟肉）--这就是"杀羊不掉熟肉"的根因。
 * <p>
 * 本 Mixin @Redirect compositePredicates.test，BYPASS=true 时返回 true，
 * 让所有条件性 function 无条件执行 run（熔炼/设置数量/附魔等），实现真正的"无视条件"。
 * <p>
 * method="apply"（接口方法，SRG 名未混淆）与 target=Predicate.test（Java 标准库）均不混淆，
 * 不需 refmap 条目。
 */
@Mixin(LootItemConditionalFunction.class)
public abstract class LootItemConditionalFunctionMixin {

    @Redirect(
        method = "apply",
        at = @At(value = "INVOKE", target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"),
        require = 0,
        remap = false
    )
    private boolean adventure_power$lootAllBypassFunctionCond(Predicate<LootContext> predicate, Object context) {
        if (LootAllHandler.BYPASS.get()) {
            return true;
        }
        return predicate.test((LootContext) context);
    }
}
