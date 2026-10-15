package io.github.barniebarnie.antcraft.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Breaks a request ("1 wooden pickaxe") into crafting steps and raw materials, using
 * what is in stock first. Generic over the item type so it can be tested without a game.
 */
public final class TaskPlanner {
	/** Ticks a furnace takes to smelt one item. */
	public static final int SMELT_TICKS = 200;

	/**
	 * Each ingredient is a set of interchangeable items (any planks) and how many are needed.
	 *
	 * @param smelting made in a furnace, one ingredient at a time and with fuel, instead of at a crafting table
	 */
	public record Recipe<I>(I output, int count, Map<Set<I>, Integer> ingredients, boolean smelting) {
		public Recipe(I output, int count, Map<Set<I>, Integer> ingredients) {
			this(output, count, ingredients, false);
		}
	}

	/** @param fuel what the furnace burns for a smelting step (empty for crafting) */
	public record Craft<I>(Recipe<I> recipe, int times, Map<I, Integer> fuel) {
		public Craft(Recipe<I> recipe, int times) {
			this(recipe, times, Map.of());
		}
	}

	/** Interchangeable fuel items and how long each one burns. */
	public record Fuel<I>(Set<I> items, int burnTicks) {
		/** How many of one fuel item it takes to smelt {@code smelts} items. */
		public int itemsFor(int smelts) {
			return (int) (((long) smelts * SMELT_TICKS + this.burnTicks - 1) / this.burnTicks);
		}
	}

	/**
	 * @param crafts crafting steps in a valid order (ingredients before what they make)
	 * @param toGather raw materials the colony has to collect first
	 * @param missing items the colony can neither craft nor gather
	 */
	public record Plan<I>(List<Craft<I>> crafts, Map<I, Integer> toGather, Map<I, Integer> missing) {
		/** Everything needed is in stock; only crafting is left. */
		public boolean isReady() {
			return this.toGather.isEmpty() && this.missing.isEmpty();
		}
	}

	public interface RecipeBook<I> {
		List<Recipe<I>> recipesFor(I item);

		/** Furnace fuels, best first. Without any, smelting needs no fuel. */
		default List<Fuel<I>> fuels() {
			return List.of();
		}
	}

	private TaskPlanner() {
	}

	public static <I> Plan<I> plan(I item, int count, Map<I, Integer> stock, RecipeBook<I> book, Predicate<I> gatherable) {
		State<I> state = new State<>(new HashMap<>(stock));
		new Search<>(book, gatherable).need(state, item, count, new HashSet<>());
		return new Plan<>(List.copyOf(state.crafts), Map.copyOf(state.gather), Map.copyOf(state.missing));
	}

	private static final class State<I> {
		final Map<I, Integer> stock;
		final List<Craft<I>> crafts = new ArrayList<>();
		final Map<I, Integer> gather = new LinkedHashMap<>();
		final Map<I, Integer> missing = new LinkedHashMap<>();
		/**
		 * Set when a recipe chain needs an item it is already making, or uses a storage recipe
		 * without having its ingredient; such a plan is unusable.
		 */
		boolean cycle;

		State(Map<I, Integer> stock) {
			this.stock = stock;
		}

		State<I> copy() {
			State<I> copy = new State<>(new HashMap<>(this.stock));
			copy.crafts.addAll(this.crafts);
			copy.gather.putAll(this.gather);
			copy.missing.putAll(this.missing);
			copy.cycle = this.cycle;
			return copy;
		}

		void assign(State<I> other) {
			this.stock.clear();
			this.stock.putAll(other.stock);
			this.crafts.clear();
			this.crafts.addAll(other.crafts);
			this.gather.clear();
			this.gather.putAll(other.gather);
			this.missing.clear();
			this.missing.putAll(other.missing);
			this.cycle = other.cycle;
		}

		/** Lower is better: missing items are worst, then gathering, then crafting effort. */
		long[] score() {
			long missingCount = this.missing.values().stream().mapToLong(Integer::longValue).sum();
			long gatherCount = this.gather.values().stream().mapToLong(Integer::longValue).sum();
			long craftCount = this.crafts.stream().mapToLong(Craft::times).sum();
			return new long[] {missingCount, gatherCount, craftCount};
		}

