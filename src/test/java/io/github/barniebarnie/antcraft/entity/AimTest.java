package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Where a bot aims its bow (#707): high enough that the arrow's drop under gravity and drag brings it onto the target. */
class AimTest {
	/** The height an arrow shot at {@code pitch} radians and {@code speed} blocks a tick has when it has come {@code across}. */
	private static double heightAt(double across, double pitch, double speed) {
		double x = 0;
		double y = 0;
		double vx = Math.cos(pitch) * speed;
		double vy = Math.sin(pitch) * speed;
		for (int tick = 0; tick < 200; tick++) {
			double nx = x + vx;
			double ny = y + vy;
			if (nx >= across) {
				return y + (ny - y) * (across - x) / (nx - x);
			}
			x = nx;
			y = ny;
			vx *= Aim.DRAG;
			vy = vy * Aim.DRAG - Aim.GRAVITY;
		}
		return Double.NEGATIVE_INFINITY;
	}

	@Test
	void anArrowAimedAtATargetLandsOnIt() {
		double[][] targets = {{15, 0}, {5, 0}, {20, -3}, {10, 5}, {1, 2}, {25, 1}};
		for (double[] target : targets) {
			Aim.Shot shot = Aim.at(target[0], target[1], 3.0);
			assertNotNull(shot, "no shot at " + target[0] + ", " + target[1]);
			assertEquals(target[1], heightAt(target[0], shot.pitch(), 3.0), 0.05, "shot at " + target[0] + ", " + target[1]);
		}
	}

	@Test
	void aShotOnTheLevelAimsAboveTheTargetAndTakesTicksToGetThere() {
		Aim.Shot near = Aim.at(5, 0, 3.0);
		Aim.Shot far = Aim.at(20, 0, 3.0);
		assertTrue(near.pitch() > 0 && far.pitch() > near.pitch(), near + " " + far);
		// Some 3 blocks a tick, slowing down.
		assertEquals(2, near.ticks());
		assertTrue(far.ticks() >= 7 && far.ticks() <= 8, far.toString());
	}

	@Test
	void aTargetOutOfTheBowsReachHasNoShot() {
		assertNull(Aim.at(200, 0, 3.0));
		// An arrow shot straight up climbs some 60 blocks.
		assertNull(Aim.at(10, 80, 3.0));
	}
}
