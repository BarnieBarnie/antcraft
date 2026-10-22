package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/** The server's shaped and shapeless crafting recipes and furnace recipes, in the planner's terms. */
public final class GameRecipeBook implements TaskPlanner.RecipeBook<Item> {
	private static final CraftingInput ANY_INPUT = CraftingInput.of(1, 1, List.of(ItemStack.EMPTY));

	private static RecipeManager cachedFor;
	private static GameRecipeBook cached;

	private static final SingleRecipeInput ANY_SINGLE_INPUT = new SingleRecipeInput(ItemStack.EMPTY);

	private final Map<Item, List<TaskPlanner.Recipe<Item>>> byOutput = new HashMap<>();
	private final List<TaskPlanner.Fuel<Item>> fuels;

	/** Rebuilt whenever the server reloads its recipes. */
	public static synchronized GameRecipeBook of(MinecraftServer server) {
		RecipeManager manager = server.getRecipeManager();
		if (manager != cachedFor) {
			cached = of(manager);
			cachedFor = manager;
		}
		return cached;
	}

	/** A fresh book of the given recipes, not cached. */
	public static GameRecipeBook of(RecipeManager manager) {
		return new GameRecipeBook(manager);
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
				// In the recipe's own order: Set.of and Map.copyOf iterate in an order that differs between
				// runs, and the planner takes the first of options and ingredients that tie.
				Map<Set<Item>, Integer> counts = new LinkedHashMap<>();
				for (Ingredient ingredient : ingredients) {
					counts.merge(options(ingredient), 1, Integer::sum);
				}
				this.byOutput.computeIfAbsent(result.getItem(), item -> new ArrayList<>())
					.add(new TaskPlanner.Recipe<>(result.getItem(), result.getCount(), Collections.unmodifiableMap(counts), false, isLarge(recipe, ingredients.size())));
			} else if (holder.value() instanceof SmeltingRecipe recipe) {
				// Like crafting results, furnace results do not depend on the input.
				ItemStack result = recipe.assemble(ANY_SINGLE_INPUT);
				Set<Item> options = options(recipe.input());
				if (result.isEmpty() || options.isEmpty()) {
					continue;
				}
				this.byOutput.computeIfAbsent(result.getItem(), item -> new ArrayList<>())
					.add(new TaskPlanner.Recipe<>(result.getItem(), result.getCount(), Map.of(options, 1), true));
			}
		}
		this.fuels = Fuels.order(StreamSupport.stream(BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.LOGS).spliterator(), false)
			.map(Holder::value).collect(Collectors.toCollection(LinkedHashSet::new)));
	}

	private static Set<Item> options(Ingredient ingredient) {
		Set<Item> options = ingredient.items().map(Holder::value).collect(Collectors.toCollection(LinkedHashSet::new));
		return Collections.unmodifiableSet(options);
	}

	/** Whether the recipe needs a crafting table's 3x3 grid: a shape wider or taller than 2, or more than 4 ingredients. */
	private static boolean isLarge(CraftingRecipe recipe, int ingredients) {
		return recipe instanceof ShapedRecipe shaped ? shaped.getWidth() > 2 || shaped.getHeight() > 2 : ingredients > 4;
	}

	@Override
	public List<TaskPlanner.Fuel<Item>> fuels() {
		return this.fuels;
	}

	@Override
	public List<TaskPlanner.Recipe<Item>> recipesFor(Item item) {
		return this.byOutput.getOrDefault(item, List.of());
	}
}
