package io.github.barniebarnie.antcraft.colony;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Which tool tier a bot should fetch or order: wooden, then stone, and so on. Generic over
 * the item type, like {@link TaskPlanner}, so it can be tested without a game. Tiers the
 * colony cannot make yet (iron before smelting) are skipped because their plan is not ready.
 */
public final class ToolUpgrade {
	private ToolUpgrade() {
	}

	/**
	 * Where {@code tool} sits in {@code tiers} (worst first): -1 for no tool, and 0 for a
	 * tool that is not a listed tier, so any listed tier above the lowest replaces it.
	 */
	public static <I> int rank(List<I> tiers, @Nullable I tool) {
		if (tool == null) {
			return -1;
		}
		return Math.max(tiers.indexOf(tool), 0);
	}

	/** The best tier in {@code stock} that beats {@code current}. */
	public static <I> Optional<I> bestInStock(List<I> tiers, @Nullable I current, Map<I, Integer> stock) {
		for (int i = tiers.size() - 1; i > rank(tiers, current); i--) {
			if (stock.getOrDefault(tiers.get(i), 0) > 0) {
				return Optional.of(tiers.get(i));
			}
		}
		return Optional.empty();
	}

	/**
	 * The tier to order for a bot holding {@code current}: the best tier above it the crafter
	 * can make from stock right now. Nothing is ordered while a better tier is in stock (fetch
	 * that instead) or already on order, so a colony has one upgrade on its way at a time.
	 * A bot without a tool orders the lowest tier when nothing can be made yet, so the colony
	 * gathers its materials.
	 */
	public static <I> Optional<I> toOrder(List<I> tiers, @Nullable I current, Map<I, Integer> stock, Map<I, Integer> orders,
			TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		int rank = rank(tiers, current);
		List<I> better = tiers.subList(rank + 1, tiers.size());
		if (better.stream().anyMatch(tier -> stock.getOrDefault(tier, 0) > 0 || orders.getOrDefault(tier, 0) > 0)) {
			return Optional.empty();
		}
		for (I tier : better.reversed()) {
			if (makeableNow(tier, stock, book, gatherable)) {
				return Optional.of(tier);
			}
		}
		return current == null && !tiers.isEmpty() ? Optional.of(tiers.getFirst()) : Optional.empty();
	}

	private static <I> boolean makeableNow(I item, Map<I, Integer> stock, TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		Map<I, Integer> available = new HashMap<>(stock);
		available.remove(item);
		TaskPlanner.Plan<I> plan = TaskPlanner.plan(item, 1, available, book, gatherable);
		return plan.isReady() && !plan.crafts().isEmpty();
	}
}
