package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CraftingTest {
	@BeforeAll
	static void loadVanillaData() {
		VanillaData.recipeBook();
	}

	private static TaskPlanner.Recipe<Item> recipe(Item output, boolean smelting) {
		return VanillaData.recipeBook().recipesFor(output).stream().filter(recipe -> recipe.smelting() == smelting).findFirst().orElseThrow();
	}

	@Test
	void stairsNeedACraftingTableAndSticksDoNot() {
		assertEquals(Crafting.Station.TABLE, Crafting.station(recipe(Items.COBBLESTONE_STAIRS, false)));
		assertEquals(Crafting.Station.HAND, Crafting.station(recipe(Items.STICK, false)));
		assertEquals(Crafting.Station.HAND, Crafting.station(recipe(Items.OAK_PLANKS, false)));
		assertEquals(Crafting.Station.FURNACE, Crafting.station(recipe(Items.IRON_INGOT, true)));
	}

	@Test
	void aThreeByThreeRecipeIsOnlyClaimableWithACarriedOrStandingCraftingTable() {
		TaskPlanner.Recipe<Item> stairs = recipe(Items.COBBLESTONE_STAIRS, false);
		assertFalse(Crafting.canClaim(stairs, List.of(Items.COBBLESTONE), false));
		assertTrue(Crafting.canClaim(stairs, List.of(Items.COBBLESTONE, Items.CRAFTING_TABLE), false));
		assertTrue(Crafting.canClaim(stairs, List.of(Items.COBBLESTONE), true));
		// A furnace is no crafting table.
		assertFalse(Crafting.canClaim(stairs, List.of(Items.COBBLESTONE, Items.FURNACE), false));
	}

	@Test
	void aTwoByTwoRecipeIsClaimableByAnyBot() {
		TaskPlanner.Recipe<Item> sticks = recipe(Items.STICK, false);
		assertTrue(Crafting.canClaim(sticks, List.of(), false));
		assertTrue(Crafting.canClaim(recipe(Items.OAK_PLANKS, false), List.of(Items.OAK_LOG), false));
	}

	@Test
	void smeltingIsOnlyClaimableWithACarriedOrStandingFurnace() {
		TaskPlanner.Recipe<Item> ingot = recipe(Items.IRON_INGOT, true);
		assertFalse(Crafting.canClaim(ingot, List.of(Items.RAW_IRON, Items.CRAFTING_TABLE), false));
		assertTrue(Crafting.canClaim(ingot, List.of(Items.RAW_IRON, Items.FURNACE), false));
		assertTrue(Crafting.canClaim(ingot, List.of(Items.RAW_IRON), true));
	}
}