		/** Like {@link #score()}, but using up less stock beats crafting less: for choosing a fuel. */
		long[] fuelScore() {
			long[] score = this.score();
			long stockLeft = this.stock.values().stream().mapToLong(Integer::longValue).sum();
			return new long[] {score[0], score[1], -stockLeft, score[2]};
		}

		int take(I item, int wanted) {
			int available = this.stock.getOrDefault(item, 0);
			int taken = Math.min(available, wanted);
			if (taken > 0) {
				this.stock.put(item, available - taken);
			}
			return taken;
		}
	}

	private static final Comparator<long[]> BY_SCORE = (a, b) -> {
		for (int i = 0; i < a.length; i++) {
			int c = Long.compare(a[i], b[i]);
			if (c != 0) {
				return c;
			}
		}
		return 0;
	};

	private record Search<I>(RecipeBook<I> book, Predicate<I> gatherable) {
		void need(State<I> state, I item, int count, Set<I> path) {
			int remaining = count - state.take(item, count);
			if (remaining == 0) {
				return;
			}
			if (this.gatherable.test(item)) {
				state.gather.merge(item, remaining, Integer::sum);
				return;
			}
			if (path.contains(item)) {
				state.cycle = true;
				return;
			}

			// Break the item down when any recipe is usable, so the player sees which ingredients
			// are missing; only report the item itself when nothing can make it.
			State<I> best = null;
			for (Recipe<I> recipe : this.book.recipesFor(item)) {
				State<I> trial = state.copy();
				this.craft(trial, recipe, remaining, path);
				if (!trial.cycle && (best == null || BY_SCORE.compare(trial.score(), best.score()) < 0)) {
					best = trial;
				}
			}
			if (best == null) {
				state.missing.merge(item, remaining, Integer::sum);
			} else {
				state.assign(best);
			}
		}

		void craft(State<I> state, Recipe<I> recipe, int wanted, Set<I> path) {
			if (!recipe.smelting() && this.isStorageRecipe(recipe) && recipe.ingredients().keySet().stream().flatMap(Set::stream).noneMatch(i -> state.stock.getOrDefault(i, 0) > 0)) {
				// Packing ingots into a block or unpacking it only makes sense with some already at hand.
				state.cycle = true;
				return;
			}
			int times = (wanted + recipe.count() - 1) / recipe.count();
			Set<I> innerPath = new HashSet<>(path);
			innerPath.add(recipe.output());
			for (Map.Entry<Set<I>, Integer> ingredient : recipe.ingredients().entrySet()) {
				this.needAny(state, ingredient.getKey(), ingredient.getValue() * times, innerPath);
			}
			Map<I, Integer> fuel = recipe.smelting() ? this.fuel(state, times * recipe.count(), innerPath) : Map.of();
			state.crafts.add(new Craft<>(recipe, times, fuel));
			int leftover = times * recipe.count() - wanted;
			if (leftover > 0) {
				state.stock.merge(recipe.output(), leftover, Integer::sum);
			}
		}

		/**
		 * Fuel for smelting {@code smelts} items. Either burns what is in stock in the book's order
		 * of preference (gathering the rest), or first makes a better fuel, such as charcoal from
		 * logs, when that uses up less. A fuel on {@code path} is being made already, so it is not
		 * made again to fuel itself.
		 */
		Map<I, Integer> fuel(State<I> state, int smelts, Set<I> path) {
			State<I> best = state.copy();
			Map<I, Integer> bestUsed = this.burn(best, smelts);
			List<Fuel<I>> fuels = this.book.fuels();
			for (int rank = 0; rank < fuels.size(); rank++) {
				for (I item : fuels.get(rank).items()) {
					if (path.contains(item)) {
						continue;
					}
					for (Recipe<I> recipe : this.book.recipesFor(item)) {
						State<I> trial = state.copy();
						Map<I, Integer> used = this.makeFuel(trial, smelts, rank, item, recipe, path);
						if (used != null && !trial.cycle && BY_SCORE.compare(trial.fuelScore(), best.fuelScore()) < 0) {
							best = trial;
							bestUsed = used;
						}
					}
				}
			}
			state.assign(best);
			return bestUsed;
		}

