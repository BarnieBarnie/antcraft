package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class QuarryTest {
	private static final Quarry QUARRY = new Quarry(new BlockPos(0, 0, 0), new BlockPos(2, 3, 2));

	/** Every position in the set still has a block to mine. */
	private static Set<BlockPos> solid(int fromY, int toY) {
		Set<BlockPos> blocks = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(0, fromY, 0), new BlockPos(2, toY, 2))) {
			blocks.add(pos.immutable());
		}
		return blocks;
	}

	@Test
	void cornersAreNormalised() {
		Quarry q = new Quarry(new BlockPos(2, 3, 2), new BlockPos(0, 0, 0));
		assertEquals(QUARRY, q);
	}

	@Test
	void topLayersWithBlocksAreWorkedFirst() {
		Set<BlockPos> blocks = solid(0, 2);
		assertEquals(List.of(2, 1), QUARRY.layersToWork(blocks::contains, 2));
	}

	@Test
	void emptyLayersAreSkipped() {
		Set<BlockPos> blocks = solid(0, 1);
		assertEquals(List.of(1, 0), QUARRY.layersToWork(blocks::contains, 2));
	}

	@Test
	void finishedQuarryHasNoLayers() {
		assertTrue(QUARRY.layersToWork(pos -> false, 2).isEmpty());
	}

	@Test
	void blocksInLayerAreOrderedNearestFirst() {
		Set<BlockPos> blocks = solid(1, 1);
		List<BlockPos> order = QUARRY.blocksInLayer(1, blocks::contains, new BlockPos(3, 1, 3));

		assertEquals(9, order.size());
		assertEquals(new BlockPos(2, 1, 2), order.getFirst());
		assertEquals(new BlockPos(0, 1, 0), order.getLast());
	}

	@Test
	void containsChecksTheBox() {
		assertTrue(QUARRY.contains(new BlockPos(1, 2, 1)));
		assertTrue(!QUARRY.contains(new BlockPos(3, 2, 1)));
	}
}
