package io.github.barniebarnie.antcraft.entity;

import org.jspecify.annotations.Nullable;

/**
 * Where a bot aims its bow (docs/reference/combat/shoot.md): the pitch at which an arrow, slowed by the air and pulled
 * down by gravity as the game's are each tick, comes onto a target {@code across} blocks away and {@code rise} blocks
 * higher. The low shot, found by bisection over a simulated flight.
 */
final class Aim {
	/** What an arrow keeps of its speed each tick in the air (AbstractArrow's inertia). */
	static final double DRAG = 0.99;
	/** What an arrow falls faster each tick (AbstractArrow's default gravity). */
	static final double GRAVITY = 0.05;
	/** The longest flight it plans, in ticks. */
	private static final int MAX_TICKS = 100;
	/** The highest pitch it tries, unless the target is steeper: about the furthest-reaching one, above which shots only lob. */
	private static final double HIGHEST = Math.toRadians(45);

	/** A shot: the pitch in radians, up positive, and the ticks the arrow takes to get there. */
	record Shot(double pitch, int ticks) {
	}

	private Aim() {
	}

	/** The shot at {@code speed} blocks a tick onto a target {@code across} away and {@code rise} up, or null if none reaches it. */
	static @Nullable Shot at(double across, double rise, double speed) {
		double highest = Math.max(HIGHEST, Math.min(Math.atan2(rise, across) + 0.2, Math.toRadians(85)));
		if (height(across, highest, speed)[0] < rise) {
			return null;
		}
		double low = -Math.PI / 2 + 0.01;
		double high = highest;
		for (int i = 0; i < 40; i++) {
			double mid = (low + high) / 2;
			if (height(across, mid, speed)[0] < rise) {
				low = mid;
			} else {
				high = mid;
			}
		}
		return new Shot(high, (int) height(across, high, speed)[1]);
	}

	/** The height an arrow shot at {@code pitch} has once it came {@code across}, and the tick it got there. */
	private static double[] height(double across, double pitch, double speed) {
		double x = 0;
		double y = 0;
		double vx = Math.cos(pitch) * speed;
		double vy = Math.sin(pitch) * speed;
		for (int tick = 1; tick <= MAX_TICKS; tick++) {
			if (x + vx >= across) {
				return new double[] {y + vy * (across - x) / vx, tick};
			}
			x += vx;
			y += vy;
			vx *= DRAG;
			vy = vy * DRAG - GRAVITY;
		}
		return new double[] {Double.NEGATIVE_INFINITY, MAX_TICKS};
	}
}
