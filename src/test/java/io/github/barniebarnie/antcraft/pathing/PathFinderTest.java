package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
	void walksUpAStairBlockFromItsLowSideWithoutJumping() {
		assertEquals(PathFinder.WALK_COST, stepOntoStairFacing(Direction.EAST), 1e-9);
	}

	@Test
	void jumpsOntoAStairBlockFromItsTallBack() {
		assertEquals(PathFinder.WALK_COST + PathFinder.JUMP_COST, stepOntoStairFacing(Direction.WEST), 1e-9);
	}

	@Test
	void jumpsOntoAStairBlockFromItsSide() {
		assertEquals(PathFinder.WALK_COST + PathFinder.JUMP_COST, stepOntoStairFacing(Direction.NORTH), 1e-9);
	}

	/** The cost of stepping east from the start onto a stair block facing {@code facing}. */
	private static double stepOntoStairFacing(Direction facing) {
		GridWorld world = GridWorld.flat(10).stair(1, 1, 0, facing);
		Path path = find(world, new BlockPos(1, 2, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.STEP_UP), types(path));
		return path.cost();
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

	/** A ledge at y=0 for x<=0 (feet at y=1) over water a block deep at x 1..5, its surface {@code fall} blocks down, and a bank at x=6. */
	private static GridWorld ledgeOverWater(int fall) {
		int surface = 1 - fall;
		return new GridWorld().fill(-5, 0, -5, 0, 0, 5, '#')
			.fill(1, surface - 1, -5, 6, surface - 1, 5, '#')
			.fill(1, surface, -5, 5, surface, 5, 'W')
			.fill(6, surface, -5, 6, surface, 5, '#');
	}

	@Test
	void dropsFartherThanMaxDropIntoWater() {
		// Water breaks the fall, so a drop into it may go past maxDrop (3 here).
		Path path = find(ledgeOverWater(PathFinder.MAX_WATER_DROP), new BlockPos(6, 2 - PathFinder.MAX_WATER_DROP, 0), WALK_ONLY);

		assertTrue(path.complete(), "did not drop into the water: " + path.steps());
		assertEquals(new Step(MoveType.SWIM, new BlockPos(1, 1 - PathFinder.MAX_WATER_DROP, 0), List.of(), List.of()), path.steps().getFirst());
	}

	@Test
	void neverDropsIntoWaterFartherThanMaxWaterDrop() {
		int fall = PathFinder.MAX_WATER_DROP + 1;
		Path path = find(ledgeOverWater(fall), new BlockPos(6, 2 - fall, 0), WALK_ONLY);

		assertFalse(path.complete(), "dropped too far: " + path.steps());
	}

	@Test
	void swimsUpAWaterfallToTheTopOfACliff() {
		// A cliff 4 high at x>=3 with a waterfall down its face at x=2, z=0.
		GridWorld world = GridWorld.flat(10).fill(3, 1, -10, 10, 4, 10, '#').fill(2, 1, 0, 2, 4, 0, 'W');
		Path path = find(world, new BlockPos(4, 5, 0), WALK_ONLY);

		assertTrue(path.complete(), "cannot climb the waterfall: " + path.steps());
		assertEquals(List.of(new BlockPos(1, 1, 0), new BlockPos(2, 1, 0), new BlockPos(2, 2, 0), new BlockPos(2, 3, 0), new BlockPos(2, 4, 0),
			new BlockPos(3, 5, 0), new BlockPos(4, 5, 0)), positions(path));
	}

	@Test
	void swimmingUpABlockCostsTheTicksItTakes() {
		// A water column 4 deep in a pool: the bot at the bottom rises 3 blocks to the surface, then climbs out.
		GridWorld world = GridWorld.flat(10).fill(-1, 1, -10, 10, 4, 10, '#').fill(0, 1, 0, 0, 4, 0, 'W');
		Path path = PathFinder.find(world, START, new Goal.Block(new BlockPos(1, 5, 0)), WALK_ONLY);

		assertTrue(path.complete(), "cannot rise: " + path.steps());
		assertEquals(3 * PathFinder.SWIM_UP_COST + PathFinder.WALK_COST + PathFinder.JUMP_COST, path.cost(), 1e-9);
		// Measured at about 8 ticks a block (see PathingGameTest.swimUpCostMatchesTheTimeARiseTakes).
		assertTrue(PathFinder.SWIM_UP_COST >= 6 && PathFinder.SWIM_UP_COST <= 10, "SWIM_UP_COST " + PathFinder.SWIM_UP_COST);
	}

	@Test
	void takesStairsRatherThanAWaterfallThatIsSlowerToSwimUp() {
		// The waterfall of swimsUpAWaterfallToTheTopOfACliff, and stairs up the cliff 4 blocks along it. Swimming up the
		// waterfall is a few blocks shorter, but rising 3 blocks in water takes longer than walking round to the stairs.
		GridWorld world = GridWorld.flat(10).fill(3, 1, -10, 10, 4, 10, '#').fill(2, 1, 0, 2, 4, 0, 'W')
			.fill(0, 1, 4, 0, 1, 4, '#').fill(1, 1, 4, 1, 2, 4, '#').fill(2, 1, 4, 2, 3, 4, '#');
		Path path = find(world, new BlockPos(4, 5, 0), WALK_ONLY);

		assertTrue(path.complete(), "no way up: " + path.steps());
		assertFalse(types(path).contains(MoveType.SWIM), "swam up the waterfall: " + path.steps());
	}

	@Test
	void doesNotSwimSidewaysFromOneWaterfallIntoAnother() {
		// Two water columns side by side in bedrock. The bot is at the foot of one capped at the top; only the other
		// leads up to air and a ledge, and getting across means swimming under water.
		GridWorld world = new GridWorld().fill(-2, 0, -3, 6, 7, 4, 'B')
			.fill(2, 1, 0, 2, 4, 1, 'W')
			.fill(2, 5, 1, 3, 6, 1, ' ');
		Path path = PathFinder.find(world, new BlockPos(2, 1, 0), new Goal.Block(new BlockPos(3, 5, 1)), WALK_ONLY);

		assertFalse(path.complete(), "swam under water from one waterfall to the other: " + path.steps());
	}

	@Test
	void pillarsUpToHighGoal() {
		GridWorld world = GridWorld.flat(5);
		Path path = find(world, new BlockPos(0, 4, 0), FULL);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.PILLAR, MoveType.PILLAR, MoveType.PILLAR), types(path));
	}

	@Test
	void climbsALadderRatherThanPillarBesideIt() {
		// A wall three high with a ladder on its west face; the goal is on top of the wall.
		GridWorld world = GridWorld.flat(5).fill(2, 1, -5, 2, 3, 5, '#').fill(1, 1, 0, 1, 3, 0, 'H');
		Path path = find(world, new BlockPos(2, 4, 0), FULL);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.WALK, MoveType.CLIMB_UP, MoveType.CLIMB_UP, MoveType.STEP_UP), types(path));
		assertTrue(path.steps().stream().allMatch(s -> s.places().isEmpty() && s.breaks().isEmpty()), "built or dug: " + path.steps());
	}

	@Test
	void climbsALadderUpThroughAHatch() {
		// A bedrock ceiling at y=4 with a trapdoor in it over a ladder.
		GridWorld world = GridWorld.flat(5).fill(-5, 4, -5, 5, 4, 5, 'B').fill(1, 1, 0, 1, 3, 0, 'H').set(1, 4, 0, 'T');
		Path path = find(world, new BlockPos(2, 5, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertEquals(List.of(MoveType.WALK, MoveType.CLIMB_UP, MoveType.CLIMB_UP, MoveType.CLIMB_UP, MoveType.STEP_UP), types(path));
	}

	@Test
	void climbsALadderDownThroughAHatch() {
		GridWorld world = GridWorld.flat(5).fill(-5, 4, -5, 5, 4, 5, 'B').fill(1, 1, 0, 1, 3, 0, 'H').set(1, 4, 0, 'T');
		Path path = PathFinder.find(world, new BlockPos(2, 5, 0), new Goal.Block(START), WALK_ONLY);

		assertTrue(path.complete());
		// Down through the hatch and the ladder, then off it the last block or two.
		assertEquals(List.of(MoveType.WALK, MoveType.CLIMB_DOWN, MoveType.CLIMB_DOWN, MoveType.CLIMB_DOWN), types(path).subList(0, 4));
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

	/** Stone ground up to y=1 (feet at y=2) with a pool of {@code depth} water whose surface is at y=1, x and z from -2 to 2. */
	private static GridWorld pond(int depth) {
		return new GridWorld().fill(-10, -depth, -10, 10, 1, 10, '#').fill(-2, 2 - depth, -2, 2, 1, 2, 'W');
	}

	private static List<BlockPos> positions(Path path) {
		return path.steps().stream().map(Step::to).toList();
	}

	@Test
	void swimsToTheShoreFromTheMiddleOfAPoolWithoutScaffold() {
		GridWorld world = pond(2).forbidAllPlacing();
		Path path = PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(5, 2, 0)), new PathFinder.Options(true, false, 10_000, 3));

		assertTrue(path.complete(), "no way out of the pool: " + path.steps());
		assertTrue(types(path).contains(MoveType.SWIM));
		assertTrue(path.steps().stream().allMatch(s -> s.places().isEmpty() && s.breaks().isEmpty()));
	}

	@Test
	void neverDivesBelowTheSurface() {
		GridWorld world = pond(2);
		Path path = PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(5, 2, 0)), WALK_ONLY);

		assertTrue(positions(path).stream().noneMatch(p -> p.getY() < 1), "dived: " + path.steps());
	}

	@Test
	void risesToTheSurfaceWhenUnderwater() {
		GridWorld world = pond(2);
		Path path = PathFinder.find(world, new BlockPos(0, 0, 0), new Goal.Block(new BlockPos(5, 2, 0)), WALK_ONLY);

		assertTrue(path.complete(), "no way up: " + path.steps());
		assertEquals(new BlockPos(0, 1, 0), path.steps().getFirst().to());
	}

	@Test
	void doesNotSwimUnderABlockWithNoAirAbove() {
		// A bedrock wall across the floor with water under it: crossing means diving.
		GridWorld world = GridWorld.flat(10).fill(2, 2, -10, 2, 4, 10, 'B').fill(2, 1, -10, 2, 1, 10, 'W');
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertFalse(path.complete(), "dived under the wall: " + path.steps());
	}

	@Test
	void entersWaterFromAHigherBankAndClimbsOutOnTheOtherSide() {
		// A moat 2 wide and 2 deep across the whole ground, its surface a block below the banks.
		GridWorld world = new GridWorld().fill(-10, -1, -10, 10, 1, 10, '#').fill(2, 0, -10, 3, 1, 10, 'W');
		Path path = PathFinder.find(world, new BlockPos(0, 2, 0), new Goal.Block(new BlockPos(5, 2, 0)), WALK_ONLY);

		assertTrue(path.complete(), "cannot cross the moat: " + path.steps());
		assertTrue(types(path).contains(MoveType.SWIM));
	}

	@Test
	void crossesAShallowWaterStripWhenThereIsNoWayRound() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 1, 10, 'W');
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertTrue(path.complete(), "cannot cross the strip: " + path.steps());
		assertEquals(List.of(MoveType.WALK, MoveType.SWIM, MoveType.WALK, MoveType.WALK), types(path));
	}

	@Test
	void walksRoundWaterRatherThanSwimmingWhenTheDetourIsShort() {
		GridWorld world = GridWorld.flat(10).set(2, 1, 0, 'W');
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertTrue(path.complete());
		assertFalse(types(path).contains(MoveType.SWIM), "swam: " + path.steps());
	}

	@Test
	void neverGoesThroughLava() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 1, 10, 'L');
		Path path = find(world, new BlockPos(4, 1, 0), new PathFinder.Options(true, false, 10_000, 3));

		assertFalse(path.complete(), "crossed lava: " + path.steps());
		assertTrue(positions(path).stream().noneMatch(p -> world.at(p) == 'L'));
	}

	@Test
	void leavesTheWaterEvenWhenTheGoalIsInReachFromIt() {
		GridWorld world = pond(2);
		Path path = PathFinder.find(world, new BlockPos(0, 1, 0), new Goal.Near(new BlockPos(0, 1, 0), 4), WALK_ONLY);

		assertTrue(path.complete());
		assertFalse(world.isWater(path.end()), "ends in the water: " + path.steps());
	}

	@Test
	void partialPathTowardAGoalInTheWaterStopsOnTheShore() {
		GridWorld world = pond(2);
		Path path = PathFinder.find(world, new BlockPos(6, 2, 0), new Goal.Block(new BlockPos(0, 1, 0)), WALK_ONLY);

		assertFalse(path.complete());
		assertFalse(world.isWater(path.end()), "ends in the water: " + path.steps());
		assertEquals(new BlockPos(3, 2, 0), path.end());
	}

	@Test
	void anAfloatGoalMayEndSwimmingWhenTheBlockIsOutOfReachFromTheBank() {
		GridWorld world = pond(2).forbidAllPlacing();
		// The bottom of the middle of the pool, out of reach from every spot on the bank.
		Goal.Reach bottom = new Goal.Reach(new BlockPos(0, 0, 0), 4.0);
		PathFinder.Options walkOnly = new PathFinder.Options(false, false, 10_000, 3);

		assertFalse(PathFinder.find(world, new BlockPos(5, 2, 0), bottom, walkOnly).complete(), "reached it from the bank");
		Path path = PathFinder.find(world, new BlockPos(5, 2, 0), new Goal.Afloat(bottom), walkOnly);
		assertTrue(path.complete(), "no way to it: " + path.steps());
		assertTrue(world.isWater(path.end()) && !world.isWater(path.end().above()), "not afloat at the surface: " + path.steps());
	}

	@Test
	void anAfloatGoalSwimsOutRatherThanDigIntoTheBank() {
		// Out of reach from the dirt bank, but not from a spot dug down into it, a block from the water.
		GridWorld world = new GridWorld().fill(-10, -3, -10, 10, 1, 10, 'D').fill(-2, -1, -2, 2, 1, 2, 'W').forbidAllPlacing();
		Goal.Reach bottom = new Goal.Reach(new BlockPos(0, -1, 0), 5.0);
		PathFinder.Options digging = new PathFinder.Options(true, false, 10_000, 3);

		// Digging down into the bank beside the pool is quicker than swimming out.
		assertTrue(PathFinder.find(world, new BlockPos(5, 2, 0), bottom, digging).steps().stream().anyMatch(s -> !s.breaks().isEmpty()));
		Path path = PathFinder.find(world, new BlockPos(5, 2, 0), new Goal.Afloat(bottom), digging);
		assertTrue(path.complete(), "no way to it: " + path.steps());
		assertTrue(path.steps().stream().allMatch(s -> s.breaks().isEmpty()), "dug: " + path.steps());
	}

	@Test
	void anAfloatGoalDigsInWhereThereIsNoWaterToSwimIn() {
		// A pool under a stone lid as high as the dirt bank: its bottom is out of reach from the lid and the bank,
		// but not from a spot dug down into the bank beside the lid.
		GridWorld world = new GridWorld().fill(-10, -3, -10, 10, 1, 10, 'D').fill(-2, -1, -2, 2, 0, 2, 'W').fill(-2, 1, -2, 2, 1, 2, '#')
			.forbidAllPlacing();
		Goal.Reach bottom = new Goal.Reach(new BlockPos(1, -1, 0), 4.0);
		Path path = PathFinder.find(world, new BlockPos(5, 2, 0), new Goal.Afloat(bottom), new PathFinder.Options(true, false, 10_000, 3));

		assertTrue(path.complete(), "no way to it: " + path.steps());
		assertTrue(path.steps().stream().anyMatch(s -> !s.breaks().isEmpty()), "did not dig: " + path.steps());
	}

	@Test
	void walksThroughADoorInAWallWithoutBreakingIt() {
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 3, 10, 'B').door(2, 1, 0, Direction.Axis.X);
		Path path = find(world, new BlockPos(4, 1, 0), FULL);

		assertTrue(path.complete(), "no way through: " + path.steps());
		assertEquals(4, path.steps().size(), "went round: " + path.steps());
		assertTrue(path.steps().stream().allMatch(s -> s.breaks().isEmpty() && s.places().isEmpty()), "dug or built: " + path.steps());
	}

	@Test
	void stepsUpThroughADoorOneBlockHigher() {
		// A wall at x=2 on a step one block up, with a shut door in it at the step's height.
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 10, 1, 10, 'B').fill(2, 2, -10, 2, 4, 10, 'B').door(2, 2, 0, Direction.Axis.X);
		Path path = find(world, new BlockPos(4, 2, 0), WALK_ONLY);

		assertTrue(path.complete(), "no way up through: " + path.steps());
		assertEquals(MoveType.STEP_UP, path.steps().get(1).type(), "did not step up into the doorway: " + path.steps());
	}

	@Test
	void dropsIntoADoorwayOneBlockLower() {
		// A step one block up at x>=3, and a wall at x=2 with a shut door in it on the floor and a hole above the door.
		GridWorld world = GridWorld.flat(10).fill(3, 1, -10, 10, 1, 10, 'B').fill(2, 1, -10, 2, 4, 10, 'B')
			.door(2, 1, 0, Direction.Axis.X).set(2, 3, 0, ' ');
		Path path = PathFinder.find(world, new BlockPos(4, 2, 0), new Goal.Block(START), WALK_ONLY);

		assertTrue(path.complete(), "no way down through: " + path.steps());
		assertEquals(MoveType.DROP, path.steps().get(1).type(), "did not drop into the doorway: " + path.steps());
	}

	@Test
	void doesNotEnterADoorFromTheSide() {
		// A door in a wall running along x, walked through north-south: from the east it is a wall.
		GridWorld world = GridWorld.flat(10).fill(2, 1, -10, 2, 3, 10, 'B').door(2, 1, 0, Direction.Axis.Z);
		Path path = find(world, new BlockPos(4, 1, 0), WALK_ONLY);

		assertFalse(path.complete(), "went through: " + path.steps());
	}

	@Test
	void placesNoBlockTouchingItsSupportOnlyDiagonally() {
		// Standing on one block in the air, the step up east would place at (1,1,0), which only touches that block at an edge.
		GridWorld world = new GridWorld().set(0, 0, 0, '#');
		Path path = find(world, new BlockPos(1, 2, 0), FULL);

		assertTrue(path.complete(), "no way up: " + path.steps());
		assertEveryPlacementHasAFace(world, path);
	}

	@Test
	void placesNoBlockAgainstOneItBreaksFirst() {
		// The only face (1,1,0) could be placed against is the block the step up east breaks to stand in.
		GridWorld world = new GridWorld().set(0, 0, 0, '#').set(0, -1, 0, 'B').set(1, 2, 0, 'D');
		Path path = find(world, new BlockPos(1, 2, 0), FULL);

		assertTrue(path.complete(), "no way up: " + path.steps());
		assertEveryPlacementHasAFace(world, path);
	}

	@Test
	void aPathEndsWhereTheBotStandsNotHalfwayDownALadder() {
		// A ladder down through a hatch to a chamber at y=1..2, the block to reach beside it. The block is in reach from
		// the ladder at y=3 already, but a bot only arrives on its feet, so the path goes on down to the floor.
		GridWorld world = GridWorld.flat(5).fill(-5, 1, -5, 5, 4, 5, '#').fill(1, 1, 0, 1, 3, 0, 'H').set(1, 4, 0, 'T')
			.fill(2, 1, 0, 2, 2, 0, ' ');
		Path path = PathFinder.find(world, new BlockPos(2, 5, 0), new Goal.Reach(new BlockPos(3, 1, 0), 4.0), WALK_ONLY);

		assertTrue(path.complete(), "no way down: " + path.steps());
		assertTrue(world.canStandOn(path.end().below()), "ends at " + path.end() + ", not standing: " + path.steps());
	}

	/** A tunnel at y=1..2 along z=0 from x=0 to x=6 in dirt, its last cell (x=6) still dirt; the area is the tunnel's cells. */
	private static final Predicate<BlockPos> TUNNEL = pos -> pos.getZ() == 0 && pos.getX() >= 0 && pos.getX() <= 6
		&& pos.getY() >= 1 && pos.getY() <= 2;

	/** Dirt from y=1 up to {@code groundTop} round {@link #TUNNEL}. */
	private static GridWorld tunnel(int groundTop) {
		return GridWorld.flat(10).fill(-10, 1, -10, 10, groundTop, 10, 'D').fill(0, 1, 0, 5, 2, 0, ' ');
	}

	@Test
	void aGoalWithinAnAreaBreaksOnlyBlocksOfTheAreaFromInsideIt() {
		// A roof over the tunnel but at its west end, so the way in is a drop at x=0. Standing on the roof right above
		// the dirt cell, digging straight down is cheaper than going round, but breaks the roof.
		GridWorld world = tunnel(3).set(0, 3, 0, ' ');
		Goal.Within goal = new Goal.Within(new Goal.Block(new BlockPos(6, 1, 0)), TUNNEL);
		BlockPos start = new BlockPos(6, 4, 0);
		assertTrue(PathFinder.find(world, start, goal.goal(), FULL).steps().stream().anyMatch(step -> step.breaks().stream().anyMatch(TUNNEL.negate())),
			"without the area the path breaks nothing outside it either");

		Path path = PathFinder.find(world, start, goal, FULL);

		assertTrue(path.complete(), "no way in: " + path.steps());
		assertBreaksOnlyFromWithin(start, path, TUNNEL);
	}

	@Test
	void aGoalWithinAnAreaDoesNotDigIntoItFromAbove() {
		// No roof: the dirt cell's top is open to the sky, so digging straight down breaks only cells of the area, but
		// from outside it.
		GridWorld world = tunnel(2);
		Goal.Within goal = new Goal.Within(new Goal.Block(new BlockPos(6, 1, 0)), TUNNEL);
		BlockPos start = new BlockPos(6, 3, 0);
		assertTrue(PathFinder.find(world, start, goal.goal(), FULL).steps().getFirst().type() == MoveType.DIG_DOWN,
			"without the area the path does not dig down either");

		Path path = PathFinder.find(world, start, goal, FULL);

		assertTrue(path.complete(), "no way in: " + path.steps());
		assertBreaksOnlyFromWithin(start, path, TUNNEL);
	}

	@Test
	void aGoalWithinAnAreaIsReachedOnlyStandingInIt() {
		Goal goal = new Goal.Within(new Goal.Near(new BlockPos(6, 1, 0), 2), TUNNEL);

		assertTrue(goal.isReached(new BlockPos(5, 1, 0)));
		assertFalse(goal.isReached(new BlockPos(6, 3, 0)), "reached from above the area");
	}

	/** Every block the path breaks is in the area, broken from a spot in it. */
	private static void assertBreaksOnlyFromWithin(BlockPos start, Path path, Predicate<BlockPos> area) {
		BlockPos from = start;
		for (Step step : path.steps()) {
			for (BlockPos pos : step.breaks()) {
				assertTrue(area.test(pos), "broke " + pos + " outside the area: " + path.steps());
				assertTrue(area.test(from), "broke " + pos + " from " + from + ", outside the area: " + path.steps());
			}
			from = step.to();
		}
	}

	/** Each block the path places has a face to place it against: a block of the world it has not broken, or one it placed before. */
	private static void assertEveryPlacementHasAFace(GridWorld world, Path path) {
		java.util.Set<BlockPos> placed = new java.util.HashSet<>();
		java.util.Set<BlockPos> broken = new java.util.HashSet<>();
		for (Step step : path.steps()) {
			broken.addAll(step.breaks());
			for (BlockPos pos : step.places()) {
				boolean face = false;
				for (Direction dir : Direction.values()) {
					BlockPos n = pos.relative(dir);
					face |= placed.contains(n) || !broken.contains(n) && !world.isPassable(n) && !world.isHazard(n);
				}
				assertTrue(face, "placed " + pos + " against nothing: " + path.steps());
				placed.add(pos);
			}
		}
	}
}
