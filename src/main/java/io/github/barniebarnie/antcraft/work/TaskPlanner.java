package io.github.barniebarnie.antcraft.work;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
	 * @param large needs the 3x3 grid of a crafting table, not the 2x2 one a bot can craft in anywhere
	 */
	public record Recipe<I>(I output, int count, Map<Set<I>, Integer> ingredients, boolean smelting, boolean large) {
		public Recipe(I output, int count, Map<Set<I>, Integer> ingredients) {
			this(output, count, ingredients, false);
		}

		public Recipe(I output, int count, Map<Set<I>, Integer> ingredients, boolean smelting) {
			this(output, count, ingredients, smelting, false);
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

	/**
	 * Recipes the search expands for one plan before it stops trying alternatives. Plans for
	 * everyday items need a few hundred; the cap keeps items with many alternative recipes and
	 * ingredients (beds, wool and dyes of every colour) from searching for ever.
	 */
	static final int EXPANSION_BUDGET = 20_000;

	/** How many plans made lately are kept (see {@link #plan}). */
	static final int KEPT_PLANS = 256;
	/** The plans made lately, the least recently used first. */
	private static final Map<PlanKey, Plan<?>> KEPT = new LinkedHashMap<>(KEPT_PLANS, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<PlanKey, Plan<?>> eldest) {
			return this.size() > KEPT_PLANS;
		}
	};

	/** What a plan is made from; the recipe book and gatherables compare as their own objects do, by identity unless they say otherwise. */
	private record PlanKey(Object item, int count, Map<?, Integer> stock, RecipeBook<?> book, Predicate<?> gatherable) {
	}

	/**
	 * How to get {@code count} of {@code item} from {@code stock}: what to craft in which order, and what to gather.
	 * Many bots plan the same orders from the same stock, such as every hauler asking what the core keeps for them, so the
	 * last {@link #KEPT_PLANS} plans are kept and a plan for the same item, count, stock, recipe book and gatherables is
	 * not searched for again. A book must not change its recipes once it has planned.
	 */
	@SuppressWarnings("unchecked")
	public static <I> Plan<I> plan(I item, int count, Map<I, Integer> stock, RecipeBook<I> book, Predicate<I> gatherable) {
		PlanKey key = new PlanKey(item, count, Map.copyOf(stock), book, gatherable);
		synchronized (KEPT) {
			Plan<?> kept = KEPT.get(key);
			if (kept != null) {
				return (Plan<I>) kept;
			}
		}
		Plan<I> plan = search(item, count, stock, book, gatherable);
		synchronized (KEPT) {
			KEPT.put(key, plan);
		}
		return plan;
	}

	/** How many stock entries a search for a plan copies, for checking that a large stock stays cheap. */
	static <I> long stockEntriesCopied(I item, int count, Map<I, Integer> stock, RecipeBook<I> book, Predicate<I> gatherable) {
		long[] copied = {0};
		search(item, count, stock, book, gatherable, copied);
		return copied[0];
	}

	private static <I> Plan<I> search(I item, int count, Map<I, Integer> stock, RecipeBook<I> book, Predicate<I> gatherable) {
		return search(item, count, stock, book, gatherable, new long[1]);
	}

	private static <I> Plan<I> search(I item, int count, Map<I, Integer> stock, RecipeBook<I> book, Predicate<I> gatherable, long[] copied) {
		State<I> state = new State<>(new Stock<>(stock, copied));
		new Search<>(book, gatherable, new HashMap<>(), new IdentityHashMap<>(), new int[] {EXPANSION_BUDGET, 0, 0}).need(state, item, count, new HashSet<>(), true);
		// Map.copyOf would iterate in an order that differs between runs.
		return new Plan<>(List.copyOf(state.crafts), Collections.unmodifiableMap(state.gather), Collections.unmodifiableMap(state.missing));
	}

	/**
	 * The stock a plan has left: the stock it started from, read once and shared by every trial of one search, and what
	 * the plan has taken from it or added to it. Trials copy and compare only those changes, so a very large stock, such
	 * as a full storage room, costs no more to plan from than a small one (#322).
	 */
	private static final class Stock<I> {
		/** The stock the search started from, items with none left out. */
		private final Map<I, Integer> base;
		private final long baseTotal;
		/** The amounts that differ from {@link #base}. */
		private final Map<I, Integer> changes;
		/** Stock entries copied so far, for {@link #stockEntriesCopied}. */
		private final long[] copied;

		Stock(Map<I, Integer> stock, long[] copied) {
			this.base = new HashMap<>();
			stock.forEach((item, amount) -> {
				if (amount > 0) {
					this.base.put(item, amount);
				}
			});
			this.baseTotal = this.base.values().stream().mapToLong(Integer::longValue).sum();
			this.changes = new HashMap<>();
			this.copied = copied;
			copied[0] += stock.size();
		}

		private Stock(Stock<I> other) {
			this.base = other.base;
			this.baseTotal = other.baseTotal;
			this.changes = new HashMap<>(other.changes);
			this.copied = other.copied;
			this.copied[0] += other.changes.size();
		}

		int get(I item) {
			Integer changed = this.changes.get(item);
			return changed != null ? changed : this.base.getOrDefault(item, 0);
		}

		void set(I item, int amount) {
			if (amount == this.base.getOrDefault(item, 0)) {
				this.changes.remove(item);
			} else {
				this.changes.put(item, amount);
			}
		}

		void add(I item, int amount) {
			this.set(item, this.get(item) + amount);
		}

		/** How many items there are in all. */
		long total() {
			long total = this.baseTotal;
			for (Map.Entry<I, Integer> changed : this.changes.entrySet()) {
				total += changed.getValue() - this.base.getOrDefault(changed.getKey(), 0);
			}
			return total;
		}

		Stock<I> copy() {
			return new Stock<>(this);
		}

		/**
		 * What tells this stock apart from others of the same search: two stocks of one search hold the same items
		 * when their changes are equal.
		 */
		Map<I, Integer> changes() {
			this.copied[0] += this.changes.size();
			return Map.copyOf(this.changes);
		}

		/** Takes over the amounts of {@code changes}, from {@link #changes()} of a stock of the same search. */
		void restore(Map<I, Integer> changes) {
			this.copied[0] += changes.size();
			this.changes.clear();
			this.changes.putAll(changes);
		}

		void assign(Stock<I> other) {
			this.restore(other.changes);
		}
	}

	private static final class State<I> {
		final Stock<I> stock;
		final List<Craft<I>> crafts = new ArrayList<>();
		final Map<I, Integer> gather = new LinkedHashMap<>();
		final Map<I, Integer> missing = new LinkedHashMap<>();
		/**
		 * Set when a recipe chain needs an item it is already making, or uses a storage recipe
		 * without having its ingredient; such a plan is unusable.
		 */
		boolean cycle;
		/**
		 * A score this plan has to stay under to be worth finishing, because a plan already found
		 * for the same need scores that much. Scores only grow while a plan is worked out.
		 */
		long @Nullable [] bound;
		/** Set when the plan reached its bound and was abandoned; it loses to any finished plan. */
		boolean pruned;

		State(Stock<I> stock) {
			this.stock = stock;
		}

		State<I> copy() {
			State<I> copy = new State<>(this.stock.copy());
			copy.crafts.addAll(this.crafts);
			copy.gather.putAll(this.gather);
			copy.missing.putAll(this.missing);
			copy.cycle = this.cycle;
			copy.bound = this.bound;
			copy.pruned = this.pruned;
			return copy;
		}

		void assign(State<I> other) {
			this.stock.assign(other.stock);
			this.crafts.clear();
			this.crafts.addAll(other.crafts);
			this.gather.clear();
			this.gather.putAll(other.gather);
			this.missing.clear();
			this.missing.putAll(other.missing);
			this.cycle = other.cycle;
			// The bound belongs to this state's caller, not to the trial that is taken over.
			this.pruned = other.pruned;
		}

		/** Abandons this plan once it scores no better than {@code best}. */
		void boundBy(long[] best) {
			if (this.bound == null || BY_SCORE.compare(best, this.bound) < 0) {
				this.bound = best;
			}
		}

		/** True when this plan is abandoned: it scores at least its bound already. */
		boolean prune() {
			if (!this.pruned && this.bound != null && BY_SCORE.compare(this.score(), this.bound) >= 0) {
				this.pruned = true;
			}
			return this.pruned;
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
			long stockLeft = this.stock.total();
			return new long[] {score[0], score[1], -stockLeft, score[2]};
		}

		int take(I item, int wanted) {
			int available = this.stock.get(item);
			int taken = Math.min(available, wanted);
			if (taken > 0) {
				this.stock.set(item, available - taken);
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

	/**
	 * Everything {@link Search#need} decides depends on: the item, how many, the stock (as its {@linkplain Stock#changes changes}), whether
	 * conversion recipes may make it, whether the plan is broken already and whether it is for making fuel (which
	 * burns its own fuel rather than make it).
	 */
	private record NeedKey<I>(I item, int count, Map<I, Integer> stock, boolean conversions, boolean cycle, boolean makingFuel) {
		static <I> NeedKey<I> of(State<I> state, I item, int count, boolean conversions, boolean makingFuel) {
			return new NeedKey<>(item, count, state.stock.changes(), conversions, state.cycle, makingFuel);
		}
	}

	/** What one {@link Search#need} call added to the plan, to replay it when the same need comes up again. */
	private record NeedResult<I>(Map<I, Integer> stock, List<Craft<I>> crafts, Map<I, Integer> gather, Map<I, Integer> missing, boolean cycle) {
		static <I> NeedResult<I> between(State<I> before, State<I> after) {
			return new NeedResult<>(after.stock.changes(), List.copyOf(after.crafts.subList(before.crafts.size(), after.crafts.size())),
				difference(before.gather, after.gather), difference(before.missing, after.missing), after.cycle);
		}

		private static <I> Map<I, Integer> difference(Map<I, Integer> before, Map<I, Integer> after) {
			Map<I, Integer> added = new LinkedHashMap<>();
			after.forEach((item, amount) -> {
				int more = amount - before.getOrDefault(item, 0);
				if (more != 0) {
					added.put(item, more);
				}
			});
			return added;
		}

		void replay(State<I> state) {
			state.stock.restore(this.stock);
			state.crafts.addAll(this.crafts);
			this.gather.forEach((item, amount) -> state.gather.merge(item, amount, Integer::sum));
			this.missing.forEach((item, amount) -> state.missing.merge(item, amount, Integer::sum));
			state.cycle = this.cycle;
		}
	}

	/**
	 * Tries the alternatives depth first. Each need is worked out once per stock: the answer is
	 * remembered and replayed, so recipes that share ingredients do not search them again. A
	 * remembered plan made while some items were off limits (being made further up) is still a
	 * valid plan elsewhere; only a dead end caused by that is not remembered.
	 *
	 * @param conversionRecipes which recipes {@link #isConversion} found to be conversions
	 * @param budget recipes still to expand, then the number of times the search ran into an item it is making, then
	 *     how many fuels are being made around the current step
	 */
	private record Search<I>(RecipeBook<I> book, Predicate<I> gatherable, Map<NeedKey<I>, NeedResult<I>> memo,
		Map<Recipe<I>, Boolean> conversionRecipes, int[] budget) {
		private static final int LEFT = 0;
		private static final int PATH_HITS = 1;
		private static final int MAKING_FUEL = 2;

		boolean exhausted() {
			return this.budget[LEFT] <= 0;
		}

		/** @param conversions whether {@code item} may be made by a {@linkplain #isConversion conversion recipe} */
		void need(State<I> state, I item, int count, Set<I> path, boolean conversions) {
			if (state.prune()) {
				return;
			}
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
				this.budget[PATH_HITS]++;
				return;
			}
			NeedKey<I> key = NeedKey.of(state, item, remaining, conversions, this.budget[MAKING_FUEL] > 0);
			NeedResult<I> known = this.memo.get(key);
			if (known != null) {
				known.replay(state);
				return;
			}
			State<I> before = state.copy();
			int pathHits = this.budget[PATH_HITS];
			this.expand(state, item, remaining, path, conversions);
			// Over the caller's bound, a trial the bound cut short might have been the better one:
			// such an answer is abandoned, not remembered.
			if (!state.prune() && (!state.cycle || this.budget[PATH_HITS] == pathHits)) {
				this.memo.put(key, NeedResult.between(before, state));
			}
		}

		void expand(State<I> state, I item, int remaining, Set<I> path, boolean conversions) {
			// Break the item down when any recipe is usable, so the player sees which ingredients
			// are missing; only report the item itself when nothing can make it. Once the budget
			// is spent, no more recipes are tried and the item counts as missing.
			State<I> best = null;
			boolean pruned = false;
			for (Recipe<I> recipe : this.book.recipesFor(item)) {
				if (!conversions && this.isConversion(recipe)) {
					continue;
				}
				if (this.exhausted()) {
					break;
				}
				this.budget[LEFT]--;
				State<I> trial = state.copy();
				if (best != null) {
					trial.boundBy(best.score());
				}
				this.craft(trial, recipe, remaining, path);
				if (trial.pruned) {
					pruned = true;
				} else if (!trial.cycle && (best == null || BY_SCORE.compare(trial.score(), best.score()) < 0)) {
					best = trial;
				}
			}
			if (best == null && pruned) {
				// Without a finished trial to bound them, the trials ran into the caller's bound.
				state.pruned = true;
			} else if (best == null) {
				state.missing.merge(item, remaining, Integer::sum);
			} else {
				state.assign(best);
			}
		}

		void craft(State<I> state, Recipe<I> recipe, int wanted, Set<I> path) {
			if (!recipe.smelting() && this.isStorageRecipe(recipe) && recipe.ingredients().keySet().stream().flatMap(Set::stream).noneMatch(i -> state.stock.get(i) > 0)) {
				// Packing ingots into a block or unpacking it only makes sense with some already at hand.
				state.cycle = true;
				return;
			}
			int times = (wanted + recipe.count() - 1) / recipe.count();
			Set<I> innerPath = new HashSet<>(path);
			innerPath.add(recipe.output());
			// What a conversion recipe converts is never converted itself: converting it straight from the original is as good.
			boolean conversions = !this.isConversion(recipe);
			for (Map.Entry<Set<I>, Integer> ingredient : recipe.ingredients().entrySet()) {
				this.needAny(state, ingredient.getKey(), ingredient.getValue() * times, innerPath, conversions);
				if (state.pruned) {
					return;
				}
			}
			Map<I, Integer> fuel = recipe.smelting() ? this.fuel(state, times * recipe.count(), innerPath) : Map.of();
			state.crafts.add(new Craft<>(recipe, times, fuel));
			int leftover = times * recipe.count() - wanted;
			if (leftover > 0) {
				state.stock.add(recipe.output(), leftover);
			}
		}

		/**
		 * Fuel for smelting {@code smelts} items. Either burns what is in stock in the book's order
		 * of preference (gathering the rest), or first makes a better fuel, such as charcoal from
		 * logs, when that uses up less. A fuel on {@code path} is being made already, so it is not
		 * made again to fuel itself. Fuel for making fuel is only burnt or gathered, never made: making
		 * fuel for every fuel and recipe at every level grows with the number of fuels to the power of
		 * the depth and could hold up a server tick for ever (#309). Each fuel recipe tried spends the
		 * expansion budget, and once it is spent the fuel is burnt.
		 */
		Map<I, Integer> fuel(State<I> state, int smelts, Set<I> path) {
			State<I> best = state.copy();
			Map<I, Integer> bestUsed = this.burn(best, smelts);
			if (this.budget[MAKING_FUEL] > 0) {
				state.assign(best);
				return bestUsed;
			}
			List<Fuel<I>> fuels = this.book.fuels();
			for (int rank = 0; rank < fuels.size(); rank++) {
				for (I item : fuels.get(rank).items()) {
					if (path.contains(item)) {
						continue;
					}
					for (Recipe<I> recipe : this.book.recipesFor(item)) {
						if (this.exhausted()) {
							break;
						}
						this.budget[LEFT]--;
						State<I> trial = state.copy();
						Map<I, Integer> used;
						this.budget[MAKING_FUEL]++;
						try {
							used = this.makeFuel(trial, smelts, rank, item, recipe, path);
						} finally {
							this.budget[MAKING_FUEL]--;
						}
						if (used != null && !trial.cycle && !trial.pruned && BY_SCORE.compare(trial.fuelScore(), best.fuelScore()) < 0) {
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
				byStock.sort(Comparator.comparingInt((I option) -> state.stock.get(option)).reversed());
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

		/**
		 * A conversion recipe turns an item into one that can be turned back, possibly with other
		 * ingredients: dyeing wool or a bed another colour, or packing ingots into a block.
		 */
		boolean isConversion(Recipe<I> recipe) {
			return this.conversionRecipes.computeIfAbsent(recipe, r -> {
				for (Set<I> ingredient : r.ingredients().keySet()) {
					for (I item : ingredient) {
						for (Recipe<I> back : this.book.recipesFor(item)) {
							if (back.ingredients().keySet().stream().anyMatch(options -> options.contains(r.output()))) {
								return true;
							}
						}
					}
				}
				return false;
			});
		}

		/** Needs {@code count} items, any mix of {@code options}. */
		void needAny(State<I> state, Set<I> options, int count, Set<I> path, boolean conversions) {
			if (state.pruned) {
				return;
			}
			int remaining = count;
			List<I> byStock = new ArrayList<>(options);
			byStock.sort(Comparator.comparingInt((I option) -> state.stock.get(option)).reversed());
			for (I option : byStock) {
				remaining -= state.take(option, remaining);
			}
			if (remaining == 0) {
				return;
			}

			State<I> best = null;
			for (I option : byStock) {
				if (best != null && this.exhausted()) {
					break;
				}
				State<I> trial = state.copy();
				if (best != null && !best.cycle && !best.pruned) {
					trial.boundBy(best.score());
				}
				this.need(trial, option, remaining, path, conversions);
				if (best == null || (best.pruned && !trial.pruned) || (!trial.pruned && ((best.cycle && !trial.cycle)
					|| (best.cycle == trial.cycle && BY_SCORE.compare(trial.score(), best.score()) < 0)))) {
					best = trial;
				}
			}
			state.assign(best);
		}
	}
}
