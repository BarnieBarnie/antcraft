package io.github.barniebarnie.antcraft.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The block search behind {@code scan} and {@code find_nearest}: the blocks within a radius of a center, nearest first
 * (by distance, then y, x and z, so the order is fixed), until {@code limit} matched. It reads a given number of blocks
 * at a time ({@link #step}) and continues where it stopped, so a big search can be spread over ticks. Pure Java: the
 * {@link Matcher} reads the world.
 */
public final class BlockScan {
	/** The largest radius a search may have. */
	public static final int MAX_RADIUS = 32;

	/** Whether the block at a position matches; false where the world is not loaded. */
	@FunctionalInterface
	public interface Matcher {
		boolean matches(int x, int y, int z);
	}

	private final Pos center;
	private final int limit;
	private final Matcher matcher;
	/** How many of {@link Offsets#OFFSETS} lie within the radius. */
	private final int end;
	private final List<Pos> found = new ArrayList<>();
	private int next;

	public BlockScan(Pos center, int radius, int limit, Matcher matcher) {
		if (radius < 0 || radius > MAX_RADIUS) {
			throw new IllegalArgumentException("radius " + radius);
		}
		this.center = center;
		this.limit = limit;
		this.matcher = matcher;
		this.end = Offsets.within(radius);
	}

	/** Reads at most {@code reads} more blocks; true once the search is done. */
	public boolean step(int reads) {
		int stop = (int) Math.min(this.end, (long) this.next + Math.max(0, reads));
		while (!this.done() && this.next < stop) {
			int offset = Offsets.OFFSETS[this.next++];
			int x = (int) this.center.x() + Offsets.dx(offset);
			int y = (int) this.center.y() + Offsets.dy(offset);
			int z = (int) this.center.z() + Offsets.dz(offset);
			if (this.matcher.matches(x, y, z)) {
				this.found.add(new Pos(x, y, z));
			}
		}
		return this.done();
	}

	public boolean done() {
		return this.found.size() >= this.limit || this.next >= this.end;
	}

	/** The matching positions found so far, nearest first. */
	public List<Pos> found() {
		return this.found;
	}

	/** How many blocks were read so far. */
	public int reads() {
		return this.next;
	}

	/** Every offset within {@link #MAX_RADIUS}, in search order; made once, when the first search starts. */
	private static final class Offsets {
		private static final int BIAS = 64;
		static final int[] OFFSETS;
		/** The squared distance of each offset. */
		static final int[] DISTANCES;

		static {
			int r = MAX_RADIUS;
			long[] keys = new long[(2 * r + 1) * (2 * r + 1) * (2 * r + 1)];
			int n = 0;
			for (int dy = -r; dy <= r; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						long d2 = (long) dx * dx + dy * dy + dz * dz;
						if (d2 <= (long) r * r) {
							// Packed so that sorting the keys sorts by distance, then y, x and z.
							keys[n++] = d2 << 24 | (long) (dy + BIAS) << 16 | (long) (dx + BIAS) << 8 | (dz + BIAS);
						}
					}
				}
			}
			keys = Arrays.copyOf(keys, n);
			Arrays.sort(keys);
			OFFSETS = new int[n];
			DISTANCES = new int[n];
			for (int i = 0; i < n; i++) {
				OFFSETS[i] = (int) (keys[i] & 0xFFFFFF);
				DISTANCES[i] = (int) (keys[i] >>> 24);
			}
		}

		/** How many offsets lie within {@code radius}. */
		static int within(int radius) {
			int index = Arrays.binarySearch(DISTANCES, radius * radius + 1);
			if (index < 0) {
				return -index - 1;
			}
			while (index > 0 && DISTANCES[index - 1] == radius * radius + 1) {
				index--;
			}
			return index;
		}

		static int dy(int offset) {
			return (offset >> 16 & 0xFF) - BIAS;
		}

		static int dx(int offset) {
			return (offset >> 8 & 0xFF) - BIAS;
		}

		static int dz(int offset) {
			return (offset & 0xFF) - BIAS;
		}
	}
}
