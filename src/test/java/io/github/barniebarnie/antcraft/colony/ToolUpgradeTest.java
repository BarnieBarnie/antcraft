package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolUpgradeTest {
	private static final List<String> TIERS = List.of("wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe");
	private static final Set<String> PLANKS = Set.of("oak_planks");

	/** Pickaxes from planks, cobblestone, iron ingots or diamonds, with sticks. Nothing makes iron ingots yet. */
	private static final TaskPlanner.RecipeBook<String> BOOK = item -> switch (item) {
		case "oak_planks" -> List.of(recipe("oak_planks", 4, Map.of(Set.of("oak_log"), 1)));
		case "stick" -> List.of(recipe("stick", 4, Map.of(PLANKS, 2)));
		case "wooden_pickaxe" -> List.of(recipe("wooden_pickaxe", 1, Map.of(PLANKS, 3, Set.of("stick"), 2)));
		case "stone_pickaxe" -> List.of(recipe("stone_pickaxe", 1, Map.of(Set.of("cobblestone"), 3, Set.of("stick"), 2)));
		case "iron_pickaxe" -> List.of(recipe("iron_pickaxe", 1, Map.of(Set.of("iron_ingot"), 3, Set.of("stick"), 2)));
		case "diamond_pickaxe" -> List.of(recipe("diamond_pickaxe", 1, Map.of(Set.of("diamond"), 3, Set.of("stick"), 2)));
		default -> List.of();
	};
	private static final Set<String> GATHERABLE = Set.of("oak_log", "cobblestone", "diamond");

	private static TaskPlanner.Recipe<String> recipe(String output, int count, Map<Set<String>, Integer> ingredients) {
		return new TaskPlanner.Recipe<>(output, count, ingredients);
	}

	private static Optional<String> order(String current, Map<String, Integer> stock, Map<String, Integer> orders) {
		return ToolUpgrade.toOrder(TIERS, current, stock, orders, BOOK, GATHERABLE::contains);
	}

	@Test
	void ordersTheBestTierThatCanBeMadeFromStock() {
		assertEquals(Optional.of("stone_pickaxe"), order("wooden_pickaxe", Map.of("cobblestone", 3, "stick", 2), Map.of()));
		assertEquals(Optional.of("diamond_pickaxe"),
			order("wooden_pickaxe", Map.of("cobblestone", 3, "diamond", 3, "stick", 2), Map.of()));
		// Materials that are still to be crafted from stock count too.
		assertEquals(Optional.of("stone_pickaxe"), order("wooden_pickaxe", Map.of("cobblestone", 3, "oak_planks", 2), Map.of()));
	}

	@Test
	void ironIsNotReachableWithoutIngots() {
		assertEquals(Optional.empty(), order("stone_pickaxe", Map.of("cobblestone", 30, "stick", 20), Map.of()));
		assertEquals(Optional.of("iron_pickaxe"), order("stone_pickaxe", Map.of("iron_ingot", 3, "stick", 2), Map.of()));
	}

	@Test
	void neverOrdersADowngradeOrTheSameTier() {
		assertEquals(Optional.empty(), order("stone_pickaxe", Map.of("oak_planks", 10, "stick", 10), Map.of()));
		assertEquals(Optional.empty(), order("stone_pickaxe", Map.of("cobblestone", 3, "stick", 2), Map.of()));
	}

	@Test
	void theBestTierOrdersNothing() {
		assertEquals(Optional.empty(), order("diamond_pickaxe", Map.of("diamond", 30, "stick", 20), Map.of()));
	}

	@Test
	void ordersNothingWhileABetterTierIsOrdered() {
		assertEquals(Optional.empty(), order("wooden_pickaxe", Map.of("cobblestone", 3, "stick", 2), Map.of("stone_pickaxe", 1)));
		// Not even a better tier than the one on order: one upgrade at a time.
		assertEquals(Optional.empty(), order("wooden_pickaxe", Map.of("diamond", 3, "stick", 2), Map.of("stone_pickaxe", 1)));
		// An order for a tier that would not be an upgrade does not block one.
		assertEquals(Optional.of("stone_pickaxe"), order("wooden_pickaxe", Map.of("cobblestone", 3, "stick", 2), Map.of("wooden_pickaxe", 1)));
	}

	@Test
	void ordersNothingWhileABetterTierIsInStock() {
		// The miner fetches that one instead.
		assertEquals(Optional.empty(), order("wooden_pickaxe", Map.of("stone_pickaxe", 1, "cobblestone", 3, "stick", 2), Map.of()));
	}

	@Test
	void withoutAPickaxeOrdersTheBestMakeableOrElseTheLowestTier() {
		assertEquals(Optional.of("stone_pickaxe"), order(null, Map.of("cobblestone", 3, "stick", 2), Map.of()));
		// Nothing can be made yet: the lowest tier, whose materials the colony gathers first.
		assertEquals(Optional.of("wooden_pickaxe"), order(null, Map.of(), Map.of()));
		assertEquals(Optional.empty(), order(null, Map.of(), Map.of("wooden_pickaxe", 1)));
	}

	@Test
	void bestInStockIsOnlyAnUpgrade() {
		Map<String, Integer> stock = Map.of("wooden_pickaxe", 2, "stone_pickaxe", 1);
		assertEquals(Optional.of("stone_pickaxe"), ToolUpgrade.bestInStock(TIERS, "wooden_pickaxe", stock));
		assertEquals(Optional.of("stone_pickaxe"), ToolUpgrade.bestInStock(TIERS, null, stock));
		assertEquals(Optional.empty(), ToolUpgrade.bestInStock(TIERS, "stone_pickaxe", stock));
		assertEquals(Optional.empty(), ToolUpgrade.bestInStock(TIERS, "iron_pickaxe", stock));
	}

	@Test
	void ordersNoTierAboveTheBestOneJobsDeclare() {
		Map<String, Integer> stock = Map.of("cobblestone", 3, "diamond", 3, "stick", 2);
		// Only wooden pickaxes wanted: a bot with one orders nothing better.
		assertEquals(Optional.empty(), ToolUpgrade.toOrder(TIERS, "wooden_pickaxe", stock, Map.of(), BOOK, GATHERABLE::contains, "wooden_pickaxe"));
		// Up to stone: stone, not diamond.
		assertEquals(Optional.of("stone_pickaxe"),
			ToolUpgrade.toOrder(TIERS, "wooden_pickaxe", stock, Map.of(), BOOK, GATHERABLE::contains, "stone_pickaxe"));
		// No job declares a tier: the best the colony can make, as before.
		assertEquals(Optional.of("diamond_pickaxe"), ToolUpgrade.toOrder(TIERS, "wooden_pickaxe", stock, Map.of(), BOOK, GATHERABLE::contains, null));
		// A bot without one still gets the lowest tier.
		assertEquals(Optional.of("wooden_pickaxe"), ToolUpgrade.toOrder(TIERS, null, Map.of(), Map.of(), BOOK, GATHERABLE::contains, "wooden_pickaxe"));
	}

	@Test
	void aToolAnotherJobWaitsForIsNotFreeToTake() {
		Map<String, Integer> stock = Map.of("stone_pickaxe", 1, "iron_pickaxe", 1);
		// One waits for a stone pickaxe: the lowest good enough one is kept for it.
		assertEquals(Map.of("iron_pickaxe", 1), ToolUpgrade.unreserved(TIERS, stock, List.of("stone_pickaxe")));
		assertEquals(Map.of("stone_pickaxe", 1), ToolUpgrade.unreserved(TIERS, stock, List.of("iron_pickaxe")));
		assertEquals(Map.of(), ToolUpgrade.unreserved(TIERS, stock, List.of("stone_pickaxe", "wooden_pickaxe")));
		// None good enough in stock: nothing is kept.
		assertEquals(stock, ToolUpgrade.unreserved(TIERS, stock, List.of("diamond_pickaxe")));
	}

	@Test
	void anOpenJobsToolIsLeftWhenAnotherFitsTheTaker() {
		Map<String, Integer> stock = Map.of("stone_pickaxe", 1, "iron_pickaxe", 1);
		List<String> openIron = List.of("iron_pickaxe");
		// A bot without one whose job needs stone takes the stone one, leaving the iron one an open job declares.
		assertEquals(Optional.of("stone_pickaxe"), ToolUpgrade.toTake(TIERS, null, stock, List.of(), openIron, "stone_pickaxe"));
		// With stone, it does not swap up to the iron one either.
		assertEquals(Optional.empty(), ToolUpgrade.toTake(TIERS, "stone_pickaxe", Map.of("iron_pickaxe", 1), List.of(), openIron, null));
		// Open jobs, such as its own next branches, hold every tool: the lowest that does its job.
		assertEquals(Optional.of("stone_pickaxe"),
			ToolUpgrade.toTake(TIERS, null, stock, List.of(), List.of("iron_pickaxe", "stone_pickaxe"), "stone_pickaxe"));
		// Nothing else fits its job: it takes the open job's tool after all.
		assertEquals(Optional.of("iron_pickaxe"), ToolUpgrade.toTake(TIERS, null, Map.of("iron_pickaxe", 1), List.of(), openIron, "stone_pickaxe"));
		assertEquals(Optional.of("iron_pickaxe"), ToolUpgrade.toTake(TIERS, "stone_pickaxe", stock, List.of(), openIron, "iron_pickaxe"));
		// A claimed job's tool is never taken, as before.
		assertEquals(Optional.empty(), ToolUpgrade.toTake(TIERS, null, Map.of("iron_pickaxe", 1), openIron, List.of(), "stone_pickaxe"));
		// Without jobs, the best in stock.
		assertEquals(Optional.of("iron_pickaxe"), ToolUpgrade.toTake(TIERS, null, stock, List.of(), List.of(), "stone_pickaxe"));
	}

	@Test
	void unlistedToolsRankLowest() {
		// A golden pickaxe is not a tier: any listed tool replaces it, but it is still a tool.
		assertEquals(0, ToolUpgrade.rank(TIERS, "golden_pickaxe"));
		assertEquals(-1, ToolUpgrade.rank(TIERS, null));
		assertEquals(2, ToolUpgrade.rank(TIERS, "iron_pickaxe"));
		assertEquals(Optional.of("stone_pickaxe"), ToolUpgrade.bestInStock(TIERS, "golden_pickaxe", Map.of("stone_pickaxe", 1)));
	}
}
