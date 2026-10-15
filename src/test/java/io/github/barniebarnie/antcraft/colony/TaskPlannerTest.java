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
		// Storage recipes need some of their ingredient at hand, so with nothing the block itself is missing.
		TaskPlanner.Plan<String> plan = plan("iron_block", 1, Map.of());

		assertEquals(Map.of("iron_block", 1), plan.missing());
	}

	@Test
	void packsIngotsIntoABlockWhenSomeAreInStock() {
		TaskPlanner.Plan<String> plan = plan("iron_block", 1, Map.of("iron_ingot", 9));

		assertEquals(List.of("iron_block"), craftOrder(plan));
		assertTrue(plan.isReady());
	}

	@Test
	void stockIsConsumedSoTwoNeedsDoNotShareIt() {
		// 2 planks in stock: the 3 planks for the head and 2 for sticks cannot both come from it.
		TaskPlanner.Plan<String> plan = plan("wooden_pickaxe", 1, Map.of("oak_planks", 2));

		assertTrue(!plan.toGather().isEmpty() || plan.crafts().stream().anyMatch(c -> c.recipe().output().equals("oak_planks")));
	}

	@Test
	void reportsTheItemItselfWhenNoRecipeCanBeCompleted() {
		// Ingots could come from a block, but there is no block either: ask for ingots, not "1 iron block".
		TaskPlanner.Plan<String> plan = plan("iron_ingot", 6, Map.of());

		assertEquals(Map.of("iron_ingot", 6), plan.missing());
	}
	/** The tiny book plus a furnace: raw iron smelts into ingots, fuel is coal, then charcoal, then logs. */
	private static final TaskPlanner.RecipeBook<String> FURNACE_BOOK = new TaskPlanner.RecipeBook<>() {
		@Override
		public List<TaskPlanner.Recipe<String>> recipesFor(String item) {
			return switch (item) {
				case "iron_ingot" -> List.of(new TaskPlanner.Recipe<>("iron_ingot", 1, Map.of(Set.of("raw_iron"), 1), true));
				case "iron_pickaxe" -> List.of(recipe("iron_pickaxe", 1, Map.of(Set.of("iron_ingot"), 3, Set.of("stick"), 2)));
				case "charcoal" -> List.of(new TaskPlanner.Recipe<>("charcoal", 1, Map.of(Set.of("oak_log", "birch_log"), 1), true));
				default -> BOOK.recipesFor(item);
			};
		}

		@Override
		public List<TaskPlanner.Fuel<String>> fuels() {
			return List.of(
				new TaskPlanner.Fuel<>(Set.of("coal"), 1600),
				new TaskPlanner.Fuel<>(Set.of("charcoal"), 1600),
				new TaskPlanner.Fuel<>(Set.of("oak_log", "birch_log"), 300));
		}
	};
	private static final Set<String> GATHERABLE_ORES = Set.of("oak_log", "birch_log", "cobblestone", "raw_iron", "coal");

	private static TaskPlanner.Plan<String> smeltPlan(String item, int count, Map<String, Integer> stock) {
		return TaskPlanner.plan(item, count, new HashMap<>(stock), FURNACE_BOOK, GATHERABLE_ORES::contains);
	}

	private static TaskPlanner.Craft<String> smeltStep(TaskPlanner.Plan<String> plan) {
		List<TaskPlanner.Craft<String>> steps = plan.crafts().stream().filter(c -> c.recipe().smelting()).toList();
		assertEquals(1, steps.size(), "smelting steps: " + plan.crafts());
		return steps.getFirst();
	}

	@Test
	void smeltsRawIronWithCoalFromStock() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 3, Map.of("raw_iron", 3, "coal", 4));

		TaskPlanner.Craft<String> step = smeltStep(plan);
		assertEquals("iron_ingot", step.recipe().output());
		assertEquals(3, step.times());
		// One coal smelts 8 items.
		assertEquals(Map.of("coal", 1), step.fuel());
		assertTrue(plan.isReady());
	}

	@Test
	void fuelRoundsUpPerEightItems() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 9, Map.of("raw_iron", 9, "coal", 5));

		assertEquals(Map.of("coal", 2), smeltStep(plan).fuel());
		assertTrue(plan.isReady());
	}

	@Test
	void plansAnIronPickaxeFromRawIronAndCoal() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_pickaxe", 1, Map.of("raw_iron", 3, "coal", 1, "oak_planks", 2));

		TaskPlanner.Craft<String> step = smeltStep(plan);
		assertEquals(3, step.times());
		assertEquals(Map.of("coal", 1), step.fuel());
		assertEquals("iron_pickaxe", craftOrder(plan).getLast());
		assertTrue(plan.isReady());
	}

	@Test
	void withoutFuelThePlanGathersCoal() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 2, Map.of("raw_iron", 2));

		assertEquals(Map.of("coal", 1), plan.toGather());
		assertTrue(!plan.isReady());
	}

	@Test
	void prefersCoalOverLogs() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 2, Map.of("raw_iron", 2, "coal", 1, "oak_log", 5));

		assertEquals(Map.of("coal", 1), smeltStep(plan).fuel());
	}

	@Test
	void burnsLogsWhenThereIsNoCoal() {
		// A log burns for one and a half items.
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 2, Map.of("raw_iron", 2, "birch_log", 5));

		assertEquals(Map.of("birch_log", 2), smeltStep(plan).fuel());
		assertTrue(plan.isReady());
	}

	@Test
	void fuelInStockIsSharedBetweenSmeltingSteps() {
		// 8 ingots from stock use the only coal; the 9th needs fresh coal.
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 9, Map.of("raw_iron", 9, "coal", 1));

		assertEquals(Map.of("coal", 1), plan.toGather());
	}

	private static TaskPlanner.Craft<String> smeltStepFor(TaskPlanner.Plan<String> plan, String output) {
		List<TaskPlanner.Craft<String>> steps = plan.crafts().stream().filter(c -> c.recipe().smelting() && c.recipe().output().equals(output)).toList();
		assertEquals(1, steps.size(), "steps making " + output + ": " + plan.crafts());
		return steps.getFirst();
	}

	/** Logs a plan uses up, as ingredients and as fuel. */
	private static int logsUsed(TaskPlanner.Plan<String> plan) {
		int logs = 0;
		for (TaskPlanner.Craft<String> craft : plan.crafts()) {
			if (craft.recipe().output().equals("charcoal")) {
				logs += craft.times();
			}
			logs += craft.fuel().getOrDefault("oak_log", 0) + craft.fuel().getOrDefault("birch_log", 0);
		}
		return logs;
	}

	@Test
	void makesCharcoalFromLogsWhenThatSavesLogs() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 16, Map.of("raw_iron", 16, "oak_log", 16));

		TaskPlanner.Craft<String> charcoal = smeltStepFor(plan, "charcoal");
		TaskPlanner.Craft<String> ingots = smeltStepFor(plan, "iron_ingot");
		assertTrue(plan.crafts().indexOf(charcoal) < plan.crafts().indexOf(ingots), "charcoal first: " + plan.crafts());
		assertEquals(Map.of("charcoal", 2), ingots.fuel());
		// Burning logs directly would take 11 (16 items, a log burns for one and a half).
		assertTrue(logsUsed(plan) < 11, "logs used: " + logsUsed(plan));
		assertEquals(4, logsUsed(plan));
		assertTrue(plan.isReady());
	}

	@Test
	void burnsALogDirectlyForOneItem() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 1, Map.of("raw_iron", 1, "oak_log", 5));

		assertEquals(Map.of("oak_log", 1), smeltStep(plan).fuel());
		assertTrue(plan.isReady());
	}

	@Test
	void makesNoCharcoalWithCoalInStock() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 16, Map.of("raw_iron", 16, "coal", 2, "oak_log", 16));

		assertEquals(Map.of("coal", 2), smeltStep(plan).fuel());
		assertTrue(plan.isReady());
	}

	@Test
	void charcoalIsNotMadeToFuelMakingCharcoal() {
		TaskPlanner.Plan<String> plan = smeltPlan("charcoal", 16, Map.of("oak_log", 32));

		TaskPlanner.Craft<String> step = smeltStep(plan);
		assertEquals(16, step.times());
		assertEquals(Map.of("oak_log", 11), step.fuel());
		assertTrue(plan.isReady());
	}
}
