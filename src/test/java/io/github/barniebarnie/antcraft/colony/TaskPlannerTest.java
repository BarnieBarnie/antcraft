package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskPlannerTest {
	private static final Set<String> PLANKS = Set.of("oak_planks", "birch_planks");

	/** A tiny recipe book: logs -> 4 planks, 2 planks -> 4 sticks, 3 planks + 2 sticks -> pickaxe. */
	private static final TaskPlanner.RecipeBook<String> BOOK = item -> switch (item) {
		case "oak_planks" -> List.of(recipe("oak_planks", 4, Map.of(Set.of("oak_log"), 1)));
		case "birch_planks" -> List.of(recipe("birch_planks", 4, Map.of(Set.of("birch_log"), 1)));
		case "stick" -> List.of(recipe("stick", 4, Map.of(PLANKS, 2)));
		case "wooden_pickaxe" -> List.of(recipe("wooden_pickaxe", 1, Map.of(PLANKS, 3, Set.of("stick"), 2)));
		// A cycle, like iron ingots <-> iron blocks.
		case "iron_ingot" -> List.of(recipe("iron_ingot", 9, Map.of(Set.of("iron_block"), 1)));
		case "iron_block" -> List.of(recipe("iron_block", 1, Map.of(Set.of("iron_ingot"), 9)));
		default -> List.of();
	};
	private static final Set<String> GATHERABLE = Set.of("oak_log", "birch_log", "cobblestone");

	private static TaskPlanner.Recipe<String> recipe(String output, int count, Map<Set<String>, Integer> ingredients) {
		return new TaskPlanner.Recipe<>(output, count, ingredients);
	}

	private static TaskPlanner.Plan<String> plan(String item, int count, Map<String, Integer> stock) {
		return TaskPlanner.plan(item, count, new HashMap<>(stock), BOOK, GATHERABLE::contains);
	}

	private static List<String> craftOrder(TaskPlanner.Plan<String> plan) {
		return plan.crafts().stream().map(c -> c.recipe().output()).toList();
	}

	@Test
	void nothingToDoWhenInStock() {
		TaskPlanner.Plan<String> plan = plan("stick", 4, Map.of("stick", 10));

		assertTrue(plan.crafts().isEmpty());
		assertTrue(plan.toGather().isEmpty());
		assertTrue(plan.isReady());
	}

	@Test
	void craftsIngredientsBeforeTheItem() {
		TaskPlanner.Plan<String> plan = plan("stick", 4, Map.of("oak_log", 1));

		assertEquals(List.of("oak_planks", "stick"), craftOrder(plan));
		assertTrue(plan.isReady());
	}

	@Test
	void roundsUpToWholeCrafts() {
		TaskPlanner.Plan<String> plan = plan("stick", 6, Map.of("oak_planks", 10));

		assertEquals(1, plan.crafts().size());
		assertEquals(2, plan.crafts().getFirst().times());
	}

	@Test
	void leftoversFromOneCraftAreReused() {
		// 1 log -> 4 planks covers both the 3 planks and the 2 for sticks? No: 3 + 2 = 5, so 2 logs.
		TaskPlanner.Plan<String> plan = plan("wooden_pickaxe", 1, Map.of("oak_log", 5));

		int logsUsed = plan.crafts().stream().filter(c -> c.recipe().output().equals("oak_planks")).mapToInt(TaskPlanner.Craft::times).sum();
		assertEquals(2, logsUsed);
		assertEquals("wooden_pickaxe", craftOrder(plan).getLast());
	}

	@Test
	void usesWhicheverPlanksAreInStock() {
		TaskPlanner.Plan<String> plan = plan("stick", 4, Map.of("birch_planks", 2));

		assertTrue(plan.crafts().stream().noneMatch(c -> c.recipe().output().endsWith("planks")));
		assertTrue(plan.isReady());
	}

	@Test
	void reportsRawMaterialsToGather() {
		TaskPlanner.Plan<String> plan = plan("wooden_pickaxe", 1, Map.of());

		// Any planks will do, so either kind of log is a valid answer.
		assertEquals(2, plan.toGather().values().stream().mapToInt(Integer::intValue).sum());
		assertTrue(plan.toGather().keySet().stream().allMatch(item -> item.endsWith("_log")));
		assertTrue(plan.missing().isEmpty());
		assertTrue(!plan.isReady());
	}

	@Test
	void reportsItemsNobodyCanMakeAsMissing() {
		TaskPlanner.Plan<String> plan = plan("diamond", 1, Map.of());

		assertEquals(Map.of("diamond", 1), plan.missing());
	}

	@Test
	void recipeCyclesDoNotLoopForever() {
		TaskPlanner.Plan<String> plan = plan("iron_block", 1, Map.of());

		assertEquals(Map.of("iron_ingot", 9), plan.missing());
	}

	@Test
	void stockIsConsumedSoTwoNeedsDoNotShareIt() {
		// 2 planks in stock: the 3 planks for the head and 2 for sticks cannot both come from it.
		TaskPlanner.Plan<String> plan = plan("wooden_pickaxe", 1, Map.of("oak_planks", 2));

		assertTrue(!plan.toGather().isEmpty() || plan.crafts().stream().anyMatch(c -> c.recipe().output().equals("oak_planks")));
	}
}
