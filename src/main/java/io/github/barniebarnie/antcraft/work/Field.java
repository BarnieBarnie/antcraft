package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A square crop field the colony grows itself. {@code center} is the soil block in the middle,
 * which holds water; the soil around it is tilled and planted.
 */
public record Field(BlockPos center) {
	/** Blocks from the middle to the edge: a 5x5 field, every block within reach of the water. */
	public static final int RADIUS = 2;
	/** How far below and above the core's floor the field may lie. */
	private static final int HEIGHT_RANGE = 2;

	public static final Codec<Field> CODEC = BlockPos.CODEC.xmap(Field::new, Field::center);

	public Field {
		center = center.immutable();
	}

	/** The soil and the crop layer above it. */
	public BoundingBox area() {
		return new BoundingBox(this.center.getX() - RADIUS, this.center.getY(), this.center.getZ() - RADIUS,
			this.center.getX() + RADIUS, this.center.getY() + 1, this.center.getZ() + RADIUS);
	}

	/** Where crops go (above the soil) that do not have one yet. The middle is left for water. */
	public List<BlockPos> toPlant(Predicate<BlockPos> hasCrop) {
		List<BlockPos> blocks = new ArrayList<>();
		for (BlockPos soil : BlockPos.betweenClosed(this.center.offset(-RADIUS, 0, -RADIUS), this.center.offset(RADIUS, 0, RADIUS))) {
			BlockPos crop = soil.above();
			if (!soil.equals(this.center) && !hasCrop.test(crop)) {
				blocks.add(crop);
			}
		}
		return blocks;
	}

	/**
	 * The field nearest the core on flat soil inside the territory, clear of the core and of the
	 * {@code avoid} areas (such as the mineshaft).
	 *
	 * @param isSoil whether a block can be tilled (or already is farmland)
	 * @param isOpen whether the block above soil leaves room for a crop
	 */
	public static Optional<Field> choose(BlockPos core, int radius, List<BoundingBox> avoid, Predicate<BlockPos> isSoil, Predicate<BlockPos> isOpen) {
		// Keep a ring around the core free so bots can always reach it.
		BoundingBox coreArea = BoundingBox.fromCorners(core.offset(-1, -1, -1), core.offset(1, 1, 1));
		int reach = radius - RADIUS;
		List<BlockPos> centers = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-reach, 0, -reach), core.offset(reach, 0, reach))) {
			centers.add(pos.immutable());
		}
		centers.sort(Comparator.comparingInt(pos -> pos.distManhattan(core)));
		// ponytail: scans up to the whole territory when there is no room; cache failures if surveys get slow.
		for (BlockPos column : centers) {
			for (int dy = 0; dy <= HEIGHT_RANGE; dy = dy > 0 ? -dy : -dy + 1) {
				Field field = new Field(column.offset(0, -1 + dy, 0));
				if (!field.area().intersects(coreArea) && avoid.stream().noneMatch(field.area()::intersects) && field.isFlat(isSoil, isOpen)) {
					return Optional.of(field);
				}
			}
		}
		return Optional.empty();
	}

	private boolean isFlat(Predicate<BlockPos> isSoil, Predicate<BlockPos> isOpen) {
		for (BlockPos soil : BlockPos.betweenClosed(this.center.offset(-RADIUS, 0, -RADIUS), this.center.offset(RADIUS, 0, RADIUS))) {
			if (!isSoil.test(soil) || !isOpen.test(soil.above())) {
				return false;
			}
		}
		return true;
	}
}
