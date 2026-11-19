package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FishingTest {
	/** The surface water blocks of a pool from {@code (x0, z0)} to {@code (x1, z1)} at y 1. */
	private static List<BlockPos> pool(int x0, int z0, int x1, int z1) {
		List<BlockPos> water = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				water.add(new BlockPos(x, 1, z));
			}
		}
		return water;
	}

	@Test
	void theSpotIsThePoolsMiddleWhereAnOffCastStillLandsInWater() {
		assertEquals(new BlockPos(4, 1, 4), Fishing.spot(pool(3, 3, 5, 5), new BlockPos(0, 2, 4)));
	}

	@Test
	void ofSpotsAsOpenTheNearestWins() {
		List<BlockPos> water = pool(3, 3, 5, 5);
		water.addAll(pool(13, 3, 15, 5));
		assertEquals(new BlockPos(14, 1, 4), Fishing.spot(water, new BlockPos(18, 2, 4)));
	}

	@Test
	void noWaterIsNoSpot() {
		assertNull(Fishing.spot(List.of(), BlockPos.ZERO));
	}

	@Test
	void thePitchCastsTheHookAsFarAsTheSpot() {
		for (double across : new double[] {2, 3, 5, 8}) {
			float pitch = Fishing.pitch(across, 1.62);
			double landed = Fishing.lands(pitch, 1.62);
			assertTrue(Math.abs(landed - across) < 0.8, "pitch " + pitch + " lands at " + landed + ", not " + across);
		}
		// Nearer takes a steeper cast, down positive as an entity's pitch.
		assertTrue(Fishing.pitch(2, 1.62) > Fishing.pitch(5, 1.62));
	}
}
