package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** The server's shaped and shapeless crafting recipes, in the planner's terms. */
public final class GameRecipeBook implements TaskPlanner.RecipeBook<Item> {
	private static final CraftingInput ANY_INPUT = CraftingInput.of(1, 1, List.of(ItemStack.EMPTY));

	private static RecipeManager cachedFor;
	private static GameRecipeBook cached;

	private final Map<Item, List<TaskPlanner.Recipe<Item>>> byOutput = new HashMap<>();

	/** Rebuilt whenever the server reloads its recipes. */
	public static synchronized GameRecipeBook of(MinecraftServer server) {
		RecipeManager manager = server.getRecipeManager();
		if (manager != cachedFor) {
			cached = new GameRecipeBook(manager);
			cachedFor = manager;
		}
		return cached;
	}

	private GameRecipeBook(RecipeManager manager) {
		for (RecipeHolder<?> holder : manager.getRecipes()) {
			if (holder.value() instanceof CraftingRecipe recipe && (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)) {
				// Shaped and shapeless recipes ignore the input when producing their result.
				ItemStack result = recipe.assemble(ANY_INPUT);
				List<Ingredient> ingredients = recipe.placementInfo().ingredients();
				if (result.isEmpty() || ingredients.isEmpty()) {
					continue;
				}
				Map<Set<Item>, Integer> counts = new LinkedHashMap<>();
				for (Ingredient ingredient : ingredients) {
					Set<Item> options = ingredient.items().map(Holder::value).collect(Collectors.toUnmodifiableSet());
					counts.merge(options, 1, Integer::sum);
				}
				this.byOutput.computeIfAbsent(result.getItem(), item -> new ArrayList<>())
					.add(new TaskPlanner.Recipe<>(result.getItem(), result.getCount(), Map.copyOf(counts)));
			}
		}
	}

	@Override
	public List<TaskPlanner.Recipe<Item>> recipesFor(Item item) {
		return this.byOutput.getOrDefault(item, List.of());
	}
}
