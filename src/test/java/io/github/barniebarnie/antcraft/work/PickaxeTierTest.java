package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PickaxeTierTest {
	/** Loads the vanilla tags that say which tool harvests which block. */
	@BeforeAll
	static void loadTags() {
		VanillaData.recipeBook();
	}

	@Test
	void harvestsLikeAPlayer() {
		assertFalse(PickaxeTier.canHarvest(Items.WOODEN_PICKAXE, Blocks.IRON_ORE.defaultBlockState()));
		assertTrue(PickaxeTier.canHarvest(Items.STONE_PICKAXE, Blocks.IRON_ORE.defaultBlockState()));
		assertFalse(PickaxeTier.canHarvest(Items.STONE_PICKAXE, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()));
		assertTrue(PickaxeTier.canHarvest(Items.IRON_PICKAXE, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()));
		assertFalse(PickaxeTier.canHarvest(null, Blocks.STONE.defaultBlockState()));
		// Blocks that need no tool, like logs, drop for anyone.
		assertTrue(PickaxeTier.canHarvest(null, Blocks.OAK_LOG.defaultBlockState()));
	}

	@Test
	void levelNeedsThePickaxeItsWantedOresNeed() {
		// The diamond level.
		assertEquals(Items.IRON_PICKAXE, PickaxeTier.forLevel(-58, item -> item == Items.DIAMOND));
		// The iron level.
		assertEquals(Items.STONE_PICKAXE, PickaxeTier.forLevel(16, item -> item == Items.RAW_IRON));
		// Gold and iron share a level; only gold needs iron.
		assertEquals(Items.STONE_PICKAXE, PickaxeTier.forLevel(-16, item -> item == Items.RAW_IRON || item == Items.LAPIS_LAZULI));
		assertEquals(Items.IRON_PICKAXE, PickaxeTier.forLevel(-16, item -> item == Items.RAW_GOLD));
		// Nothing in particular wanted: every ore of the level.
		assertEquals(Items.STONE_PICKAXE, PickaxeTier.forLevel(16, item -> false));
		assertEquals(Items.IRON_PICKAXE, PickaxeTier.forLevel(-58, item -> false));
	}

	@Test
	void ordersTheStepItsPickaxeCanMineTheMaterialsFor() {
		// Stone mines raw iron, so it can work towards an iron pickaxe.
		assertEquals(Items.IRON_PICKAXE, PickaxeTier.toOrder(Items.STONE_PICKAXE, Items.IRON_PICKAXE));
		// Wood cannot: a stone one first.
		assertEquals(Items.STONE_PICKAXE, PickaxeTier.toOrder(Items.WOODEN_PICKAXE, Items.IRON_PICKAXE));
		assertEquals(Items.STONE_PICKAXE, PickaxeTier.toOrder(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE));
		assertEquals(Items.WOODEN_PICKAXE, PickaxeTier.toOrder(null, Items.IRON_PICKAXE));
	}

	@Test
	void branchJobNeedsThePickaxeOfTheWantedOresThereOrAnyForStone() {
		// The iron level, for raw iron.
		assertEquals(Items.STONE_PICKAXE, PickaxeTier.forBranch(16, item -> item == Items.RAW_IRON));
		assertEquals(Items.IRON_PICKAXE, PickaxeTier.forBranch(-58, item -> item == Items.DIAMOND));
		// Only stone wanted, or ores that are not found there: any pickaxe digs it.
		assertEquals(Items.WOODEN_PICKAXE, PickaxeTier.forBranch(16, item -> item == Items.COBBLESTONE));
		assertEquals(Items.WOODEN_PICKAXE, PickaxeTier.forBranch(-58, item -> item == Items.RAW_IRON));
	}

}
