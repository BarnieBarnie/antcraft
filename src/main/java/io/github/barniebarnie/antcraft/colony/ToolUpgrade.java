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
		return toOrder(tiers, current, stock, orders, book, gatherable, null);
	}

	/** Like {@link #toOrder(List, Object, Map, Map, TaskPlanner.RecipeBook, Predicate)}, but no tier above {@code ceiling}, unless it is null. */
	public static <I> Optional<I> toOrder(List<I> tiers, @Nullable I current, Map<I, Integer> stock, Map<I, Integer> orders,
			TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable, @Nullable I ceiling) {
		int rank = rank(tiers, current);
		List<I> better = tiers.subList(rank + 1, tiers.size());
		if (better.stream().anyMatch(tier -> stock.getOrDefault(tier, 0) > 0 || orders.getOrDefault(tier, 0) > 0)) {
			return Optional.empty();
		}
		int top = ceiling == null ? tiers.size() - 1 : rank(tiers, ceiling);
		for (I tier : better.reversed()) {
			if (rank(tiers, tier) <= top && makeableNow(tier, stock, book, gatherable)) {
				return Optional.of(tier);
			}
		}
		return current == null && !tiers.isEmpty() ? Optional.of(tiers.getFirst()) : Optional.empty();
	}

	/**
	 * {@code stock} less what bots wait for: for each tier in {@code waiting}, which another bot's job declares and it
	 * lacks, one of the lowest tier in stock that is good enough, if any.
	 */
	public static <I> Map<I, Integer> unreserved(List<I> tiers, Map<I, Integer> stock, List<I> waiting) {
		Map<I, Integer> free = new HashMap<>(stock);
		for (I need : waiting) {
			tiers.subList(rank(tiers, need), tiers.size()).stream().filter(tier -> free.getOrDefault(tier, 0) > 0).findFirst()
				.ifPresent(tier -> free.computeIfPresent(tier, (item, count) -> count > 1 ? count - 1 : null));
		}
		return free;
	}

	/**
	 * The tier a bot with {@code current}, whose own job needs {@code need} (null for none), takes from {@code stock}: the
	 * best that beats {@code current}, leaving what {@code waiting} (other bots' claimed jobs, see {@link #unreserved}) and
	 * {@code open} (the tiers unclaimed jobs declare) need. When nothing else left gets it to {@code need}, it takes the
	 * lowest tier that does after all, open jobs' tools included, or else the best there is.
	 */
	public static <I> Optional<I> toTake(List<I> tiers, @Nullable I current, Map<I, Integer> stock, List<I> waiting, List<I> open,
			@Nullable I need) {
		Map<I, Integer> free = unreserved(tiers, stock, waiting);
		Optional<I> spare = bestInStock(tiers, current, unreserved(tiers, free, open));
		int needed = rank(tiers, need);
		if (rank(tiers, current) >= needed || spare.filter(tier -> rank(tiers, tier) >= needed).isPresent()) {
			return spare;
		}
		// Open jobs, such as the bot's own next branches, may hold every tool: the lowest that does, so the rest stay.
		return tiers.subList(needed, tiers.size()).stream().filter(tier -> free.getOrDefault(tier, 0) > 0).findFirst()
			.or(() -> bestInStock(tiers, current, free));
	}

	static <I> boolean makeableNow(I item, Map<I, Integer> stock, TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		Map<I, Integer> available = new HashMap<>(stock);
		available.remove(item);
		TaskPlanner.Plan<I> plan = TaskPlanner.plan(item, 1, available, book, gatherable);
		return plan.isReady() && !plan.crafts().isEmpty();
	}
}
