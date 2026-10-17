package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PathFinderTest {
	private static final BlockPos START = new BlockPos(0, 1, 0);
	private static final PathFinder.Options WALK_ONLY = new PathFinder.Options(false, false, 10_000, 3);
	private static final PathFinder.Options FULL = new PathFinder.Options(true, true, 10_000, 3);

	private static Path find(GridWorld world, BlockPos goal, PathFinder.Options options) {
		return PathFinder.find(world, START, new Goal.Block(goal), options);
	}

	private static List<MoveType> types(Path path) {
		return path.steps().stream().map(Step::type).toList();
	}

	@Test
	void walksStraightOnFlatGround() {
		Path path = find(GridWorld.flat(10), new BlockPos(5, 1, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertEquals(5, path.steps().size());
		assertTrue(types(path).stream().allMatch(t -> t == MoveType.WALK));
		assertEquals(new BlockPos(5, 1, 0), path.end());
	}

	@Test
	void alreadyAtGoalGivesEmptyCompletePath() {
		Path path = find(GridWorld.flat(5), START, WALK_ONLY);

		assertTrue(path.complete());
		assertTrue(path.steps().isEmpty());
	}

	@Test
	void stepsUpOneBlock() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 10, 1, 10, '#');
		Path path = find(world, new BlockPos(4, 2, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertTrue(types(path).contains(MoveType.STEP_UP));
	}

	@Test
	void cannotClimbTwoBlockWallWithoutTools() {
		// Wall of height 2 across the whole floor.
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 2, 10, '#');
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertFalse(path.complete());
	}

	@Test
	void walksAroundShortWallInsteadOfDigging() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -1, 2, 2, 1, '#');
		Path path = find(world, new BlockPos(4, 1, 0), FULL);

		assertTrue(path.complete());
		assertTrue(path.steps().stream().allMatch(s -> s.breaks().isEmpty()), "should not dig");
	}

	@Test
	void digsThroughWallWhenNoWayAround() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 2, 10, '#');
		Path path = find(world, new BlockPos(4, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertTrue(path.complete());
		assertTrue(path.steps().stream().anyMatch(s -> !s.breaks().isEmpty()), "must break through the wall");
	}

	@Test
	void neverBreaksBedrock() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 3, 10, 'B');
		Path path = find(world, new BlockPos(4, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertFalse(path.complete());
	}

	@Test
	void doesNotDigNextToLava() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 2, 10, '#').fill(3, 1, -10, 3, 2, 10, 'L');
		// The only way through the wall exposes lava, and lava itself is never entered.
		Path path = find(world, new BlockPos(5, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertFalse(path.complete());
	}

	@Test
	void bridgesGapWhenPlacingAllowed() {
		// Floor with a 2-wide gap down to y=-20 at x=2..3.
		GridWorld world = GridWorld.flat(10).fill(2, 0, -10, 3, 0, 10, ' ');
		Path path = find(world, new BlockPos(5, 1, 0), FULL);

		assertTrue(path.complete());
		List<BlockPos> placed = path.steps().stream().flatMap(s -> s.places().stream()).toList();
		assertEquals(List.of(new BlockPos(2, 0, 0), new BlockPos(3, 0, 0)), placed);
	}

	@Test
	void cannotCrossGapWithoutPlacing() {
		GridWorld world = GridWorld.flat(10).fill(2, 0, -10, 3, 0, 10, ' ');
		Path path = find(world, new BlockPos(5, 1, 0), WALK_ONLY);

		assertFalse(path.complete());
	}

	@Test
	void dropsUpToMaxDropButNoFurther() {
		// Ledge at y=0 for x<=0; lower floor 3 down, and an even lower floor 4 down.
		GridWorld three = new GridWorld().fill(-5, 0, -5, 0, 0, 5, '#').fill(1, -3, -5, 5, -3, 5, '#');
		Path ok = find(three, new BlockPos(3, -2, 0), WALK_ONLY);
		assertTrue(ok.complete());
		assertTrue(types(ok).contains(MoveType.DROP));

		GridWorld four = new GridWorld().fill(-5, 0, -5, 0, 0, 5, '#').fill(1, -4, -5, 5, -4, 5, '#');
		Path tooFar = find(four, new BlockPos(3, -3, 0), WALK_ONLY);
		assertFalse(tooFar.complete());
	}

	@Test
	void pillarsUpToHighGoal() {
		GridWorld world = GridWorld.flat(5);
		Path path = find(world, new BlockPos(0, 4, 0), FULL);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.PILLAR, MoveType.PILLAR, MoveType.PILLAR), types(path));
	}

	@Test
	void digsDownToLowerGoal() {
		GridWorld world = GridWorld.flat(5).fill(-5, -5, -5, 5, -1, 5, 'D');
		Path path = find(world, new BlockPos(0, -2, 0), FULL);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.DIG_DOWN, MoveType.DIG_DOWN, MoveType.DIG_DOWN), types(path));
	}

	@Test
	void reachesNearGoal() {
		Path path = PathFinder.find(GridWorld.flat(10), START, new Goal.Near(new BlockPos(6, 1, 0), 2), WALK_ONLY);

		assertTrue(path.complete());
		assertTrue(path.end().distManhattan(new BlockPos(6, 1, 0)) <= 2);
	}

	@Test
	void givesUpAfterNodeLimitWithPartialPathTowardGoal() {
		GridWorld world = GridWorld.flat(100);
		Path path = PathFinder.find(world, START, new Goal.Block(new BlockPos(90, 1, 0)), new PathFinder.Options(false, false, 30, 3));

		assertFalse(path.complete());
		assertTrue(path.end().getX() > 0, "partial path should head toward the goal");
	}

	@Test
	void pillarsElsewhereWhenPlacingIsForbiddenAtTheStart() {
		GridWorld world = GridWorld.flat(10).forbidPlacing(START);
		Path path = find(world, new BlockPos(0, 3, 0), FULL);

		assertTrue(path.complete());
		assertTrue(path.steps().stream().noneMatch(s -> s.places().contains(START)), "placed at a forbidden spot: " + path.steps());
	}

	@Test
	void cannotClimbWhenPlacingIsForbiddenEverywhere() {
		GridWorld world = GridWorld.flat(10).forbidAllPlacing();
		Path path = find(world, new BlockPos(0, 3, 0), FULL);

		assertFalse(path.complete());
	}
}
