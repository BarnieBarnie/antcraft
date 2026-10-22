package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
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

	/** The miner fetches and orders the pickaxe its branch job declares, the one the board scores it by. */
	@Test
	void minerGetsThePickaxeItsBranchJobDeclares() {
		Job wooden = branchJob(16, Optional.of(Items.WOODEN_PICKAXE));
		// Only stone wanted at the iron level: a wooden pickaxe, not the stone one the level's iron would need.
		assertEquals(Items.WOODEN_PICKAXE, PickaxeTier.forBranch(wooden, item -> item == Items.COBBLESTONE));
		assertEquals(Items.WOODEN_PICKAXE, PickaxeTier.forBranch(wooden, item -> item == Items.RAW_IRON));
		// A job from an old save, without a tool: worked out as the board does.
		assertEquals(Items.WOODEN_PICKAXE, PickaxeTier.forBranch(branchJob(16, Optional.empty()), item -> item == Items.COBBLESTONE));
		assertEquals(Items.STONE_PICKAXE, PickaxeTier.forBranch(branchJob(16, Optional.empty()), item -> item == Items.RAW_IRON));
	}

	private static Job branchJob(int y, Optional<Item> tool) {
		return new Job(UUID.randomUUID(), JobType.MINE_BRANCH, new BlockPos(0, y, 0), Optional.empty(), 0L, List.of(), Optional.empty(), 0, 0L, 0L, tool, 0);
	}
}
