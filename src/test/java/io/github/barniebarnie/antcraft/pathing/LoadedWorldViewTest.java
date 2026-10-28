package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** Unloaded blocks are a wall that cannot be broken, built on or opened: a plan never reads or enters them. */
class LoadedWorldViewTest {
	private static final BlockPos START = new BlockPos(0, 1, 0);
	private static final PathFinder.Options FULL = new PathFinder.Options(true, true, 10_000, 3);

	/** The world with x of 4 and up unloaded. */
	private static WorldView loadedBelowX4(WorldView world) {
		return new LoadedWorldView(world, pos -> pos.getX() < 4);
	}

	@Test
	void anUnloadedBlockIsAWallThatCannotBeBrokenOrUsed() {
		GridWorld grid = GridWorld.flat(10).door(5, 1, 0, Direction.Axis.X).set(6, 1, 1, 'H').set(6, 1, 2, 'W');
		WorldView view = loadedBelowX4(grid);
		for (BlockPos pos : new BlockPos[] {new BlockPos(5, 1, 0), new BlockPos(6, 1, 1), new BlockPos(6, 1, 2), new BlockPos(5, 0, 0), new BlockPos(5, 3, 0)}) {
			assertFalse(view.isPassable(pos), pos.toString());
			assertFalse(view.canStandOn(pos), pos.toString());
			assertTrue(view.isHazard(pos), pos.toString());
			assertFalse(view.isWater(pos), pos.toString());
			assertTrue(view.breakTicks(pos) < 0, pos.toString());
			assertFalse(view.canOpen(pos, Direction.EAST), pos.toString());
			assertFalse(view.isClimbable(pos), pos.toString());
			assertFalse(view.stairUp(pos, Direction.EAST), pos.toString());
			assertFalse(view.mayPlace(pos), pos.toString());
		}
		// Loaded blocks read through.
		assertTrue(view.isPassable(new BlockPos(3, 1, 0)));
		assertTrue(view.canStandOn(new BlockPos(3, 0, 0)));
		assertEquals(30, view.breakTicks(new BlockPos(3, 0, 0)));
	}

	@Test
	void aPathDoesNotGoIntoUnloadedBlocks() {
		WorldView view = loadedBelowX4(GridWorld.flat(10));
		Path path = PathFinder.find(view, START, new Goal.Block(new BlockPos(6, 1, 0)), FULL);
		assertFalse(path.complete());
		assertTrue(PathFinder.find(view, START, new Goal.Block(new BlockPos(3, 1, 0)), FULL).complete());
	}
}
