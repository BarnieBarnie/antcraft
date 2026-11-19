package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
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

	private static <I> List<I> craftOrder(TaskPlanner.Plan<I> plan) {
		return plan.crafts().stream().map(c -> c.recipe().output()).toList();
	}

	/**
	 * Many bots plan the same orders from the same stock, such as every hauler asking what the core keeps for them; a
	 * plan made lately for the same item, count, stock, recipes and gatherables is not searched for again.
	 */
	@Test
	void aPlanMadeLatelyFromTheSameStockIsNotSearchedForAgain() {
		int[] lookups = {0};
		TaskPlanner.RecipeBook<String> counting = item -> {
			lookups[0]++;
			return BOOK.recipesFor(item);
		};
		Predicate<String> gatherable = GATHERABLE::contains;
		Map<String, Integer> stock = new HashMap<>(Map.of("oak_log", 2));
		TaskPlanner.Plan<String> first = TaskPlanner.plan("wooden_pickaxe", 1, stock, counting, gatherable);
		int searched = lookups[0];
		assertTrue(searched > 0);

		assertEquals(first, TaskPlanner.plan("wooden_pickaxe", 1, new HashMap<>(Map.of("oak_log", 2)), counting, gatherable));
		assertEquals(searched, lookups[0], "searched again for the same plan");
		// The stock the first plan was made from changes after: the plan is not taken for it.
		stock.put("oak_log", 0);
		TaskPlanner.Plan<String> none = TaskPlanner.plan("wooden_pickaxe", 1, stock, counting, gatherable);
		assertTrue(lookups[0] > searched, "not searched for other stock");
		assertEquals(plan("wooden_pickaxe", 1, Map.of("oak_log", 0)), none);
		int after = lookups[0];
		TaskPlanner.plan("wooden_pickaxe", 2, stock, counting, gatherable);
		assertTrue(lookups[0] > after, "not searched for another count");
	}

	/**
	 * Each step says what it uses (#935): a sign of six oak planks and a stick, from six oak and two birch planks, makes
	 * its sticks of the birch planks, though oak is what there is most of, whichever ingredient the recipe lists first.
	 */
	@Test
	void eachStepSaysWhichItemsItUses() {
		TaskPlanner.RecipeBook<String> book = item -> item.equals("oak_sign")
			? List.of(recipe("oak_sign", 3, Map.of(Set.of("oak_planks"), 6, Set.of("stick"), 1)))
			: BOOK.recipesFor(item);
		TaskPlanner.Plan<String> plan = TaskPlanner.plan("oak_sign", 3, new HashMap<>(Map.of("oak_planks", 6, "birch_planks", 2)), book,
			GATHERABLE::contains);

		assertEquals(List.of("stick", "oak_sign"), craftOrder(plan));
		assertEquals(Map.of("birch_planks", 2), plan.crafts().get(0).uses());
		assertEquals(Map.of("oak_planks", 6, "stick", 1), plan.crafts().get(1).uses());
	}

	/** A step uses an item it makes as well as one it carries: planks from a log beside one carried plank. */
	@Test
	void aStepUsesWhatItTakesAndWhatAnEarlierStepMakes() {
		TaskPlanner.Plan<String> plan = plan("stick", 4, Map.of("oak_planks", 1, "oak_log", 1));

		assertEquals(List.of("oak_planks", "stick"), craftOrder(plan));
		assertEquals(Map.of("oak_log", 1), plan.crafts().get(0).uses());
		assertEquals(Map.of("oak_planks", 2), plan.crafts().get(1).uses());
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
	/** {@link #FURNACE_BOOK} with planks burning as long as logs, ranked with them as {@link Fuels#order} does. */
	private static final TaskPlanner.RecipeBook<String> PLANK_FUEL_BOOK = new TaskPlanner.RecipeBook<>() {
		@Override
		public List<TaskPlanner.Recipe<String>> recipesFor(String item) {
			return FURNACE_BOOK.recipesFor(item);
		}

		@Override
		public List<TaskPlanner.Fuel<String>> fuels() {
			return List.of(
				new TaskPlanner.Fuel<>(Set.of("coal"), 1600),
				new TaskPlanner.Fuel<>(Set.of("charcoal"), 1600),
				new TaskPlanner.Fuel<>(Set.of("oak_log", "birch_log", "oak_planks", "birch_planks"), 300));
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
	void burnsPlanksFromLogsWhenThereIsNoCoal() {
		// A plank burns as long as a log, for one and a half items, and a log makes four (#937).
		TaskPlanner.Plan<String> plan = TaskPlanner.plan("iron_ingot", 2, new HashMap<>(Map.of("raw_iron", 2, "birch_log", 5)), PLANK_FUEL_BOOK, GATHERABLE_ORES::contains);

		assertEquals(Map.of("birch_planks", 2), smeltStep(plan).fuel());
		assertEquals(List.of("birch_planks", "iron_ingot"), craftOrder(plan));
		// One log made into planks: four logs and two planks kept, where burning logs kept three logs.
		assertEquals(1, plan.crafts().getFirst().times());
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
	void oneItemFromALogBurnsAPlankAndKeepsThree() {
		TaskPlanner.Plan<String> plan = TaskPlanner.plan("iron_ingot", 1, new HashMap<>(Map.of("raw_iron", 1, "oak_log", 1)), PLANK_FUEL_BOOK, GATHERABLE_ORES::contains);

		assertEquals(Map.of("oak_planks", 1), smeltStep(plan).fuel());
		// The log makes four planks, so three are kept.
		assertEquals(List.of("oak_planks", "iron_ingot"), craftOrder(plan));
		assertEquals(1, plan.crafts().getFirst().times());
		assertTrue(plan.isReady());
	}

	@Test
	void makesNoCharcoalWithCoalInStock() {
		TaskPlanner.Plan<String> plan = smeltPlan("iron_ingot", 16, Map.of("raw_iron", 16, "coal", 2, "oak_log", 16));

		assertEquals(Map.of("coal", 2), smeltStep(plan).fuel());
		assertTrue(plan.isReady());
	}

	/**
	 * With an oak and a birch log, 4 torches need no gathering: one log makes planks for the stick and the fuel, the other
	 * smelts into charcoal. Planning the charcoal first took one log to smelt and burnt the other, leaving none for the
	 * stick (#931).
	 */
	@Test
	void torchesFromTwoMixedLogsGatherNothing() {
		Map<Set<String>, Integer> torch = new LinkedHashMap<>();
		torch.put(Set.of("coal", "charcoal"), 1);
		torch.put(Set.of("stick"), 1);
		TaskPlanner.RecipeBook<String> book = new TaskPlanner.RecipeBook<>() {
			@Override
			public List<TaskPlanner.Recipe<String>> recipesFor(String item) {
				return item.equals("torch") ? List.of(recipe("torch", 4, torch)) : FURNACE_BOOK.recipesFor(item);
			}

			@Override
			public List<TaskPlanner.Fuel<String>> fuels() {
				return List.of(
					new TaskPlanner.Fuel<>(Set.of("coal"), 1600),
					new TaskPlanner.Fuel<>(Set.of("charcoal"), 1600),
					new TaskPlanner.Fuel<>(Set.of("oak_log", "birch_log", "oak_planks", "birch_planks"), 300));
			}
		};

		TaskPlanner.Plan<String> plan = TaskPlanner.plan("torch", 4, new HashMap<>(Map.of("oak_log", 1, "birch_log", 1)), book, GATHERABLE::contains);

		assertEquals(Map.of(), plan.toGather(), "plan: " + plan.crafts());
		assertTrue(plan.isReady(), "plan: " + plan);
	}

	/**
	 * Only the middle ingredient first works: the first and the last each take a p the middle needs, as the p is what
	 * they have most of. Recipe order and its reverse both leave a p short (#936).
	 */
	@Test
	void threeIngredientsWhereOnlyTheMiddleFirstWorksPlanNoShortage() {
		Map<Set<String>, Integer> ingredients = new LinkedHashMap<>();
		ingredients.put(Set.of("p", "q"), 1);
		ingredients.put(Set.of("p"), 3);
		ingredients.put(Set.of("p", "r"), 1);
		TaskPlanner.RecipeBook<String> book = new TaskPlanner.RecipeBook<>() {
			@Override
			public List<TaskPlanner.Recipe<String>> recipesFor(String item) {
				return item.equals("x") ? List.of(recipe("x", 1, ingredients)) : List.of();
			}

			@Override
			public List<TaskPlanner.Fuel<String>> fuels() {
				return List.of();
			}
		};

		TaskPlanner.Plan<String> plan = TaskPlanner.plan("x", 1, new HashMap<>(Map.of("p", 3, "q", 1, "r", 1)), book, item -> false);

		assertTrue(plan.isReady(), "plan: " + plan);
	}

	@Test
	void charcoalIsNotMadeToFuelMakingCharcoal() {
		TaskPlanner.Plan<String> plan = smeltPlan("charcoal", 16, Map.of("oak_log", 32));

		TaskPlanner.Craft<String> step = smeltStep(plan);
		assertEquals(16, step.times());
		assertEquals(Map.of("oak_log", 11), step.fuel());
		assertTrue(plan.isReady());
	}

	/**
	 * Many fuels, each smelted from ore in stock or crafted from a smelted item, so making one fuel needs fuel itself. The
	 * fuel search used to make fuel to make fuel to make fuel, over every fuel and recipe at each level, and never ended
	 * (#309). Fuel to make fuel is now only burnt or gathered.
	 */
	@Test
	void makingFuelThatNeedsFuelPlansQuickly() {
		int fuels = 12;
		Map<String, List<TaskPlanner.Recipe<String>>> byOutput = new HashMap<>();
		List<TaskPlanner.Fuel<String>> ranks = new ArrayList<>();
		Map<String, Integer> stock = new HashMap<>(Map.of("raw_iron", 64));
		for (int i = 0; i < fuels; i++) {
			String fuel = "fuel_" + i;
			// Better fuels first; each still burns longer than the log at the end.
			ranks.add(new TaskPlanner.Fuel<>(Set.of(fuel), 3200 - 100 * i));
			List<TaskPlanner.Recipe<String>> recipes = new ArrayList<>();
			for (int r = 0; r < 2; r++) {
				String ore = "ore_" + i + "_" + r;
				stock.put(ore, 64);
				if (i % 2 == 0) {
					recipes.add(new TaskPlanner.Recipe<>(fuel, 1, Map.of(Set.of(ore), 1), true));
				} else {
					String smelted = "smelted_" + i + "_" + r;
					byOutput.put(smelted, List.of(new TaskPlanner.Recipe<>(smelted, 1, Map.of(Set.of(ore), 1), true)));
					recipes.add(recipe(fuel, 1, Map.of(Set.of(smelted), 1)));
				}
			}
			byOutput.put(fuel, recipes);
		}
		ranks.add(new TaskPlanner.Fuel<>(Set.of("oak_log"), 300));
		byOutput.put("iron_ingot", List.of(new TaskPlanner.Recipe<>("iron_ingot", 1, Map.of(Set.of("raw_iron"), 1), true)));
		TaskPlanner.RecipeBook<String> book = new TaskPlanner.RecipeBook<>() {
			@Override
			public List<TaskPlanner.Recipe<String>> recipesFor(String item) {
				return byOutput.getOrDefault(item, List.of());
			}

			@Override
			public List<TaskPlanner.Fuel<String>> fuels() {
				return ranks;
			}
		};

		TaskPlanner.Plan<String> plan = assertTimeoutPreemptively(Duration.ofSeconds(5),
			() -> planQuickly("iron_ingot", 64, stock, book, "oak_log"::equals));

		TaskPlanner.Craft<String> ingots = smeltStepFor(plan, "iron_ingot");
		assertEquals(64, ingots.times());
		// The best fuel is made from ore in stock and burnt.
		assertEquals(Map.of("fuel_0", 4), ingots.fuel(), "plan: " + plan);
		assertEquals(Map.of(), plan.missing(), "plan: " + plan);
	}

	/**
	 * Recipe lookups a plan may make. Counting search steps instead of timing them keeps the check
	 * independent of how busy the machine is. Plans take up to about 6,000; without pruning a bed
	 * takes over 30,000, and without the expansion budget the deep and wide book never finishes.
	 */
	private static final int QUICK = 20_000;

	/** Plans while counting recipe lookups, and fails as soon as the search takes more than {@link #QUICK}. */
	private static <I> TaskPlanner.Plan<I> planQuickly(I item, int count, Map<I, Integer> stock, TaskPlanner.RecipeBook<I> book, Predicate<I> gatherable) {
		int[] lookups = {0};
		TaskPlanner.RecipeBook<I> counting = new TaskPlanner.RecipeBook<>() {
			@Override
			public List<TaskPlanner.Recipe<I>> recipesFor(I wanted) {
				if (++lookups[0] > QUICK) {
					// Fails right away, so a search that never ends cannot hang the test.
					fail("the search took more than " + QUICK + " recipe lookups");
				}
				return book.recipesFor(wanted);
			}

			@Override
			public List<TaskPlanner.Fuel<I>> fuels() {
				return book.fuels();
			}
		};
		return TaskPlanner.plan(item, count, new HashMap<>(stock), counting, gatherable);
	}

	@BeforeAll
	static void loadVanillaData() {
		// Bootstraps the registries and loads the vanilla recipes and tags once for all tests.
		VanillaData.recipeBook();
	}

	private static TaskPlanner.Plan<Item> planVanilla(Item item, int count, Map<Item, Integer> stock) {
		TaskPlanner.RecipeBook<Item> book = VanillaData.recipeBook();
		return planQuickly(item, count, stock, book, VanillaData.gatherable());
	}

	@Test
	void plansAPickaxeWithTheRealRecipeBook() {
		TaskPlanner.Plan<Item> plan = planVanilla(Items.WOODEN_PICKAXE, 1, Map.of(Items.OAK_LOG, 2));

		assertEquals(Items.WOODEN_PICKAXE, plan.crafts().getLast().recipe().output());
		assertTrue(plan.isReady(), "plan: " + plan);
	}

	@Test
	void plansABedFromNothingQuickly() {
		TaskPlanner.Plan<Item> plan = planVanilla(Items.BED.red(), 1, Map.of());

		assertEquals(Items.BED.red(), plan.crafts().getLast().recipe().output(), "plan: " + plan);
		assertTrue(!plan.toGather().isEmpty(), "nothing in stock, so something has to be gathered: " + plan);
	}

	@Test
	void plansABedFromGatherableInputsWithEmptyStock() {
		// Farmers shear sheep for white wool; the dye for a red one comes from a flower.
		for (Item bed : List.of(Items.BED.white(), Items.BED.red())) {
			TaskPlanner.Plan<Item> plan = planVanilla(bed, 1, Map.of());

			assertEquals(bed, plan.crafts().getLast().recipe().output(), "plan: " + plan);
			assertEquals(Map.of(), plan.missing(), "plan: " + plan);
			assertEquals(3, plan.toGather().getOrDefault(Items.WOOL.white(), 0), "plan: " + plan);
		}
	}

	@Test
	void plansStringToGatherNotAsMissing() {
		// Bots kill spiders and haulers carry the string home.
		TaskPlanner.Plan<Item> plan = planVanilla(Items.BOW, 1, Map.of(Items.STICK, 3));

		assertEquals(Map.of(), plan.missing(), "plan: " + plan);
		assertEquals(3, plan.toGather().getOrDefault(Items.STRING, 0), "plan: " + plan);
	}

	@Test
	void plansAYellowBedFromFlowersToGather() {
		TaskPlanner.Plan<Item> plan = planVanilla(Items.BED.yellow(), 1, Map.of(Items.WOOL.white(), 3, Items.OAK_PLANKS, 3));

		assertEquals(Map.of(), plan.missing(), "plan: " + plan);
		assertEquals(Map.of(Items.DANDELION, 1), plan.toGather(), "plan: " + plan);
	}

	@Test
	void plansRedDyeFromAFlowerTheSameEveryRun() {
		// The colony grows no beetroot. Poppy, red tulip and rose bush tie; the book lists recipes by id,
		// so the first, the poppy, wins in every run rather than whichever a hash order puts first.
		TaskPlanner.Plan<Item> plan = planVanilla(Items.BED.red(), 1, Map.of(Items.WOOL.white(), 3, Items.OAK_PLANKS, 3));

		assertEquals(Map.of(), plan.missing(), "plan: " + plan);
		assertEquals(Map.of(Items.POPPY, 1), plan.toGather(), "plan: " + plan);
	}

	@Test
	void plansARedBedTheSameEveryRun() {
		// Ingredient options used to be hash sets in a per-run order: one run gathered birch wood, the next an acacia log.
		TaskPlanner.Plan<Item> plan = planVanilla(Items.BED.red(), 1, Map.of());

		assertEquals(Map.of(Items.WOOL.white(), 3, Items.OAK_LOG, 1, Items.POPPY, 1), plan.toGather(), "plan: " + plan);
		assertEquals(List.of(Items.DYE.red(), Items.OAK_PLANKS, Items.BED.white(), Items.BED.red()), craftOrder(plan));
	}

	@Test
	void theRecipeBookListsRecipesById() {
		// The recipe manager hands them out by id; the planner takes the first of recipes that tie.
		List<Item> sources = VanillaData.recipeBook().recipesFor(Items.DYE.red()).stream()
			.map(recipe -> recipe.ingredients().keySet().iterator().next().iterator().next()).toList();

		assertEquals(List.of(Items.BEETROOT, Items.POPPY, Items.ROSE_BUSH, Items.RED_TULIP), sources);
	}

	@Test
	void plansABedFromWoolAndPlanks() {
		TaskPlanner.Plan<Item> plan = planVanilla(Items.BED.red(), 1, Map.of(Items.WOOL.red(), 3, Items.OAK_PLANKS, 3));

		assertEquals(1, plan.crafts().size(), "plan: " + plan);
		assertEquals(Items.BED.red(), plan.crafts().getFirst().recipe().output());
		assertTrue(plan.isReady(), "plan: " + plan);
	}

	/** Fuel for two iron ingots from nothing, with the real fuel ranking, each log costing {@code logCost} to gather and coal {@code coalCost} (#944). */
	private static TaskPlanner.Plan<Item> ingotsFromNoFuel(int logCost, int coalCost) {
		Map<Item, Integer> costs = new HashMap<>(Map.of(Items.COAL, coalCost));
		BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.LOGS).forEach(log -> costs.put(log.value(), logCost));
		return TaskPlanner.plan(Items.IRON_INGOT, 2, List.of(), new HashMap<>(Map.of(Items.RAW_IRON, 2)), VanillaData.recipeBook(), VanillaData.gatherable(), costs);
	}

	@Test
	void withNoFuelAndCoalCheaperToGatherThePlanGathersCoal() {
		TaskPlanner.Plan<Item> plan = ingotsFromNoFuel(3, 1);

		assertEquals(Map.of(Items.COAL, 1), plan.toGather(), "plan: " + plan);
	}

	@Test
	void withNoFuelAndATreeCheaperToGatherThePlanBurnsPlanks() {
		TaskPlanner.Plan<Item> plan = ingotsFromNoFuel(1, 3);

		assertEquals(1, plan.toGather().size(), "plan: " + plan);
		assertTrue(plan.toGather().keySet().iterator().next().builtInRegistryHolder().is(ItemTags.LOGS), "plan: " + plan);
		assertTrue(plan.crafts().getFirst().recipe().output().builtInRegistryHolder().is(ItemTags.PLANKS), "plan: " + plan);
	}

	/**
	 * 64 ingots from carried logs: planks burn 11 logs (43 planks), charcoal 10, 8 smelted into charcoal and 2 into the
	 * planks that smelt them. Charcoal's own smelt burnt logs, 6, so it lost (#944).
	 */
	@Test
	void aBigSmeltFromCarriedLogsBurnsCharcoalWhenThatBurnsFewerLogs() {
		TaskPlanner.Plan<Item> plan = planVanilla(Items.IRON_INGOT, 64, Map.of(Items.RAW_IRON, 64, Items.OAK_LOG, 64));

		TaskPlanner.Craft<Item> ingots = plan.crafts().getLast();
		assertEquals(Map.of(Items.CHARCOAL, 8), ingots.fuel(), "plan: " + plan);
		int logs = plan.crafts().stream().mapToInt(craft -> craft.uses().getOrDefault(Items.OAK_LOG, 0) + craft.fuel().getOrDefault(Items.OAK_LOG, 0)).sum();
		assertEquals(10, logs, "plan: " + plan);
		assertTrue(plan.isReady(), "plan: " + plan);
	}

	/**
	 * A plan from a very large stock, such as a full storage room: every vanilla item no recipe makes, 64 of each (some
	 * 600 kinds), for 16 iron pickaxes, which tries the fuels and their recipes. The search copied the whole stock for
	 * each recipe and fuel it tried and for each need it remembered: some 200 times here, 121,200 entries and about 7 ms
	 * a plan (#322). It now copies only what the plan has changed, under 1,000 entries and about 2 ms.
	 */
	@Test
	void aPlanFromAVeryLargeStockCopiesLittleOfIt() {
		TaskPlanner.RecipeBook<Item> book = VanillaData.recipeBook();
		Map<Item, Integer> stock = new HashMap<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (book.recipesFor(item).isEmpty()) {
				stock.put(item, 64);
			}
		}
		stock.remove(Items.COAL);
		assertTrue(stock.size() > 500, "stock of " + stock.size());

		long copied = TaskPlanner.stockEntriesCopied(Items.IRON_PICKAXE, 16, stock, book, VanillaData.gatherable());

		assertTrue(copied <= 2L * stock.size(), copied + " stock entries copied for a stock of " + stock.size());
		TaskPlanner.Plan<Item> plan = planVanilla(Items.IRON_PICKAXE, 16, stock);
		assertEquals(Items.IRON_PICKAXE, plan.crafts().getLast().recipe().output(), "plan: " + plan);
		assertTrue(plan.isReady(), "plan: " + plan);
	}

	@Test
	void plansABedWithLotsOfStockQuickly() {
		// A colony core full of wool, dye and wood gives every alternative recipe something to use.
		Map<Item, Integer> stock = new HashMap<>();
		Items.WOOL.forEach(wool -> stock.put(wool, 2));
		stock.put(Items.STRING, 7);
		stock.put(Items.POPPY, 1);
		stock.put(Items.OAK_LOG, 5);
		stock.put(Items.BIRCH_PLANKS, 1);

		TaskPlanner.Plan<Item> plan = planVanilla(Items.BED.red(), 1, stock);

		assertEquals(Items.BED.red(), plan.crafts().getLast().recipe().output(), "plan: " + plan);
	}

	/**
	 * A made-up book that is deep and wide: every item has several recipes, each needing a few
	 * ingredients that any of several items on the next level can stand in for.
	 */
	private static TaskPlanner.RecipeBook<String> wideBook(int depth, int width, int recipes) {
		Map<String, List<TaskPlanner.Recipe<String>>> byOutput = new HashMap<>();
		for (int level = 0; level < depth; level++) {
			for (int i = 0; i < width; i++) {
				String item = "item_" + level + "_" + i;
				List<TaskPlanner.Recipe<String>> list = new ArrayList<>();
				for (int r = 0; r < recipes; r++) {
					Map<Set<String>, Integer> ingredients = new LinkedHashMap<>();
					for (int slot = 0; slot < 2; slot++) {
						Set<String> options = new java.util.HashSet<>();
						for (int o = 0; o < 4; o++) {
							options.add("item_" + (level + 1) + "_" + ((i + r + slot * 3 + o) % width));
						}
						ingredients.put(options, 1 + slot);
					}
					list.add(recipe(item, 1 + r % 3, ingredients));
				}
				byOutput.put(item, list);
			}
		}
		return item -> byOutput.getOrDefault(item, List.of());
	}

	@Test
	void deepAndWideRecipeBooksPlanQuickly() {
		TaskPlanner.RecipeBook<String> book = wideBook(8, 8, 4);

		TaskPlanner.Plan<String> plan = planQuickly("item_0_0", 2, Map.of("item_3_1", 5, "item_5_2", 3), book, item -> item.startsWith("item_8_"));

		assertTrue(!plan.crafts().isEmpty() || !plan.missing().isEmpty(), "plan: " + plan);
	}

	@Test
	void recipesSharingDeepIngredientsPlanQuickly() {
		// A diamond lattice: both items on a level are made from one of each item on the next, so
		// every need below the top is shared. With the need memo each item is worked out once, about
		// 30 lookups; without it the search repeats every shared need, 2^depth lookups, while the
		// expansions stay at half the lookups and so under the expansion budget.
		int depth = 16;
		Map<String, List<TaskPlanner.Recipe<String>>> byOutput = new HashMap<>();
		for (int level = 0; level < depth; level++) {
			Map<Set<String>, Integer> next = Map.of(Set.of("item_" + (level + 1) + "_0"), 1, Set.of("item_" + (level + 1) + "_1"), 1);
			for (int i = 0; i < 2; i++) {
				byOutput.put("item_" + level + "_" + i, List.of(recipe("item_" + level + "_" + i, 1, next)));
			}
		}

		TaskPlanner.Plan<String> plan = planQuickly("item_0_0", 1, Map.of(), item -> byOutput.getOrDefault(item, List.of()), item -> item.startsWith("item_" + depth + "_"));

		assertEquals("item_0_0", plan.crafts().getLast().recipe().output());
		assertEquals(Map.of("item_16_0", 1 << (depth - 1), "item_16_1", 1 << (depth - 1)), plan.toGather(), "plan: " + plan);
		assertEquals(Map.of(), plan.missing());
	}
}
