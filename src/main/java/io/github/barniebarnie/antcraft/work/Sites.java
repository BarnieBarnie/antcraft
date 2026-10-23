package io.github.barniebarnie.antcraft.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Finding room near the core for a colony area, such as a field or storage. */
public final class Sites {
	/** How far below and above the core's level an area may lie. */
	private static final int HEIGHT_RANGE = 2;

	private Sites() {
	}

	/**
	 * The site nearest the core, searching columns within {@code reach} of it and levels around the
	 * core's, whose area is clear of the core and the {@code avoid} areas and that {@code fits}.
	 *
	 * @param at the site for a block column and level, both at the core's height or offset from it
	 */
	static <T> Optional<T> nearest(BlockPos core, int reach, List<BoundingBox> avoid, Function<BlockPos, T> at,
			Function<T, BoundingBox> area, Predicate<T> fits) {
		// ponytail: scans up to the whole territory when there is no room; Colony.findSite skips the next few searches after that.
		for (BlockPos column : columns(core, reach)) {
			for (int dy = 0; dy <= HEIGHT_RANGE; dy = dy > 0 ? -dy : -dy + 1) {
				T site = at.apply(column.offset(0, dy, 0));
				if (isClear(core, avoid, area.apply(site)) && fits.test(site)) {
					return Optional.of(site);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * The box of {@code size} nearest the core, its lowest corner within {@code reach} of it and up to two blocks
	 * above or below the core's level, clear of the core and the {@code avoid} areas, all of whose blocks {@code fit}.
	 */
	public static Optional<BoundingBox> box(BlockPos core, int reach, List<BoundingBox> avoid, Vec3i size, Predicate<BlockPos> fits) {
		return nearest(core, reach, avoid, pos -> BoundingBox.fromCorners(pos, pos.offset(size).offset(-1, -1, -1)), box -> box,
			box -> BlockPos.betweenClosedStream(box).allMatch(fits));
	}

	/** How wide each ring of columns {@link #cheapest} searches is: a site in a farther ring is taken only if a nearer ring has none. */
	private static final int RING = 8;

	/**
	 * The site at the core's level, among the columns within {@code reach} of it, clear of the core
	 * and the {@code avoid} areas, with the lowest {@code cost} in the nearest ring of {@link #RING}
	 * blocks that has one, the nearest of those first. Empty costs, such as for a site that cannot be
	 * made to fit, are left out. Rings keep the search near the core, and its cost low, until the
	 * ground there is taken.
	 */
	static <T> Optional<T> cheapest(BlockPos core, int reach, List<BoundingBox> avoid, Function<BlockPos, T> at,
			Function<T, BoundingBox> area, Function<T, OptionalInt> cost) {
		List<BlockPos> columns = columns(core, reach);
		columns.sort(Comparator.comparingInt(pos -> ring(core, pos)));
		T best = null;
		int bestCost = Integer.MAX_VALUE;
		int bestRing = Integer.MAX_VALUE;
		for (BlockPos column : columns) {
			if (ring(core, column) > bestRing) {
				break;
			}
			T site = at.apply(column);
			if (isClear(core, avoid, area.apply(site))) {
				OptionalInt c = cost.apply(site);
				if (c.isPresent() && c.getAsInt() < bestCost) {
					best = site;
					bestCost = c.getAsInt();
					bestRing = ring(core, column);
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/** Which ring of {@link #RING} blocks around the core {@code column} lies in: 0 up to {@code RING} blocks out, 1 up to twice that, and so on. */
	private static int ring(BlockPos core, BlockPos column) {
		return Math.max(0, Math.max(Math.abs(column.getX() - core.getX()), Math.abs(column.getZ() - core.getZ())) - 1) / RING;
	}

	/** The columns within {@code reach} of the core, nearest first. */
	private static List<BlockPos> columns(BlockPos core, int reach) {
		List<BlockPos> columns = new ArrayList<>();
		if (reach < 0) {
			return columns;
		}
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-reach, 0, -reach), core.offset(reach, 0, reach))) {
			columns.add(pos.immutable());
		}
		columns.sort(Comparator.comparingInt(pos -> pos.distManhattan(core)));
		return columns;
	}

	/** Whether {@code a} and {@code b} cover any of the same block columns, at whatever height. */
	public static boolean sharesColumns(BoundingBox a, BoundingBox b) {
		return a.minX() <= b.maxX() && a.maxX() >= b.minX() && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
	}

	/**
	 * Whether {@code box} shares no columns with the {@code avoid} areas or with a ring around the core kept free so bots
	 * can always reach it, at any height: sites never stack over each other.
	 */
	private static boolean isClear(BlockPos core, List<BoundingBox> avoid, BoundingBox box) {
		BoundingBox coreArea = BoundingBox.fromCorners(core.offset(-1, -1, -1), core.offset(1, 1, 1));
		return !sharesColumns(box, coreArea) && avoid.stream().noneMatch(area -> sharesColumns(box, area));
	}
}
