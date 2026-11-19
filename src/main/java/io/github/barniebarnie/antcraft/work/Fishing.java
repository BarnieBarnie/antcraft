package io.github.barniebarnie.antcraft.work;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Where a fishing bot casts and how (docs/reference/blocks/fish.md): the water block it aims its hook at, and the pitch
 * that lands the hook there, as the rod throws it (FishingHook's cast from a player).
 */
public final class Fishing {
	/** The steepest pitch it casts at, in degrees down: steeper drops the hook at its feet. */
	private static final int STEEPEST = 80;
	/** The flattest, in degrees up: flatter only throws further than any spot it walks to. */
	private static final int FLATTEST = -20;
	/** How far ahead of the eyes the hook starts, in blocks. */
	private static final double AHEAD = 0.3;
	/** How much faster the hook falls each tick (FishingHook's gravity). */
	private static final double GRAVITY = 0.03;
	/** What the hook keeps of its speed each tick. */
	private static final double DRAG = 0.92;

	private Fishing() {
	}

	/**
	 * The water block of {@code water} (open surface water: air over it) to cast at from {@code from}: the one with the
	 * most of {@code water} around it, so a cast a little off still lands in water, the nearest of those; null for none.
	 */
	public static @Nullable BlockPos spot(List<BlockPos> water, BlockPos from) {
		Set<BlockPos> all = new HashSet<>(water);
		return water.stream()
			.max(Comparator.<BlockPos>comparingInt(pos -> around(all, pos)).thenComparing(pos -> -pos.distSqr(from)))
			.orElse(null);
	}

	private static int around(Set<BlockPos> water, BlockPos pos) {
		int n = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if ((dx != 0 || dz != 0) && water.contains(pos.offset(dx, 0, dz))) {
					n++;
				}
			}
		}
		return n;
	}

	/**
	 * The pitch, in degrees down as an entity's, that lands a hook cast from the eyes {@code across} blocks away and
	 * {@code drop} blocks below them ({@link #lands}), to the whole degree.
	 */
	public static float pitch(double across, double drop) {
		int best = STEEPEST;
		for (int pitch = FLATTEST; pitch <= STEEPEST; pitch++) {
			if (Math.abs(lands(pitch, drop) - across) < Math.abs(lands(best, drop) - across)) {
				best = pitch;
			}
		}
		return best;
	}

	/**
	 * How far across a hook cast at {@code pitch} (degrees down) lands, once it is {@code drop} blocks below the eyes: the
	 * rod throws it at 0.6 blocks a tick and half a block more per axis (FishingHook's constructor, without its spread).
	 */
	static double lands(double pitch, double drop) {
		double tan = Math.clamp(Math.tan(Math.toRadians(pitch)), -5, 5);
		double speed = 0.6 / Math.sqrt(1 + tan * tan) + 0.5;
		double vx = speed;
		double vy = -tan * speed;
		double x = AHEAD;
		double y = 0;
		for (int tick = 0; tick < 200 && y > -drop; tick++) {
			vy -= GRAVITY;
			x += vx;
			y += vy;
			vx *= DRAG;
			vy *= DRAG;
		}
		return x;
	}
}
