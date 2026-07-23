package com.ayin90723.adventure_power.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ayin90723.adventure_power.util.AdventureItemNbtUtil;
import com.ayin90723.adventure_power.milestone.Milestone;
import com.ayin90723.adventure_power.util.MilestoneRegistry;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 冒险的终点合成配方 — 龙蛋 + 冒险的开始 → 冒险的终点。
 * 在标准 ShapelessRecipe 的基础上增加了里程碑 NBT 校验：
 * 冒险的开始必须完成全部里程碑才会显示结果。
 * 里程碑列表由 MilestoneRegistry 动态定义。
 */
public class AdventureEndRecipe extends ShapelessRecipe {

    public AdventureEndRecipe(ResourceLocation id, String group,
                              CraftingBookCategory category,
                              ItemStack result,
                              NonNullList<Ingredient> ingredients) {
        super(id, group, category, result, ingredients);
    }

    @Override
    public boolean matches(CraftingContainer inv, Level level) {
        if (!super.matches(inv, level)) return false;

        ItemStack beginStack = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(ModItems.ADVENTURE_BEGIN.get())) {
                beginStack = stack;
                break;
            }
        }
        if (beginStack == null) return false;

        // 仅检查物品 NBT：冒险的开始在里程碑解锁时会更新自身 NBT 标记，
        // 无需回退到遍历 level.players() 检查 Capability（多人服可能匹配错玩家）。
        AdventureItemNbtUtil.migrateOldStage(beginStack);
        return hasAllMilestones(beginStack);
    }

    /** 检查物品 NBT 是否包含全部里程碑标记 */
    private static boolean hasAllMilestones(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        List<Milestone> all = MilestoneRegistry.getAll();
        if (all.isEmpty()) return false;
        for (Milestone m : all) {
            if (!tag.getBoolean(PersistentDataKeys.milestoneNbtKey(m.id()))) return false;
        }
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return Serializer.INSTANCE;
    }

    public static class Serializer implements RecipeSerializer<AdventureEndRecipe> {
        public static final Serializer INSTANCE = new Serializer();

        @Override
        public AdventureEndRecipe fromJson(ResourceLocation id, JsonObject json) {
            String group = GsonHelper.getAsString(json, "group", "");
            CraftingBookCategory category = CraftingBookCategory.CODEC.byName(
                GsonHelper.getAsString(json, "category", null), CraftingBookCategory.MISC);
            NonNullList<Ingredient> ingredients = itemsFromJson(
                GsonHelper.getAsJsonArray(json, "ingredients"));
            ItemStack result = ShapedRecipe.itemStackFromJson(
                GsonHelper.getAsJsonObject(json, "result"));
            return new AdventureEndRecipe(id, group, category, result, ingredients);
        }

        @Override
        public AdventureEndRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            String group = buf.readUtf();
            CraftingBookCategory category = buf.readEnum(CraftingBookCategory.class);
            int count = buf.readVarInt();
            NonNullList<Ingredient> ingredients = NonNullList.withSize(count, Ingredient.EMPTY);
            for (int i = 0; i < count; i++) {
                ingredients.set(i, Ingredient.fromNetwork(buf));
            }
            ItemStack result = buf.readItem();
            return new AdventureEndRecipe(id, group, category, result, ingredients);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, AdventureEndRecipe recipe) {
            buf.writeUtf(recipe.getGroup());
            buf.writeEnum(recipe.category());
            buf.writeVarInt(recipe.getIngredients().size());
            for (Ingredient ing : recipe.getIngredients()) {
                ing.toNetwork(buf);
            }
            buf.writeItem(recipe.getResultItem(null));
        }

        private static NonNullList<Ingredient> itemsFromJson(JsonArray array) {
            NonNullList<Ingredient> list = NonNullList.create();
            for (int i = 0; i < array.size(); i++) {
                Ingredient ingredient = Ingredient.fromJson(array.get(i));
                if (!ingredient.isEmpty()) {
                    list.add(ingredient);
                }
            }
            return list;
        }
    }
}
