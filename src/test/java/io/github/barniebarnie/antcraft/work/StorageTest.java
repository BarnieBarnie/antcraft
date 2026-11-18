package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StorageTest {
	private static TaskPlanner.Recipe<Item> PICKAXE;
	private static TaskPlanner.Recipe<Item> PLANKS;

	@BeforeAll
	static void bootstrap() {
		// Item stacks need their components bound, which happens once the registries are frozen.
		VanillaData.recipeBook();
		PICKAXE = new TaskPlanner.Recipe<>(Items.STONE_PICKAXE, 1, Map.of(Set.of(Items.COBBLESTONE), 3, Set.of(Items.STICK), 2));
		PLANKS = new TaskPlanner.Recipe<>(Items.OAK_PLANKS, 4, Map.of(Set.of(Items.OAK_LOG), 1));
	}

	private static SimpleContainer full(ItemStack... stacks) {
		SimpleContainer container = new SimpleContainer(stacks.length);
		for (int i = 0; i < stacks.length; i++) {
			container.setItem(i, stacks[i]);
		}
		return container;
	}

	@Test
	void aCraftThatUsesUpAWholeStackFitsInAFullContainer() {
		SimpleContainer container = full(new ItemStack(Items.COBBLESTONE, 3), new ItemStack(Items.STICK, 64), new ItemStack(Items.DIRT, 64));
		assertTrue(Storage.fits(container, PICKAXE));
		assertTrue(Storage.craft(container, PICKAXE));
		assertEquals(1, container.countItem(Items.STONE_PICKAXE));
		assertEquals(62, container.countItem(Items.STICK));
	}

	@Test
	void aCraftWhoseResultHasNoRoomDoesNotFit() {
		SimpleContainer container = full(new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.STICK, 64), new ItemStack(Items.DIRT, 64));
		assertFalse(Storage.fits(container, PICKAXE));
	}

	@Test
	void aCraftThatDoesNotFitLeavesTheContainerAlone() {
		SimpleContainer container = full(new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.STICK, 64), new ItemStack(Items.DIRT, 64));
		assertFalse(Storage.craft(container, PICKAXE));
		assertEquals(64, container.countItem(Items.COBBLESTONE));
		assertEquals(64, container.countItem(Items.STICK));
		assertEquals(0, container.countItem(Items.STONE_PICKAXE));
	}

	@Test
	void theResultMayTopUpAStackOfItself() {
		SimpleContainer container = full(new ItemStack(Items.OAK_LOG, 2), new ItemStack(Items.OAK_PLANKS, 60), new ItemStack(Items.DIRT, 64));
		assertTrue(Storage.fits(container, PLANKS));
		// Topped up to 64: no room for four more.
		container.setItem(1, new ItemStack(Items.OAK_PLANKS, 61));
		assertFalse(Storage.fits(container, PLANKS));
	}

	/** As the planner takes them (#925): the option there is most of, the first one of the recipe's set on a tie, not the first slot. */
	@Test
	void aCraftTakesTheInterchangeableItemThereIsMostOf() {
		TaskPlanner.Recipe<Item> sticks = new TaskPlanner.Recipe<>(Items.STICK, 4, Map.of(new LinkedHashSet<>(List.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS)), 2));
		SimpleContainer container = full(new ItemStack(Items.OAK_PLANKS, 3), new ItemStack(Items.BIRCH_PLANKS, 4), ItemStack.EMPTY);
		assertTrue(Storage.craft(container, sticks));
		assertEquals(3, container.countItem(Items.OAK_PLANKS));
		assertEquals(2, container.countItem(Items.BIRCH_PLANKS));
		SimpleContainer tie = full(new ItemStack(Items.BIRCH_PLANKS, 2), new ItemStack(Items.OAK_PLANKS, 2), ItemStack.EMPTY);
		assertTrue(Storage.craft(tie, sticks));
		assertEquals(0, tie.countItem(Items.OAK_PLANKS));
		assertEquals(2, tie.countItem(Items.BIRCH_PLANKS));
	}

	/** use (#930): of interchangeable items, those named first, in that order, though there is more of another. */
	@Test
	void aCraftTakesTheItemsItIsToUseFirst() {
		TaskPlanner.Recipe<Item> table = new TaskPlanner.Recipe<>(Items.CRAFTING_TABLE, 1, Map.of(new LinkedHashSet<>(List.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS)), 4));
		SimpleContainer container = full(new ItemStack(Items.OAK_PLANKS, 4), new ItemStack(Items.BIRCH_PLANKS, 3), ItemStack.EMPTY);
		assertTrue(Storage.craft(container, table, List.of(Items.BIRCH_PLANKS)));
		assertEquals(3, container.countItem(Items.OAK_PLANKS));
		assertEquals(0, container.countItem(Items.BIRCH_PLANKS));
	}

	@Test
	void addItemTopsUpStacksBeforeFillingEmptySlots() {
		SimpleContainer container = full(ItemStack.EMPTY, new ItemStack(Items.COBBLESTONE, 60), ItemStack.EMPTY, new ItemStack(Items.COBBLESTONE, 10));
		ItemStack rest = Storage.addItem(container, new ItemStack(Items.COBBLESTONE, 4 + 54 + 64 + 10));
		assertEquals(64, container.getItem(1).getCount());
		assertEquals(64, container.getItem(3).getCount());
		assertEquals(64, container.getItem(0).getCount());
		assertEquals(10, container.getItem(2).getCount());
		assertEquals(0, rest.getCount());
	}
}
