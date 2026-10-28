package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Where a bot's reflexes take it (docs/scripting.md, Reflexes): a step aside, and the way up to air. */
class EscapeTest {
	private static final BlockPos FEET = new BlockPos(0, 1, 0);

	@Test
	void stepsAsideToTheFirstOpenBlockBesideIt() {
		GridWorld world = GridWorld.flat(4);
		assertEquals(Optional.of(FEET.north()), Escape.stepAside(world, FEET, pos -> false));
	}

	@Test
	void stepsAsideAwayFromWhatItAvoids() {
		GridWorld world = GridWorld.flat(4);
		assertEquals(Optional.of(FEET.east()), Escape.stepAside(world, FEET, pos -> pos.getZ() < 0));
	}

	@Test
	void doesNotStepIntoANetherPortal() {
		// A portal to the north, where it would step first (#351).
		GridWorld world = GridWorld.flat(4).fill(0, 1, -1, 0, 2, -1, 'P');
		assertEquals(Optional.of(FEET.east()), Escape.stepAside(world, FEET, pos -> false));
	}

	@Test
	void doesNotStepIntoAWallOrOntoLavaOrOverAHole() {
		GridWorld world = GridWorld.flat(4)
			.fill(0, 1, -1, 0, 2, -1, '#') // a wall to the north
			.set(1, 0, 0, 'L') // lava in the floor to the east
			.set(0, 0, 1, ' '); // a hole to the south
		assertEquals(Optional.of(FEET.west()), Escape.stepAside(world, FEET, pos -> false));
	}

	@Test
	void stepsUpOrDownABlock() {
		// Walled in where it stands, but the wall to the east is a block high.
		GridWorld up = GridWorld.flat(4).fill(-1, 1, -1, 1, 2, 1, '#').set(0, 1, 0, ' ').set(0, 2, 0, ' ').set(1, 2, 0, ' ');
		assertEquals(Optional.of(new BlockPos(1, 2, 0)), Escape.stepAside(up, FEET, pos -> false));
		// Two blocks up is too high.
		up.set(1, 2, 0, '#').set(1, 3, 0, ' ');
		assertEquals(Optional.empty(), Escape.stepAside(up, FEET, pos -> false));
		// To the east the floor is a block lower.
		GridWorld down = GridWorld.flat(4).fill(-1, 1, -1, 1, 2, 1, '#').set(0, 1, 0, ' ').set(0, 2, 0, ' ')
			.set(1, 1, 0, ' ').set(1, 2, 0, ' ').set(1, 0, 0, ' ').set(1, -1, 0, '#');
		assertEquals(Optional.of(new BlockPos(1, 0, 0)), Escape.stepAside(down, FEET, pos -> false));
	}

	@Test
	void doesNotCutACornerBetweenTwoWalls() {
		GridWorld world = GridWorld.flat(4).fill(-1, 1, -1, 1, 2, 1, '#').set(0, 1, 0, ' ').set(0, 2, 0, ' ')
			.set(1, 1, 1, ' ').set(1, 2, 1, ' ');
		assertEquals(Optional.empty(), Escape.stepAside(world, FEET, pos -> false));
		world.set(1, 1, 0, ' ').set(1, 2, 0, ' ');
		assertEquals(Optional.of(new BlockPos(1, 1, 0)), Escape.stepAside(world, FEET, pos -> false));
		assertEquals(Optional.of(new BlockPos(1, 1, 1)), Escape.stepAside(world, FEET, pos -> pos.getZ() == 0));
	}

	/** In the middle of a 5x5 patch of fire: it walks out through the fire, a block at a time, to the nearest safe block. */
	@Test
	void walksOutOfAWidePatchOfFire() {
		GridWorld world = GridWorld.flat(8).fill(-2, 1, -2, 2, 1, 2, 'F');
		BlockPos at = FEET;
		for (int steps = 0; steps < 3; steps++) {
			Optional<BlockPos> step = Escape.stepAside(world, at, pos -> false);
			assertTrue(step.isPresent(), "no step from " + at);
			assertTrue(at.distManhattan(step.get()) <= 2, "step " + step.get() + " from " + at);
			at = step.get();
		}
		assertEquals(new BlockPos(0, 1, -3), at);
	}

	@Test
	void noStepAsideWhenTheFireReachesFurtherThanItLooks() {
		GridWorld world = GridWorld.flat(16).fill(-10, 1, -10, 10, 1, 10, 'F');
		assertEquals(Optional.empty(), Escape.stepAside(world, FEET, pos -> false));
	}

	/** Under a stone ceiling in water two deep, with air above the water three blocks east. */
	@Test
	void swimsUnderTheCeilingToTheNearestAir() {
		GridWorld world = tunnel().set(3, 3, 0, ' ');
		List<BlockPos> way = Escape.wayToAir(world, FEET);
		assertEquals(new BlockPos(3, 2, 0), way.getLast(), "way " + way);
		BlockPos at = FEET;
		for (BlockPos step : way) {
			assertEquals(1, at.distManhattan(step), "way " + way);
			at = step;
		}
		assertEquals(4, way.size(), "way " + way);
	}

	@Test
	void noWayToAirWhenTheWaterIsShutIn() {
		assertTrue(Escape.wayToAir(tunnel(), FEET).isEmpty());
	}

	/** Water two deep and three wide under a stone ceiling, walled in all round. */
	private static GridWorld tunnel() {
		return GridWorld.flat(8).fill(-5, 1, -2, 5, 3, 2, '#').fill(-4, 1, -1, 4, 2, 1, 'W');
	}

	@Test
	void swimsStraightUpInOpenWater() {
		GridWorld world = GridWorld.flat(8).fill(-4, 1, -4, 4, 4, 4, 'W');
		assertEquals(List.of(new BlockPos(0, 2, 0), new BlockPos(0, 3, 0), new BlockPos(0, 4, 0)), Escape.wayToAir(world, FEET));
	}
}
