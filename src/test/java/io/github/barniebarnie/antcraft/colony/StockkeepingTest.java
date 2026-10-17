package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StockkeepingTest {
	private static final Set<String> PICKAXES = Set.of("wooden_pickaxe", "stone_pickaxe");
	private static final List<StockPlanner.Target<String>> TARGETS = List.of(
		new StockPlanner.Target<>(PICKAXES::contains, List.of("stone_pickaxe"), 2),
		new StockPlanner.Target<>("oak_log"::equals, List.of(), 32));
	/** Items keep at least one stack of 64 in the core. */
	private static final int STACK = 64;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

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

	@Test
	void minerBringsItsHaulToTheCoreOnlyWhenTheColonyWantsSomethingInIt() {
		Map<String, Integer> stock = Map.of("oak_log", 40, "stone_pickaxe", 1);
		// Nothing ordered, and the targets of what it carries are met: the landing chest will do.
		assertFalse(Stockkeeping.toCore(Set.of("cobblestone", "oak_log"), Set.of(), TARGETS, stock));
		// An open order for something it carries.
		assertTrue(Stockkeeping.toCore(Set.of("cobblestone", "raw_iron"), Set.of("raw_iron"), TARGETS, stock));
		// A stock target the colony is below.
		assertTrue(Stockkeeping.toCore(Set.of("cobblestone", "wooden_pickaxe"), Set.of(), TARGETS, stock));
		assertTrue(Stockkeeping.toCore(Set.of("oak_log"), Set.of(), TARGETS, Map.of("oak_log", 31)));
	}

	@Test
	void anOpenOrderWantsWhatItsRecipesStillLackAndWhatCouldStandInForIt() {
		Set<String> stone = Set.of("cobblestone", "cobbled_deepslate");
		TaskPlanner.RecipeBook<String> book = new TaskPlanner.RecipeBook<>() {
			@Override
			public List<TaskPlanner.Recipe<String>> recipesFor(String item) {
				return item.equals("stone_pickaxe") ? List.of(new TaskPlanner.Recipe<>("stone_pickaxe", 1, Map.of(stone, 3, Set.of("stick"), 2))) : List.of();
			}
		};
		Set<String> gatherable = Set.of("cobblestone", "cobbled_deepslate", "stick");
		// The order itself, and every option for an ingredient it has to gather.
		assertEquals(Set.of("stone_pickaxe", "cobblestone", "cobbled_deepslate", "stick"),
			Stockkeeping.wantedForOrders(Map.of("stone_pickaxe", 1), Map.of(), book, gatherable::contains));
		// What is in stock already is not wanted.
		assertEquals(Set.of("stone_pickaxe", "stick"),
			Stockkeeping.wantedForOrders(Map.of("stone_pickaxe", 1), Map.of("cobblestone", 3), book, gatherable::contains));
		// A stocked pickaxe does not make the order for another one ready.
		assertEquals(Set.of("stone_pickaxe", "cobblestone", "cobbled_deepslate", "stick"),
			Stockkeeping.wantedForOrders(Map.of("stone_pickaxe", 1), Map.of("stone_pickaxe", 1), book, gatherable::contains));
	}

	@Test
	void anOpenStonePickaxeOrderSendsAMinerWithCobblestoneToTheCore() {
		Map<Item, Integer> stock = Map.of(Items.STICK, 8);
		Set<Item> forOrders = Stockkeeping.wantedForOrders(Map.of(Items.STONE_PICKAXE, 1), stock, VanillaData.recipeBook(), VanillaData.gatherable());
		assertTrue(Stockkeeping.toCore(Set.of(Items.DIRT, Items.COBBLESTONE), forOrders, List.of(), stock));
		assertTrue(Stockkeeping.toCore(Set.of(Items.COBBLED_DEEPSLATE), forOrders, List.of(), stock));
		assertFalse(Stockkeeping.toCore(Set.of(Items.DIRT, Items.GRAVEL), forOrders, List.of(), stock));
		// Without the order, cobblestone can stay in the landing chest.
		assertFalse(Stockkeeping.toCore(Set.of(Items.DIRT, Items.COBBLESTONE), Set.of(), List.of(), stock));
	}

	@Test
	void orderIngredientsInALandingChestAreNotWantedAgain() {
		// Stock as Storage.colonyStock counts it: the core and the mineshaft landing chests.
		Map<Item, Integer> core = Map.of(Items.STICK, 8);
		Map<Item, Integer> landingChest = Map.of(Items.COBBLESTONE, 3);
		Map<Item, Integer> stock = StockPlanner.total(List.of(core, landingChest));
		Set<Item> forOrders = Stockkeeping.wantedForOrders(Map.of(Items.STONE_PICKAXE, 1), stock, VanillaData.recipeBook(), VanillaData.gatherable());
		assertFalse(forOrders.contains(Items.COBBLESTONE));
		assertFalse(forOrders.contains(Items.COBBLED_DEEPSLATE));
		// So a miner's cobblestone joins the rest in the landing chest instead of going up to the core.
		assertFalse(Stockkeeping.toCore(Set.of(Items.DIRT, Items.COBBLESTONE), forOrders, List.of(), stock));
		// Short of what the order needs, it is still wanted.
		Map<Item, Integer> tooFew = StockPlanner.total(List.of(core, Map.of(Items.COBBLESTONE, 2)));
		assertTrue(Stockkeeping.wantedForOrders(Map.of(Items.STONE_PICKAXE, 1), tooFew, VanillaData.recipeBook(), VanillaData.gatherable())
			.contains(Items.COBBLESTONE));
	}

	@Test
	void wantedCountsWhatOrdersNeedAndWhatStockTargetsAskFor() {
		var wanted = Stockkeeping.wanted(Set.of("raw_iron"), TARGETS);
		assertTrue(wanted.test("raw_iron"));
		assertTrue(wanted.test("oak_log"));
		assertTrue(wanted.test("wooden_pickaxe"));
		assertFalse(wanted.test("dirt"));
	}

	@Test
	void storageNeedsAChestPerFullCategoryLessEmptyChestsSpotsAndChestsHeld() {
		// Three categories have no chest with room, and there are spots for them all.
		assertEquals(3, Stockkeeping.chestsToOrder(3, 0, 5, 0));
		// An empty chest takes one category.
		assertEquals(2, Stockkeeping.chestsToOrder(3, 1, 5, 0));
		// No more chests than free spots.
		assertEquals(2, Stockkeeping.chestsToOrder(3, 0, 2, 0));
		// Chests the colony already holds are placed instead.
		assertEquals(1, Stockkeeping.chestsToOrder(3, 0, 5, 2));
		assertEquals(0, Stockkeeping.chestsToOrder(1, 0, 5, 3));
		assertEquals(0, Stockkeeping.chestsToOrder(0, 0, 5, 0));
		assertEquals(0, Stockkeeping.chestsToOrder(2, 0, 0, 0));
	}
}
