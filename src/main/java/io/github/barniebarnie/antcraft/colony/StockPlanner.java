package io.github.barniebarnie.antcraft.colony;

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
	 */
	public record Target<I>(Predicate<I> matches, List<I> make, int count) {
	}

	private StockPlanner() {
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
	 * The orders to add so the targets are met. Pending orders count as already on their way.
	 * Only what the crafter can make from stock right now is ordered, as many as the stock
	 * allows; later surveys top up as more materials come in. An order that had to wait for
	 * gathering would block its target, for example birch planks while only oak grows nearby.
	 * Gatherable targets get no order: they are collected, not crafted.
	 */
	public static <I> Map<I, Integer> ordersToAdd(List<Target<I>> targets, Map<I, Integer> stock, Map<I, Integer> orders,
			TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		Map<I, Integer> toAdd = new LinkedHashMap<>();
		for (Target<I> target : targets) {
			int shortfall = target.count() - have(target, stock) - have(target, orders) - have(target, toAdd);
			for (I option : target.make()) {
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
