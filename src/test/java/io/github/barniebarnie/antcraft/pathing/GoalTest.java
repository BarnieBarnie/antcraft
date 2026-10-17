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
		assertFalse(new Goal.InSight(LOG, 4.5, (feet, target) -> false).isReached(new BlockPos(1, 1, 0)));
	}

	@Test
	void inSightIsMetWithinReachWhenTheBlockCanBeSeen() {
		assertTrue(new Goal.InSight(LOG, 4.5, (feet, target) -> target.equals(LOG)).isReached(new BlockPos(1, 1, 0)));
	}

	@Test
	void inSightIsNotMetOutOfReachEvenWhenTheBlockCanBeSeen() {
		assertFalse(new Goal.InSight(LOG, 4.5, (feet, target) -> true).isReached(new BlockPos(5, 1, 0)));
	}

	@Test
	void pathfinderGoesOnUntilTheBlockIsInSight() {
		// Only seen from x >= 1, say round a corner: the path passes the spots that are merely within reach.
		GridWorld world = GridWorld.flat(10);
		Path path = PathFinder.find(world, new BlockPos(-8, 1, 0), new Goal.InSight(new BlockPos(0, 1, 0), 3.0, (feet, target) -> feet.getX() >= 1),
			new PathFinder.Options(false, false, 10_000, 3));

		assertTrue(path.complete());
		assertTrue(path.end().getX() >= 1 && path.end().getX() <= 3);
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
