package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every idle bot asks whether each open craft job is ready, which plans all open orders (see CrafterGoal); the colony
 * plans them once for as long as its orders, the stock they are planned from and the recipes stay the same.
 */
class OrderPlansTest {
	private static TaskPlanner.RecipeBook<Item> BOOK;

	@BeforeAll
	static void bootstrap() {
		BOOK = VanillaData.recipeBook();
	}

	/** Counts how often the planner looks up recipes. */
	private static final class Counting implements TaskPlanner.RecipeBook<Item> {
		int lookups;

		@Override
		public List<TaskPlanner.Recipe<Item>> recipesFor(Item item) {
			this.lookups++;
			return BOOK.recipesFor(item);
		}

		@Override
		public List<TaskPlanner.Fuel<Item>> fuels() {
			return BOOK.fuels();
		}
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	@Test
	void theOrdersArePlannedOnceWhileNothingTheyArePlannedFromChanges() {
		Colony colony = colony();
		colony.addOrder(Items.STONE_PICKAXE, 1);
		colony.addOrder(Items.TORCH, 4);
		Counting book = new Counting();
		Map<Item, Integer> stock = Map.of(Items.OAK_LOG, 8, Items.COBBLESTONE, 3, Items.COAL, 2);

		Map<Item, TaskPlanner.Plan<Item>> plans = colony.orderPlans(stock, book);
		int lookups = book.lookups;
		assertTrue(lookups > 0);
		assertEquals(Map.of(Items.STONE_PICKAXE, TaskPlanner.plan(Items.STONE_PICKAXE, 1, Map.of(Items.OAK_LOG, 8, Items.COBBLESTONE, 3, Items.COAL, 2),
			BOOK, Gatherables::isGatherable), Items.TORCH,
			TaskPlanner.plan(Items.TORCH, 4, Map.of(Items.OAK_LOG, 8, Items.COBBLESTONE, 3, Items.COAL, 2), BOOK,
				Gatherables::isGatherable)), plans);

		assertSame(plans, colony.orderPlans(Map.of(Items.OAK_LOG, 8, Items.COBBLESTONE, 3, Items.COAL, 2), book), "same orders and stock");
		assertEquals(lookups, book.lookups, "planned again");

		Map<Item, TaskPlanner.Plan<Item>> fewerLogs = colony.orderPlans(Map.of(Items.OAK_LOG, 7, Items.COBBLESTONE, 3, Items.COAL, 2), book);
		assertNotSame(plans, fewerLogs, "other stock");
		colony.addOrder(Items.STONE_AXE, 1);
		Map<Item, TaskPlanner.Plan<Item>> moreOrders = colony.orderPlans(Map.of(Items.OAK_LOG, 7, Items.COBBLESTONE, 3, Items.COAL, 2), book);
		assertNotSame(fewerLogs, moreOrders, "other orders");
		assertTrue(moreOrders.containsKey(Items.STONE_AXE));
		assertNotSame(moreOrders, colony.orderPlans(Map.of(Items.OAK_LOG, 7, Items.COBBLESTONE, 3, Items.COAL, 2), BOOK), "other recipes");
	}

	/** An order's own item in stock does not count toward it: each order is planned as if none of it were there. */
	@Test
	void eachOrderIsPlannedWithoutItsOwnItemInStock() {
		Colony colony = colony();
		colony.addOrder(Items.OAK_PLANKS, 4);
		Map<Item, TaskPlanner.Plan<Item>> plans = colony.orderPlans(Map.of(Items.OAK_PLANKS, 64, Items.OAK_LOG, 1), BOOK);
		assertEquals(TaskPlanner.plan(Items.OAK_PLANKS, 4, Map.of(Items.OAK_LOG, 1), BOOK, Gatherables::isGatherable),
			plans.get(Items.OAK_PLANKS));
	}
}
