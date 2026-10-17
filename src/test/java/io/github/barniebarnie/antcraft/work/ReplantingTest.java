package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReplantingTest {
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

		assertEquals(birch, Replanting.saplingNeeded(birch, 1, felled, felled));
		assertEquals(birch, Replanting.saplingNeeded(birch, 1, felled, felled + Replanting.MATCH_WAIT_TICKS - 1));
		assertEquals(Optional.empty(), Replanting.saplingNeeded(birch, 1, felled, felled + Replanting.MATCH_WAIT_TICKS));
	}

	@Test
	void a2x2TreeAlwaysNeedsItsOwnKind() {
		Optional<net.minecraft.world.item.Item> darkOak = Optional.of(Items.DARK_OAK_SAPLING);

		assertEquals(darkOak, Replanting.saplingNeeded(darkOak, 4, 0, 10 * Replanting.MATCH_WAIT_TICKS));
	}

	@Test
	void aTreeOfNoKnownKindTakesAnySaplingRightAway() {
		assertEquals(Optional.empty(), Replanting.saplingNeeded(Optional.empty(), 1, 0, 0));
	}
}
