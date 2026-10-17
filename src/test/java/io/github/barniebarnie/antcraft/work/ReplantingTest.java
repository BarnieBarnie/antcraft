package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReplantingTest {
	private static final int WAIT = 2400;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void logsMapToTheirSapling() {
		assertEquals(Optional.of(Items.DARK_OAK_SAPLING), Replanting.saplingFor(Blocks.DARK_OAK_LOG));
		assertEquals(Optional.of(Items.JUNGLE_SAPLING), Replanting.saplingFor(Blocks.JUNGLE_LOG));
		assertEquals(Optional.of(Items.SPRUCE_SAPLING), Replanting.saplingFor(Blocks.SPRUCE_WOOD));
		assertEquals(Optional.of(Items.PALE_OAK_SAPLING), Replanting.saplingFor(Blocks.PALE_OAK_LOG));
	}

	@Test
	void logsWithoutASaplingMapToNothing() {
		assertTrue(Replanting.saplingFor(Blocks.CRIMSON_STEM).isEmpty());
		assertTrue(Replanting.saplingFor(Blocks.MANGROVE_LOG).isEmpty());
	}

	@Test
	void a1x1TreeWaitsForItsOwnKindThenTakesAny() {
		Optional<net.minecraft.world.item.Item> birch = Optional.of(Items.BIRCH_SAPLING);
		long felled = 1000;

		assertEquals(birch, Replanting.saplingNeeded(birch, 1, felled, felled, WAIT));
		assertEquals(birch, Replanting.saplingNeeded(birch, 1, felled, felled + WAIT - 1, WAIT));
		assertEquals(Optional.empty(), Replanting.saplingNeeded(birch, 1, felled, felled + WAIT, WAIT));
	}

	@Test
	void theWaitFollowsTheConfiguredValue() {
		Optional<net.minecraft.world.item.Item> birch = Optional.of(Items.BIRCH_SAPLING);

		assertEquals(birch, Replanting.saplingNeeded(birch, 1, 0, 599, 600));
		assertEquals(Optional.empty(), Replanting.saplingNeeded(birch, 1, 0, 600, 600));
		assertEquals(birch, Replanting.saplingNeeded(birch, 1, 0, 5999, 6000));
		assertEquals(Optional.empty(), Replanting.saplingNeeded(birch, 1, 0, 0, 0));
	}

	@Test
	void a2x2TreeAlwaysNeedsItsOwnKind() {
		Optional<net.minecraft.world.item.Item> darkOak = Optional.of(Items.DARK_OAK_SAPLING);

		assertEquals(darkOak, Replanting.saplingNeeded(darkOak, 4, 0, 10 * WAIT, WAIT));
	}

	@Test
	void aTreeOfNoKnownKindTakesAnySaplingRightAway() {
		assertEquals(Optional.empty(), Replanting.saplingNeeded(Optional.empty(), 1, 0, 0, WAIT));
	}

	@Test
	void aSiteWithAllItsSpotsOpenIsPlantedWhole() {
		List<BlockPos> square = List.of(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0), new BlockPos(0, 1, 1), new BlockPos(1, 1, 1));

		assertEquals(square, Replanting.spotsToPlant(square, spot -> true));
		assertEquals(List.of(square.getFirst()), Replanting.spotsToPlant(List.of(square.getFirst()), spot -> true));
	}

	@Test
	void aPartlyBuiltOver2x2SiteFallsBackToA1x1Replant() {
		List<BlockPos> square = List.of(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0), new BlockPos(0, 1, 1), new BlockPos(1, 1, 1));

		List<BlockPos> toPlant = Replanting.spotsToPlant(square, spot -> !spot.equals(square.getFirst()));
		assertEquals(List.of(square.get(1)), toPlant);
		// One spot to plant, so a 1x1 tree's rule: its own kind for a while, then any.
		assertEquals(Optional.empty(), Replanting.saplingNeeded(Optional.of(Items.SPRUCE_SAPLING), toPlant.size(), 0, WAIT, WAIT));
	}

	@Test
	void aSiteWithNoOpenSpotHasNothingToPlant() {
		List<BlockPos> square = List.of(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0), new BlockPos(0, 1, 1), new BlockPos(1, 1, 1));

		assertEquals(List.of(), Replanting.spotsToPlant(square, spot -> false));
		assertEquals(List.of(), Replanting.spotsToPlant(List.of(square.getFirst()), spot -> false));
	}
}