		/**
		 * Burns stock of the fuels up to {@code rank} and makes {@code item} for the rest, or returns
		 * null when stock alone covers it.
		 */
		@Nullable Map<I, Integer> makeFuel(State<I> state, int smelts, int rank, I item, Recipe<I> recipe, Set<I> path) {
			Map<I, Integer> used = new LinkedHashMap<>();
			long ticks = this.burnStock(state, this.book.fuels().subList(0, rank + 1), (long) smelts * SMELT_TICKS, used);
			if (ticks <= 0) {
				return null;
			}
			Fuel<I> fuel = this.book.fuels().get(rank);
			int count = (int) ((ticks + fuel.burnTicks() - 1) / fuel.burnTicks());
			this.craft(state, recipe, count, path);
			used.merge(item, count, Integer::sum);
			return Map.copyOf(used);
		}

		/** Burns fuel from stock, best first, and returns the ticks still to cover. */
		long burnStock(State<I> state, List<Fuel<I>> fuels, long ticks, Map<I, Integer> used) {
			for (Fuel<I> fuel : fuels) {
				List<I> byStock = new ArrayList<>(fuel.items());
				byStock.sort(Comparator.comparingInt((I option) -> state.stock.getOrDefault(option, 0)).reversed());
				for (I item : byStock) {
					if (ticks <= 0) {
						return ticks;
					}
					int taken = state.take(item, (int) ((ticks + fuel.burnTicks() - 1) / fuel.burnTicks()));
					if (taken > 0) {
						used.merge(item, taken, Integer::sum);
						ticks -= (long) taken * fuel.burnTicks();
					}
				}
			}
			return ticks;
		}

		/**
		 * Fuel from stock in the book's order of preference, and whatever is still short as the
		 * first fuel that can be gathered.
		 */
		Map<I, Integer> burn(State<I> state, int smelts) {
			Map<I, Integer> used = new LinkedHashMap<>();
			long ticks = this.burnStock(state, this.book.fuels(), (long) smelts * SMELT_TICKS, used);
			if (ticks <= 0 || this.book.fuels().isEmpty()) {
				return Map.copyOf(used);
			}
			int smeltsLeft = (int) ((ticks + SMELT_TICKS - 1) / SMELT_TICKS);
			for (Fuel<I> fuel : this.book.fuels()) {
				for (I item : fuel.items()) {
					if (this.gatherable.test(item)) {
						int count = fuel.itemsFor(smeltsLeft);
						state.gather.merge(item, count, Integer::sum);
						used.merge(item, count, Integer::sum);
						return Map.copyOf(used);
					}
				}
			}
			Fuel<I> best = this.book.fuels().getFirst();
			I item = best.items().iterator().next();
			state.missing.merge(item, best.itemsFor(smeltsLeft), Integer::sum);
			used.merge(item, best.itemsFor(smeltsLeft), Integer::sum);
			return Map.copyOf(used);
		}

		/**
		 * A storage recipe turns one item into another that turns straight back
		 * (9 ingots to a block, a block to 9 ingots).
		 */
		boolean isStorageRecipe(Recipe<I> recipe) {
			if (recipe.ingredients().size() != 1) {
				return false;
			}
			Set<I> ingredient = recipe.ingredients().keySet().iterator().next();
			for (I item : ingredient) {
				for (Recipe<I> back : this.book.recipesFor(item)) {
					if (back.ingredients().size() == 1 && back.ingredients().keySet().iterator().next().contains(recipe.output())) {
						return true;
					}
				}
			}
			return false;
		}

		/** Needs {@code count} items, any mix of {@code options}. */
		void needAny(State<I> state, Set<I> options, int count, Set<I> path) {
			int remaining = count;
			List<I> byStock = new ArrayList<>(options);
			byStock.sort(Comparator.comparingInt((I option) -> state.stock.getOrDefault(option, 0)).reversed());
			for (I option : byStock) {
				remaining -= state.take(option, remaining);
			}
			if (remaining == 0) {
				return;
			}

			State<I> best = null;
			for (I option : byStock) {
				State<I> trial = state.copy();
				this.need(trial, option, remaining, path);
				if (best == null || (best.cycle && !trial.cycle) || (best.cycle == trial.cycle && BY_SCORE.compare(trial.score(), best.score()) < 0)) {
					best = trial;
				}
			}
			state.assign(best);
		}
	}
}
