package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class TreeScannerTest {
	/** 'L' = log, 'n' = natural leaves, 'p' = player-placed (persistent) leaves. */
	private static final class Grid implements TreeScanner.TreeView {
		private final Map<BlockPos, Character> blocks = new HashMap<>();

		Grid set(int x, int y, int z, char c) {
			this.blocks.put(new BlockPos(x, y, z), c);
			return this;
		}

		Grid trunk(int x, int z, int fromY, int toY) {
			for (int y = fromY; y <= toY; y++) {
				this.set(x, y, z, 'L');
			}
			return this;
		}

		Grid canopy(int x, int y, int z, char leaves) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					this.blocks.putIfAbsent(new BlockPos(x + dx, y, z + dz), leaves);
				}
			}
			return this.set(x, y + 1, z, leaves);
		}

		@Override
		public boolean isLog(BlockPos pos) {
			return this.blocks.get(pos) instanceof Character c && c == 'L';
		}

		@Override
		public boolean isNaturalLeaves(BlockPos pos) {
			return this.blocks.get(pos) instanceof Character c && c == 'n';
		}
	}

	@Test
	void findsTreeWithNaturalLeaves() {
		Grid grid = new Grid().trunk(5, 0, 1, 4).canopy(5, 4, 0, 'n');
		Optional<TreeScanner.Tree> tree = TreeScanner.findNearest(grid, new BlockPos(0, 1, 0), 10);

		assertTrue(tree.isPresent());
		assertEquals(new BlockPos(5, 1, 0), tree.get().base());
		assertEquals(4, tree.get().logs().size());
	}

	@Test
	void logsAreOrderedBottomUp() {
		Grid grid = new Grid().trunk(5, 0, 1, 4).canopy(5, 4, 0, 'n');
		TreeScanner.Tree tree = TreeScanner.findNearest(grid, BlockPos.ZERO, 10).orElseThrow();

		for (int i = 1; i < tree.logs().size(); i++) {
			assertTrue(tree.logs().get(i - 1).getY() <= tree.logs().get(i).getY());
		}
	}

	@Test
	void ignoresLogsWithoutLeaves() {
		Grid grid = new Grid().trunk(5, 0, 1, 4);
		assertTrue(TreeScanner.findNearest(grid, BlockPos.ZERO, 10).isEmpty());
	}

	@Test
	void ignoresLogsWithPlayerPlacedLeaves() {
		Grid grid = new Grid().trunk(5, 0, 1, 4).canopy(5, 4, 0, 'p');
		assertTrue(TreeScanner.findNearest(grid, BlockPos.ZERO, 10).isEmpty());
	}

	@Test
	void picksNearestTree() {
		Grid grid = new Grid()
			.trunk(8, 0, 1, 4).canopy(8, 4, 0, 'n')
			.trunk(-3, 0, 1, 4).canopy(-3, 4, 0, 'n');
		TreeScanner.Tree tree = TreeScanner.findNearest(grid, BlockPos.ZERO, 10).orElseThrow();

		assertEquals(new BlockPos(-3, 1, 0), tree.base());
	}

	@Test
	void includesDiagonalBranches() {
		Grid grid = new Grid().trunk(0, 0, 1, 4).set(1, 4, 1, 'L').set(2, 5, 2, 'L').canopy(2, 5, 2, 'n');
		TreeScanner.Tree tree = TreeScanner.findNearest(grid, BlockPos.ZERO, 10).orElseThrow();

		assertEquals(6, tree.logs().size());
	}

	@Test
	void outOfRangeTreeIsNotFound() {
		Grid grid = new Grid().trunk(20, 0, 1, 4).canopy(20, 4, 0, 'n');
		assertTrue(TreeScanner.findNearest(grid, BlockPos.ZERO, 10).isEmpty());
	}

	@Test
	void findsAllTreesInRange() {
		Grid grid = new Grid()
			.trunk(8, 0, 1, 4).canopy(8, 4, 0, 'n')
			.trunk(-3, 0, 1, 4).canopy(-3, 4, 0, 'n')
			.trunk(0, 6, 1, 3);
		assertEquals(2, TreeScanner.findAll(grid, BlockPos.ZERO, 10).size());
	}

	@Test
	void treeAtFindsTheTreeContainingALog() {
		Grid grid = new Grid().trunk(5, 0, 1, 4).canopy(5, 4, 0, 'n');
		TreeScanner.Tree tree = TreeScanner.treeAt(grid, new BlockPos(5, 3, 0)).orElseThrow();

		assertEquals(new BlockPos(5, 1, 0), tree.base());
	}

	@Test
	void treeAtIsEmptyWhenNoLog() {
		assertTrue(TreeScanner.treeAt(new Grid(), new BlockPos(5, 1, 0)).isEmpty());
	}
}
