package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StockkeepingTest {
	private static final Set<String> PICKAXES = Set.of("wooden_pickaxe", "stone_pickaxe");
	private static final List<StockPlanner.Target<String>> TARGETS = List.of(
		new StockPlanner.Target<>(PICKAXES::contains, List.of("stone_pickaxe"), 2),
		new StockPlanner.Target<>("oak_log"::equals, List.of(), 32));
	/** Items keep at least one stack of 64 in the core. */
	private static final int STACK = 64;

	private static Map<String, Integer> toStore(Map<String, Integer> core, Map<String, Integer> ingredients) {
		return Stockkeeping.toStore(core, TARGETS, ingredients, item -> STACK);
	}

	@Test
	void storesWhatIsAboveTheKeepLevel() {
		// Logs keep their target (32), but never less than a stack; dirt keeps a stack.
		assertEquals(Map.of("oak_log", 100 - STACK, "dirt", 10), toStore(Map.of("oak_log", 100, "dirt", STACK + 10, "stick", 3), Map.of()));
	}

	@Test
	void keepsTheTargetWhenItIsAboveAStack() {
		List<StockPlanner.Target<String>> targets = List.of(new StockPlanner.Target<>("cobblestone"::equals, List.of(), 128));
		assertEquals(Map.of("cobblestone", 22), Stockkeeping.toStore(Map.of("cobblestone", 150), targets, Map.of(), item -> STACK));
	}

	@Test
	void keepsIngredientsForOpenOrders() {
		assertTrue(toStore(Map.of("dirt", 100), Map.of("dirt", 100)).isEmpty());
	}

	@Test
	void fetchesWhatTheCoreIsShortOf() {
		// No pickaxe in the core and one in storage: bring it back.
		assertEquals(Map.of("wooden_pickaxe", 1), Stockkeeping.toFetch(Map.of(), Map.of("wooden_pickaxe", 1, "dirt", 500), TARGETS, Map.of()));
		// Up to the target only.
		assertEquals(Map.of("oak_log", 12), Stockkeeping.toFetch(Map.of("oak_log", 20), Map.of("oak_log", 300), TARGETS, Map.of()));
	}

	@Test
	void tagTargetsCountEveryMatchingItemInTheCore() {
		assertTrue(Stockkeeping.toFetch(Map.of("stone_pickaxe", 2), Map.of("wooden_pickaxe", 1), TARGETS, Map.of()).isEmpty());
	}

	@Test
	void fetchesIngredientsForOpenOrders() {
		assertEquals(Map.of("stick", 2), Stockkeeping.toFetch(Map.of("stick", 1), Map.of("stick", 10), List.of(), Map.of("stick", 3)));
	}

	@Test
	void whatIsStoredIsNotFetchedBack() {
		Map<String, Integer> core = Map.of("oak_log", 100, "stone_pickaxe", 3);
		Map<String, Integer> stored = toStore(core, Map.of());
		Map<String, Integer> left = Map.of("oak_log", 100 - stored.getOrDefault("oak_log", 0), "stone_pickaxe", 3);
		assertTrue(Stockkeeping.toFetch(left, stored, TARGETS, Map.of()).isEmpty());
	}

	@Test
	void ingredientsOfACraftingChain() {
		Set<String> planks = Set.of("oak_planks", "birch_planks");
		TaskPlanner.Recipe<String> planksFromLog = new TaskPlanner.Recipe<>("oak_planks", 4, Map.of(Set.of("oak_log"), 1));
		TaskPlanner.Recipe<String> sticks = new TaskPlanner.Recipe<>("stick", 4, Map.of(planks, 2));
		List<TaskPlanner.Craft<String>> crafts = List.of(new TaskPlanner.Craft<>(planksFromLog, 1), new TaskPlanner.Craft<>(sticks, 1));
		// Planks made in the first step are used in the second; the birch planks in stock are not needed.
		assertEquals(Map.of("oak_log", 1), Stockkeeping.ingredients(crafts, Map.of("oak_log", 5, "birch_planks", 8)));
	}
}
