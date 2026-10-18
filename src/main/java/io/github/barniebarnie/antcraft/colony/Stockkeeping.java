package io.github.barniebarnie.antcraft.colony;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * What a hauler moves between the core and the storage chests. The core keeps a working stock:
 * each item's stock target and what open orders need, and at least {@code minKeep} (a stack) of
 * anything. Above that it goes to storage; below a target or an order's needs it comes back.
 * Generic over the item type, like {@link StockPlanner}, so it can be tested without a game.
 */
public final class Stockkeeping {
	private Stockkeeping() {
	}

	/**
	 * How many storage chests to order: one per category of surplus that no chest has room for, less
	 * the empty chests, no more than there are free spots, and less the chests the colony already holds.
	 */
	public static int chestsToOrder(int fullCategories, int emptyChests, int freeSpots, int held) {
		return Math.max(0, Math.min(fullCategories - emptyChests, freeSpots) - held);
	}

	/** How many of each item to move out of the core into storage. */
	public static <I> Map<I, Integer> toStore(Map<I, Integer> core, List<StockPlanner.Target<I>> targets, Map<I, Integer> ingredients,
			ToIntFunction<I> minKeep) {
		Map<I, Integer> store = new LinkedHashMap<>();
		core.forEach((item, count) -> {
			int keep = Math.max(minKeep.applyAsInt(item), ingredients.getOrDefault(item, 0));
			for (StockPlanner.Target<I> target : targets) {
				if (target.matches().test(item)) {
					keep = Math.max(keep, target.count());
				}
			}
			if (count > keep) {
				store.put(item, count - keep);
			}
		});
		return store;
	}

	/**
	 * What to move out of a full core when nothing in it is above its working stock: all of one kind,
	 * so its slot frees. Rather a kind open orders do not use, and of those the fewest. Empty if the
	 * core is empty.
	 */
	public static <I> Map<I, Integer> toMakeRoom(Map<I, Integer> core, Map<I, Integer> ingredients) {
		return core.entrySet().stream()
			.min(Comparator.comparing((Map.Entry<I, Integer> entry) -> ingredients.getOrDefault(entry.getKey(), 0) > 0)
				.thenComparing(Map.Entry::getValue))
			.map(entry -> Map.of(entry.getKey(), entry.getValue()))
			.orElse(Map.of());
	}

	/**
	 * How many of each item to bring back from storage: what the core is short of its targets (a tag
	 * target counts every matching item in the core) and of the ingredients open orders need.
	 */
	public static <I> Map<I, Integer> toFetch(Map<I, Integer> core, Map<I, Integer> storage, List<StockPlanner.Target<I>> targets,
			Map<I, Integer> ingredients) {
		Map<I, Integer> left = new HashMap<>(storage);
		Map<I, Integer> fetch = new LinkedHashMap<>();
		ingredients.forEach((item, need) -> take(item, need - core.getOrDefault(item, 0), left, fetch));
		for (StockPlanner.Target<I> target : targets) {
			int missing = target.count() - StockPlanner.have(target, core) - StockPlanner.have(target, fetch);
			for (I item : List.copyOf(left.keySet())) {
				if (missing > 0 && target.matches().test(item)) {
					missing -= take(item, missing, left, fetch);
				}
			}
		}
		return fetch;
	}

	/**
	 * Whether a miner takes its haul up to the core rather than leaving it in the landing chest: when
	 * it carries something {@linkplain #toCore(Object, Set, List, Map) the core wants}.
	 */
	public static <I> boolean toCore(Set<I> carried, Set<I> forOrders, List<StockPlanner.Target<I>> targets, Map<I, Integer> stock) {
		return carried.stream().anyMatch(item -> toCore(item, forOrders, targets, stock));
	}

	/**
	 * Whether the core wants {@code item} brought up now: open orders want it (see
	 * {@link #wantedForOrders}), or the colony is below a stock target of it.
	 */
	public static <I> boolean toCore(I item, Set<I> forOrders, List<StockPlanner.Target<I>> targets, Map<I, Integer> stock) {
		return forOrders.contains(item)
			|| targets.stream().anyMatch(target -> target.matches().test(item) && StockPlanner.have(target, stock) < target.count());
	}

	/**
	 * What the colony wants from mining: what open orders want (see {@link #wantedForOrders}) and
	 * anything it keeps a stock target of.
	 */
	public static <I> Predicate<I> wanted(Set<I> forOrders, List<StockPlanner.Target<I>> targets) {
		return item -> forOrders.contains(item) || targets.stream().anyMatch(target -> target.matches().test(item));
	}

	/**
	 * The items open orders want: the ordered items, what their plans still have to gather or lack
	 * beyond {@code stock}, and whatever could stand in for those in a recipe (cobbled deepslate for
	 * cobblestone). Plans once per order, so call it once per check rather than per item.
	 */
	public static <I> Set<I> wantedForOrders(Map<I, Integer> orders, Map<I, Integer> stock, TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		Set<I> wanted = new HashSet<>(orders.keySet());
		orders.forEach((item, count) -> {
			Map<I, Integer> available = new HashMap<>(stock);
			// An order is for more than the colony holds, as in what the core keeps for it.
			available.remove(item);
			TaskPlanner.Plan<I> plan = TaskPlanner.plan(item, count, available, book, gatherable);
			Set<I> lacking = new HashSet<>(plan.toGather().keySet());
			lacking.addAll(plan.missing().keySet());
			wanted.addAll(lacking);
			for (TaskPlanner.Craft<I> craft : plan.crafts()) {
				craft.recipe().ingredients().keySet().stream()
					.filter(options -> options.stream().anyMatch(lacking::contains))
					.forEach(wanted::addAll);
			}
		});
		return wanted;
	}

	private static <I> int take(I item, int wanted, Map<I, Integer> left, Map<I, Integer> fetch) {
		int taken = Math.min(wanted, left.getOrDefault(item, 0));
		if (taken > 0) {
			left.merge(item, -taken, Integer::sum);
			fetch.merge(item, taken, Integer::sum);
		}
		return Math.max(taken, 0);
	}

	/**
	 * The items {@code crafts} use up from {@code stock}, fuel included. What an earlier craft makes
	 * is used before stock.
	 */
	public static <I> Map<I, Integer> ingredients(List<TaskPlanner.Craft<I>> crafts, Map<I, Integer> stock) {
		Map<I, Integer> left = new HashMap<>(stock);
		Map<I, Integer> made = new HashMap<>();
		Map<I, Integer> used = new LinkedHashMap<>();
		for (TaskPlanner.Craft<I> craft : crafts) {
			for (Map.Entry<Set<I>, Integer> ingredient : craft.recipe().ingredients().entrySet()) {
				int need = ingredient.getValue() * craft.times();
				for (I option : ingredient.getKey()) {
					int fromMade = Math.min(need, made.getOrDefault(option, 0));
					made.merge(option, -fromMade, Integer::sum);
					need -= fromMade;
				}
				for (I option : ingredient.getKey()) {
					need -= take(option, need, left, used);
				}
			}
			craft.fuel().forEach((fuel, count) -> take(fuel, count, left, used));
			made.merge(craft.recipe().output(), craft.recipe().count() * craft.times(), Integer::sum);
		}
		return used;
	}
}
