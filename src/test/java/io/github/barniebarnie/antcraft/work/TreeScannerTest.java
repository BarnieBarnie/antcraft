package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class TreeScannerTest {
	/**
	 * 'L' = log, 'n' = natural leaves, 'p' = player-placed (persistent) leaves, 'a' = air, 'd' = dirt.
	 * Unset blocks are air above y=0 and ground at and below it.
	 */
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

		@Override
		public boolean isOpen(BlockPos pos) {
			Character c = this.blocks.get(pos);
			return c == null ? pos.getY() > 0 : c == 'a';
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

	@Test
	void findsAGiantTreeWhole() {
		// A 2x2 trunk 40 logs tall, as a giant jungle tree.
		Grid grid = new Grid().trunk(0, 0, 1, 40).trunk(1, 0, 1, 40).trunk(0, 1, 1, 40).trunk(1, 1, 1, 40).canopy(0, 40, 0, 'n');
		TreeScanner.Tree tree = TreeScanner.treeAt(grid, new BlockPos(0, 1, 0)).orElseThrow();

		assertEquals(160, tree.logs().size());
	}

	@Test
	void leftoverLogsOfAFelledTreeAreStillFoundOnceTheLeavesAreGone() {
		Grid grid = new Grid().trunk(5, 0, 1, 6).set(6, 7, 1, 'L').canopy(5, 6, 0, 'n');
		TreeScanner.Tree felling = TreeScanner.treeAt(grid, new BlockPos(5, 1, 0)).orElseThrow();
		// The lower trunk is chopped and the leaves decay.
		grid.blocks.replaceAll((pos, c) -> c == 'n' ? 'a' : c);
		for (int y = 1; y <= 4; y++) {
			grid.set(5, y, 0, 'a');
		}
		assertTrue(TreeScanner.treeAt(grid, new BlockPos(5, 5, 0)).isEmpty());

		TreeScanner.Tree rest = TreeScanner.remaining(grid, felling.logs()).orElseThrow();
		assertEquals(List.of(new BlockPos(5, 5, 0), new BlockPos(5, 6, 0), new BlockPos(6, 7, 1)), rest.logs());
		assertEquals(new BlockPos(5, 5, 0), rest.base());
	}

	@Test
	void leftoverLogsKeepTheGivenBase() {
		Grid grid = new Grid().trunk(5, 0, 1, 4).canopy(5, 4, 0, 'n');
		TreeScanner.Tree felling = TreeScanner.treeAt(grid, new BlockPos(5, 1, 0)).orElseThrow();
		// The bottom log is chopped: the rest stands in the air above the original base.
		grid.set(5, 1, 0, 'a');

		TreeScanner.Tree rest = TreeScanner.remaining(grid, felling.base(), felling.logs()).orElseThrow();
		assertEquals(new BlockPos(5, 1, 0), rest.base());
		assertEquals(List.of(new BlockPos(5, 2, 0), new BlockPos(5, 3, 0), new BlockPos(5, 4, 0)), rest.logs());
	}

	@Test
	void leftoverLogsOnlyCoverTheKnownTree() {
		Grid grid = new Grid().trunk(5, 0, 1, 4).canopy(5, 4, 0, 'n');
		TreeScanner.Tree felling = TreeScanner.treeAt(grid, new BlockPos(5, 1, 0)).orElseThrow();
		// A player builds a leafless log wall against the trunk.
		grid.trunk(6, 0, 1, 3).trunk(7, 0, 1, 3);

		TreeScanner.Tree rest = TreeScanner.remaining(grid, felling.logs()).orElseThrow();
		assertEquals(felling.logs(), rest.logs());
	}

	@Test
	void leaflessPlayerLogStructureIsNotATree() {
		// A small log house: walls with no leaves anywhere.
		Grid grid = new Grid();
		for (int x = 0; x <= 4; x++) {
			grid.trunk(x, 0, 1, 3).trunk(x, 4, 1, 3);
		}
		grid.trunk(0, 2, 1, 3).trunk(4, 2, 1, 3);

		assertTrue(TreeScanner.findAll(grid, BlockPos.ZERO, 10).isEmpty());
		assertTrue(TreeScanner.treeAt(grid, new BlockPos(0, 1, 0)).isEmpty());
		assertTrue(TreeScanner.remaining(grid, List.of()).isEmpty());
	}

	@Test
	void nothingRemainsOnceAllLogsAreChopped() {
		Grid grid = new Grid().trunk(5, 0, 1, 4).canopy(5, 4, 0, 'n');
		TreeScanner.Tree felling = TreeScanner.treeAt(grid, new BlockPos(5, 1, 0)).orElseThrow();
		for (int y = 1; y <= 4; y++) {
			grid.set(5, y, 0, 'a');
		}
		assertTrue(TreeScanner.remaining(grid, felling.logs()).isEmpty());
	}

	@Test
	void treeWithItsBottomLogCutHasItsBaseOnTheGround() {
		// Someone cut the bottom log; the rest of the trunk stands in the air.
		Grid grid = new Grid().trunk(5, 0, 2, 4).canopy(5, 4, 0, 'n');

		assertEquals(new BlockPos(5, 1, 0), TreeScanner.treeAt(grid, new BlockPos(5, 3, 0)).orElseThrow().base());
		assertEquals(new BlockPos(5, 1, 0), TreeScanner.findNearest(grid, BlockPos.ZERO, 10).orElseThrow().base());
	}

	@Test
	void treeWithSeveralLowerLogsCutHasItsBaseOnTheGround() {
		Grid grid = new Grid().trunk(5, 0, 3, 5).canopy(5, 5, 0, 'n');

		assertEquals(new BlockPos(5, 1, 0), TreeScanner.treeAt(grid, new BlockPos(5, 4, 0)).orElseThrow().base());
	}

	@Test
	void intactTreeOnRaisedGroundKeepsItsBottomLogAsBase() {
		Grid grid = new Grid().set(5, 1, 0, 'd').trunk(5, 0, 2, 4).canopy(5, 4, 0, 'n');

		assertEquals(new BlockPos(5, 2, 0), TreeScanner.treeAt(grid, new BlockPos(5, 3, 0)).orElseThrow().base());
	}

	@Test
	void treeWithNoGroundInReachKeepsItsBottomLogAsBase() {
		// Hanging over a deep drop: no spot a sapling could stand on.
		Grid grid = new Grid().trunk(5, 0, 40, 43).canopy(5, 43, 0, 'n');

		assertEquals(new BlockPos(5, 40, 0), TreeScanner.treeAt(grid, new BlockPos(5, 41, 0)).orElseThrow().base());
	}
}
