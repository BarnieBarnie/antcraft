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

/**
 * Breaks a request ("1 wooden pickaxe") into crafting steps and raw materials, using
 * what is in stock first. Generic over the item type so it can be tested without a game.
 */
public final class TaskPlanner {
	/** Each ingredient is a set of interchangeable items (any planks) and how many are needed. */
	public record Recipe<I>(I output, int count, Map<Set<I>, Integer> ingredients) {
	}

	public record Craft<I>(Recipe<I> recipe, int times) {
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
		/** Set when a recipe chain needs an item it is already making; such a plan is unusable. */
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
			int times = (wanted + recipe.count() - 1) / recipe.count();
			Set<I> innerPath = new HashSet<>(path);
			innerPath.add(recipe.output());
			for (Map.Entry<Set<I>, Integer> ingredient : recipe.ingredients().entrySet()) {
				this.needAny(state, ingredient.getKey(), ingredient.getValue() * times, innerPath);
			}
			state.crafts.add(new Craft<>(recipe, times));
			int leftover = times * recipe.count() - wanted;
			if (leftover > 0) {
				state.stock.merge(recipe.output(), leftover, Integer::sum);
			}
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
