package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class GoalTest {
	private static final BlockPos LOG = new BlockPos(0, 4, 0);

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
	void pathfinderStopsWithinReach() {
		GridWorld world = GridWorld.flat(10);
		Path path = PathFinder.find(world, new BlockPos(-8, 1, 0), new Goal.Reach(new BlockPos(0, 1, 0), 3.0),
			new PathFinder.Options(false, false, 10_000, 3));

		assertTrue(path.complete());
		assertTrue(path.end().getX() < 0 && path.end().getX() >= -3);
	}
}
