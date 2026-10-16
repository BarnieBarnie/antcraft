package io.github.barniebarnie.antcraft.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Finding room near the core for a colony area, such as a field or storage. */
final class Sites {
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
		if (reach < 0) {
			return Optional.empty();
		}
		// Keep a ring around the core free so bots can always reach it.
		BoundingBox coreArea = BoundingBox.fromCorners(core.offset(-1, -1, -1), core.offset(1, 1, 1));
		List<BlockPos> columns = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-reach, 0, -reach), core.offset(reach, 0, reach))) {
			columns.add(pos.immutable());
		}
		columns.sort(Comparator.comparingInt(pos -> pos.distManhattan(core)));
		// ponytail: scans up to the whole territory when there is no room; cache failures if surveys get slow.
		for (BlockPos column : columns) {
			for (int dy = 0; dy <= HEIGHT_RANGE; dy = dy > 0 ? -dy : -dy + 1) {
				T site = at.apply(column.offset(0, dy, 0));
				BoundingBox box = area.apply(site);
				if (!box.intersects(coreArea) && avoid.stream().noneMatch(box::intersects) && fits.test(site)) {
					return Optional.of(site);
				}
			}
		}
		return Optional.empty();
	}
}
