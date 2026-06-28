package com.rosycentury.adventure_power.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rosycentury.adventure_power.capability.AdventureProgressCapability;
import com.rosycentury.adventure_power.capability.IAdventureProgress;
import com.rosycentury.adventure_power.milestone.Milestone;
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

/**
 * 冒险的终点合成配方 — 龙蛋 + 冒险的开始 → 冒险的终点。
 * 在标准 ShapelessRecipe 的基础上增加了里程碑 NBT 校验：
 * 冒险的开始必须完成全部 5 个里程碑（下界/凋零/监守者/末影龙/鞘翅）才会显示结果。
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
        // 先走标准无形状合成匹配（龙蛋 + 冒险的开始）
        if (!super.matches(inv, level)) return false;

        // 找到冒险的开始
        ItemStack beginStack = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(ModItems.ADVENTURE_BEGIN.get())) {
                beginStack = stack;
                break;
            }
        }
        if (beginStack == null) return false;

        // 路径 A：检查物品 NBT（缓存）
        AdventureProgressCapability.migrateOldStage(beginStack);
        if (hasAllFiveMilestones(beginStack)) return true;

        // 路径 B：物品 NBT 不满足，回退到玩家 Capability（处理容器存放等场景）
        for (var player : level.players()) {
            if (player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu craftingMenu
                && craftingMenu.getSlot(0).container == inv) {
                return AdventureProgressCapability.getAdventureProgress(player)
                    .map(IAdventureProgress::areAllMilestonesUnlocked)
                    .orElse(false);
            }
        }

        return false;
    }

    /** 检查物品 NBT 是否包含全部 5 个旧里程碑标记 */
    private static boolean hasAllFiveMilestones(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        return tag.getBoolean(milestoneNbtKey(Milestone.NETHER))
            && tag.getBoolean(milestoneNbtKey(Milestone.WITHER))
            && tag.getBoolean(milestoneNbtKey(Milestone.WARDEN))
            && tag.getBoolean(milestoneNbtKey(Milestone.DRAGON))
            && tag.getBoolean(milestoneNbtKey(Milestone.ELYTRA));
    }

    /** 里程碑 NBT 键（与 AdventureProgressCapability.milestoneNbtKey 一致） */
    private static String milestoneNbtKey(Milestone m) {
        String id = m.getId();
        return "MME_Milestone_" + Character.toUpperCase(id.charAt(0)) + id.substring(1);
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

        /** 复刻 ShapelessRecipe.Serializer 的 itemsFromJson（该方法是 private） */
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
