package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StockPlannerTest {
	private static final Set<String> PLANKS = Set.of("oak_planks", "birch_planks");
	private static final Set<String> PICKAXES = Set.of("wooden_pickaxe", "stone_pickaxe");

	/** logs -> 4 planks, 2 planks -> 4 sticks, pickaxes from planks or cobblestone and sticks. */
	private static final TaskPlanner.RecipeBook<String> BOOK = item -> switch (item) {
		case "oak_planks" -> List.of(recipe("oak_planks", 4, Map.of(Set.of("oak_log"), 1)));
		case "birch_planks" -> List.of(recipe("birch_planks", 4, Map.of(Set.of("birch_log"), 1)));
		case "stick" -> List.of(recipe("stick", 4, Map.of(PLANKS, 2)));
		case "wooden_pickaxe" -> List.of(recipe("wooden_pickaxe", 1, Map.of(PLANKS, 3, Set.of("stick"), 2)));
		case "stone_pickaxe" -> List.of(recipe("stone_pickaxe", 1, Map.of(Set.of("cobblestone"), 3, Set.of("stick"), 2)));
		case "iron_helmet" -> List.of(recipe("iron_helmet", 1, Map.of(Set.of("iron_ingot"), 5)));
		default -> List.of();
	};
	private static final Set<String> GATHERABLE = Set.of("oak_log", "birch_log", "cobblestone");

	private static TaskPlanner.Recipe<String> recipe(String output, int count, Map<Set<String>, Integer> ingredients) {
		return new TaskPlanner.Recipe<>(output, count, ingredients);
	}

	private static StockPlanner.Target<String> item(String item, int count) {
		return new StockPlanner.Target<>(item::equals, List.of(item), count);
	}

	private static Map<String, Integer> orders(List<StockPlanner.Target<String>> targets, Map<String, Integer> stock, Map<String, Integer> orders) {
		return StockPlanner.ordersToAdd(targets, stock, orders, BOOK, GATHERABLE::contains);
	}

	@Test
	void nothingToOrderWhenStocked() {
		assertTrue(orders(List.of(item("stick", 4)), Map.of("stick", 6), Map.of()).isEmpty());
	}

	@Test
	void ordersTheShortfall() {
		assertEquals(Map.of("stick", 3), orders(List.of(item("stick", 4)), Map.of("stick", 1, "oak_planks", 2), Map.of()));
	}

	@Test
	void pendingOrdersCountTowardTheTarget() {
		assertEquals(Map.of("stick", 1), orders(List.of(item("stick", 4)), Map.of("oak_planks", 2), Map.of("stick", 3)));
		assertTrue(orders(List.of(item("stick", 4)), Map.of("oak_planks", 2), Map.of("stick", 4)).isEmpty());
	}

	@Test
	void tagTargetCountsAnyMatchingItem() {
		StockPlanner.Target<String> anyPickaxe = new StockPlanner.Target<>(PICKAXES::contains, List.of("stone_pickaxe", "wooden_pickaxe"), 2);
		assertTrue(orders(List.of(anyPickaxe), Map.of("wooden_pickaxe", 1, "stone_pickaxe", 1), Map.of()).isEmpty());
		// An order for another pickaxe counts too.
		assertTrue(orders(List.of(anyPickaxe), Map.of("wooden_pickaxe", 1), Map.of("stone_pickaxe", 1)).isEmpty());
	}

	@Test
	void ordersTheFirstOptionThatCanBeMadeNow() {
		StockPlanner.Target<String> anyPickaxe = new StockPlanner.Target<>(PICKAXES::contains, List.of("stone_pickaxe", "wooden_pickaxe"), 1);
		assertEquals(Map.of("stone_pickaxe", 1), orders(List.of(anyPickaxe), Map.of("cobblestone", 3, "stick", 2), Map.of()));
		// No cobblestone: the wooden one can be made right away.
		assertEquals(Map.of("wooden_pickaxe", 1), orders(List.of(anyPickaxe), Map.of("oak_planks", 3, "stick", 2), Map.of()));
	}

	@Test
	void nothingIsOrderedUntilMaterialsAreIn() {
		// An order that waits for gathering would block the target: with only oak around,
		// pending birch planks would keep oak planks from being ordered.
		StockPlanner.Target<String> anyPlanks = new StockPlanner.Target<>(PLANKS::contains, List.of("birch_planks", "oak_planks"), 4);
		assertTrue(orders(List.of(anyPlanks), Map.of(), Map.of()).isEmpty());
		assertEquals(Map.of("oak_planks", 4), orders(List.of(anyPlanks), Map.of("oak_log", 1), Map.of()));
	}

	@Test
	void ordersAsManyAsCanBeMadeNow() {
		// 2 logs make 8 planks; the rest is ordered once more logs come in.
		assertEquals(Map.of("oak_planks", 8), orders(List.of(item("oak_planks", 32)), Map.of("oak_log", 2), Map.of()));
	}

	@Test
	void gatherableTargetsAreNotOrdered() {
		// Logs come from lumberjacks, not the crafter.
		assertTrue(orders(List.of(item("oak_log", 32)), Map.of(), Map.of()).isEmpty());
	}

	@Test
	void targetsNothingCanProduceAreNotOrdered() {
		// No way to get iron ingots yet: an order would only sit there.
		assertTrue(orders(List.of(item("iron_helmet", 1)), Map.of(), Map.of()).isEmpty());
	}

	@Test
	void itemsMovedToStorageStillCount() {
		// The hauler moved the sticks from the core into a storage chest.
		Map<String, Integer> core = Map.of("oak_planks", 2);
		Map<String, Integer> storage = Map.of("stick", 6);
		assertEquals(Map.of("stick", 4), orders(List.of(item("stick", 4)), core, Map.of()));
		assertTrue(orders(List.of(item("stick", 4)), StockPlanner.total(List.of(core, storage)), Map.of()).isEmpty());
	}

	@Test
	void totalAddsUpStocks() {
		assertEquals(Map.of("stick", 7, "oak_log", 1), StockPlanner.total(List.of(Map.of("stick", 6), Map.of("stick", 1, "oak_log", 1))));
	}

	@Test
	void reportsHowManyAreStocked() {
		StockPlanner.Target<String> anyPickaxe = new StockPlanner.Target<>(PICKAXES::contains, List.of("stone_pickaxe"), 2);
		assertEquals(3, StockPlanner.have(anyPickaxe, Map.of("wooden_pickaxe", 1, "stone_pickaxe", 2, "stick", 5)));
	}
}
