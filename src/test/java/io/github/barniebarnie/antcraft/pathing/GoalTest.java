package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class GoalTest {
	private static final BlockPos LOG = new BlockPos(0, 4, 0);

	@Test
	void awayIsMetOnlyFurtherThanItsRangeFromThePosition() {
		Goal away = new Goal.Away(BlockPos.ZERO, 10);
		assertFalse(away.isReached(new BlockPos(6, 0, 8)));
		assertTrue(away.isReached(new BlockPos(7, 0, 8)));
		assertTrue(away.heuristic(new BlockPos(3, 0, 0)) > away.heuristic(new BlockPos(6, 0, 0)));
		assertTrue(away.heuristic(new BlockPos(11, 0, 0)) == 0);
	}

	@Test
	void reachIsMetWhenEyesAreCloseEnough() {
		// Feet at y=1 next to the trunk: eyes at about y=2.6, block centre at y=4.5.
		assertTrue(new Goal.Reach(LOG, 4.5).isReached(new BlockPos(1, 1, 0)));
	}

	@Test
	void reachIsNotMetFromTooFarAway() {
		assertFalse(new Goal.Reach(LOG, 4.5).isReached(new BlockPos(5, 1, 0)));
	}

	@Test
	void reachIsNotMetFromInsideTheBlock() {
		assertFalse(new Goal.Reach(LOG, 4.5).isReached(LOG));
	}

	@Test
	void inSightIsNotMetWithinReachWhenTheBlockCannotBeSeen() {
		assertFalse(new Goal.InSight(LOG, 4.5, (feet, target, broken) -> false).isReached(new BlockPos(1, 1, 0)));
	}

	@Test
	void inSightIsMetWithinReachWhenTheBlockCanBeSeen() {
		assertTrue(new Goal.InSight(LOG, 4.5, (feet, target, broken) -> target.equals(LOG)).isReached(new BlockPos(1, 1, 0)));
	}

	@Test
	void inSightIsNotMetOutOfReachEvenWhenTheBlockCanBeSeen() {
		assertFalse(new Goal.InSight(LOG, 4.5, (feet, target, broken) -> true).isReached(new BlockPos(5, 1, 0)));
	}

	@Test
	void pathfinderGoesOnUntilTheBlockIsInSight() {
		// Only seen from x >= 1, say round a corner: the path passes the spots that are merely within reach.
		GridWorld world = GridWorld.flat(10);
		Path path = PathFinder.find(world, new BlockPos(-8, 1, 0), new Goal.InSight(new BlockPos(0, 1, 0), 3.0, (feet, target, broken) -> feet.getX() >= 1),
			new PathFinder.Options(false, false, 10_000, 3));

		assertTrue(path.complete());
		assertTrue(path.end().getX() >= 1 && path.end().getX() <= 3);
	}

	@Test
	void aPathWhoseOwnBreaksOpenALineOfSightReachesAnInSightGoal() {
		// The target lies under a lid of floor stone, seen only from straight above once the lid is gone, as a pond
		// under a lid is: the path digs down through the lid, and so sees the target.
		GridWorld world = GridWorld.flat(10).set(0, -1, 0, '#');
		BlockPos lid = new BlockPos(0, 0, 0);
		Goal.Sight sight = (feet, target, broken) -> feet.getX() == 0 && feet.getZ() == 0 && (world.isPassable(lid) || broken.test(lid));
		Path path = PathFinder.find(world, new BlockPos(-5, 1, 0), new Goal.InSight(new BlockPos(0, -1, 0), 4.5, sight),
			new PathFinder.Options(true, false, 10_000, 3));

		assertTrue(path.complete());
		assertTrue(path.steps().stream().anyMatch(step -> step.breaks().contains(lid)));
	}

	@Test
	void anAfloatPathDigsThroughALidOverWaterToSeeTheWaterUnderIt() {
		// A pool under a stone lid as high as the dirt bank, filled up to its lid: the water is seen only once a lid
		// block over it or beside that is gone. The path breaks one from on top and drops into the water under it, which
		// floods nothing.
		GridWorld world = new GridWorld().fill(-10, -3, -10, 10, 1, 10, 'D').fill(-2, -1, -2, 2, 0, 2, 'W').fill(-2, 1, -2, 2, 1, 2, '#')
			.forbidAllPlacing();
		BlockPos water = new BlockPos(0, 0, 0);
		Goal.Sight sight = (feet, target, broken) -> broken.test(target.above())
			|| Direction.Plane.HORIZONTAL.stream().anyMatch(side -> broken.test(target.relative(side).above()));
		Path path = PathFinder.find(world, new BlockPos(5, 2, 0), new Goal.Afloat(new Goal.InSight(water, 4.5, sight)),
			new PathFinder.Options(true, false, 10_000, 3));

		assertTrue(path.complete(), "no way to see it: " + path.steps());
		assertTrue(path.steps().stream().anyMatch(step -> step.breaks().stream().anyMatch(pos -> pos.getY() == 1)), "lid not dug: " + path.steps());
		assertTrue(world.isWater(path.end()), "not afloat: " + path.steps());
	}

	@Test
	void pathfinderStopsWithinReach() {
		GridWorld world = GridWorld.flat(10);
		Path path = PathFinder.find(world, new BlockPos(-8, 1, 0), new Goal.Reach(new BlockPos(0, 1, 0), 3.0),
			new PathFinder.Options(false, false, 10_000, 3));

		assertTrue(path.complete());
		assertTrue(path.end().getX() < 0 && path.end().getX() >= -3);
	}
}
