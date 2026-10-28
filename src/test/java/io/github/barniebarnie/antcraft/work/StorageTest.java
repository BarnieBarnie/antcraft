package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
