package io.github.barniebarnie.antcraft.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns stock targets ("keep 2 pickaxes") into crafting orders for what is short.
 * Generic over the item type, like {@link TaskPlanner}, so it can be tested without a game.
 */
public final class StockPlanner {
	/**
	 * @param matches which items count toward the target
	 * @param make what to order when short, in order of preference
	 * @param alternatives what else to order when none of {@code make} can be made now, in order of preference,
	 *     such as baked potatoes for a food target that makes bread
	 */
	public record Target<I>(Predicate<I> matches, List<I> make, int count, List<I> alternatives) {
		public Target {
			make = List.copyOf(make);
			alternatives = List.copyOf(alternatives);
		}

		public Target(Predicate<I> matches, List<I> make, int count) {
			this(matches, make, count, List.of());
		}

		/** Everything that may be ordered for this target: {@code make}, then the alternatives. */
		public List<I> orderOptions() {
			List<I> options = new ArrayList<>(this.make);
			this.alternatives.stream().filter(option -> !options.contains(option)).forEach(options::add);
			return options;
		}
	}

	private StockPlanner() {
	}

	/** Everything in the given stocks together, such as the core and the storage chests. */
	public static <I> Map<I, Integer> total(List<Map<I, Integer>> stocks) {
		Map<I, Integer> total = new HashMap<>();
		stocks.forEach(stock -> stock.forEach((item, count) -> total.merge(item, count, Integer::sum)));
		return total;
	}

	/** How many items in {@code stock} count toward the target. */
	public static <I> int have(Target<I> target, Map<I, Integer> stock) {
		int have = 0;
		for (Map.Entry<I, Integer> entry : stock.entrySet()) {
			if (target.matches().test(entry.getKey())) {
				have += entry.getValue();
			}
		}
		return have;
	}

	/**
	 * As {@link #have(Target, Map)}, but what the colony can cook into another of the target's {@linkplain Target#orderOptions
	 * order options} now does not count: raw beef in the core does not meet a food target while it can become cooked beef,
	 * or the colony would never cook it. Without fuel it counts, as it is all there is.
	 */
	public static <I> int have(Target<I> target, Map<I, Integer> stock, TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		int have = 0;
		for (Map.Entry<I, Integer> entry : stock.entrySet()) {
			if (target.matches().test(entry.getKey()) && !cookableNow(target, entry.getKey(), stock, book, gatherable)) {
				have += entry.getValue();
			}
		}
		return have;
	}

	/** Whether {@code item} smelts into another of the target's order options, and stock allows that now. */
	private static <I> boolean cookableNow(Target<I> target, I item, Map<I, Integer> stock, TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		for (I option : target.orderOptions()) {
			if (!option.equals(item) && target.matches().test(option)
					&& book.recipesFor(option).stream().anyMatch(recipe -> recipe.smelting() && recipe.ingredients().keySet().stream().anyMatch(set -> set.contains(item)))
					&& makeableNow(option, 1, stock, book, gatherable) > 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The orders to add so the targets are met. Pending orders count as already on their way.
	 * Only what the crafter can make from stock right now is ordered, as many as the stock
	 * allows; later surveys top up as more materials come in. An order that had to wait for
	 * gathering would block its target, for example birch planks while only oak grows nearby.
	 * Raw food the colony can cook into one of a target's options does not count toward it (see
	 * {@link #have(Target, Map, TaskPlanner.RecipeBook, Predicate)}).
	 * Gatherable targets get no order: they are collected, not crafted.
	 */
	public static <I> Map<I, Integer> ordersToAdd(List<Target<I>> targets, Map<I, Integer> stock, Map<I, Integer> orders,
			TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		Map<I, Integer> toAdd = new LinkedHashMap<>();
		for (Target<I> target : targets) {
			int shortfall = target.count() - have(target, stock, book, gatherable) - have(target, orders) - have(target, toAdd);
			for (I option : target.orderOptions()) {
				if (shortfall <= 0) {
					break;
				}
				int count = gatherable.test(option) ? 0 : makeableNow(option, shortfall, stock, book, gatherable);
				if (count > 0) {
					toAdd.merge(option, count, Integer::sum);
					// The first option that can be made gets the whole order; stop here.
					break;
				}
			}
		}
		return toAdd;
	}

	/** The most of {@code item}, up to {@code max}, that can be crafted from stock alone. */
	private static <I> int makeableNow(I item, int max, Map<I, Integer> stock, TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		Map<I, Integer> available = new HashMap<>(stock);
		// Like an order, this asks for new items: those in stock are already counted.
		available.remove(item);
		// More is never easier to make than less, so search for the largest ready count.
		int low = 0;
		int high = max;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			TaskPlanner.Plan<I> plan = TaskPlanner.plan(item, mid, available, book, gatherable);
			if (plan.isReady() && !plan.crafts().isEmpty()) {
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return low;
	}
}
