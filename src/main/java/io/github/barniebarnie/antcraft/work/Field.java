package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A square crop field the colony grows itself. {@code center} is the soil block in the middle,
 * which holds water; the soil around it is tilled and planted.
 */
public record Field(BlockPos center) {
	/** Blocks from the middle to the edge: a 5x5 field, every block within reach of the water. */
	public static final int RADIUS = 2;

	public static final Codec<Field> CODEC = BlockPos.CODEC.xmap(Field::new, Field::center);

	public Field {
		center = center.immutable();
	}

	/** The soil and the layers {@link #plan} clears above it. */
	public BoundingBox area() {
		return new BoundingBox(this.center.getX() - RADIUS, this.center.getY(), this.center.getZ() - RADIUS,
			this.center.getX() + RADIUS, this.center.getY() + CLEAR_HEIGHT, this.center.getZ() + RADIUS);
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
	 * @param isOpen whether a block above soil is open, checked for the layers {@link #plan} clears
	 */
	public static Optional<Field> choose(BlockPos core, int radius, List<BoundingBox> avoid, Predicate<BlockPos> isSoil, Predicate<BlockPos> isOpen) {
		return Sites.nearest(core, radius - RADIUS, avoid, pos -> new Field(pos.below()), Field::area, field -> field.isFlat(isSoil, isOpen));
	}

	/** How far from the core a new colony looks for its field (see {@link #site}). */
	private static final int SITE_REACH = 8;

	/** How many blocks above the soil {@link #plan} clears: the crop layer and the one above, room to walk and light. */
	private static final int CLEAR_HEIGHT = 2;

	/**
	 * The plan that readies the field for planting: the two layers above the soil cleared (top first), and dirt
	 * where the soil cannot be tilled, such as rock, in cells (see {@link BuildPlan#inCells}). The middle is left for the water. Empty if it is ready.
	 */
	public Optional<BuildPlan> plan(Function<BlockPos, BlockState> world) {
		List<BuildPlan.Step> clear = new ArrayList<>();
		List<BuildPlan.Step> soil = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(this.center.offset(-RADIUS, 0, -RADIUS), this.center.offset(RADIUS, 0, RADIUS))) {
			for (int up = CLEAR_HEIGHT; up >= 1; up--) {
				if (!Farming.isOpen(world.apply(pos.above(up)))) {
					clear.add(BuildPlan.Step.clear(pos.above(up)));
				}
			}
			if (!pos.equals(this.center) && !Farming.isSoil(world.apply(pos))) {
				soil.add(new BuildPlan.Step(pos, Blocks.DIRT.defaultBlockState()));
			}
		}
		List<BuildPlan.Step> steps = new ArrayList<>(clear);
		steps.addAll(soil);
		List<BuildPlan.Batch> batches = new ArrayList<>();
		if (!clear.isEmpty()) {
			batches.add(new BuildPlan.Batch(0, clear.size(), List.of(), true));
		}
		if (!soil.isEmpty()) {
			batches.add(new BuildPlan.Batch(clear.size(), steps.size(), batches.isEmpty() ? List.of() : List.of(0)));
		}
		return steps.isEmpty() ? Optional.empty() : Optional.of(new BuildPlan(steps, batches, BuildPlan.Kind.TERRAFORM).inCells());
	}

	/**
	 * Where a new colony lays out its field: at the core's level near it, clear of the core and of the {@code avoid}
	 * areas, with every block {@code workable}, needing the smallest {@link #plan}, the nearest of those.
	 */
	public static Optional<Field> site(BlockPos core, int radius, List<BoundingBox> avoid, Function<BlockPos, BlockState> world,
			Predicate<BlockPos> workable) {
		return Sites.cheapest(core, Math.min(SITE_REACH, radius - RADIUS), avoid, pos -> new Field(pos.below()), Field::area, field -> {
			for (BlockPos pos : BlockPos.betweenClosed(field.area().minX(), field.area().minY(), field.area().minZ(),
					field.area().maxX(), field.area().maxY(), field.area().maxZ())) {
				if (!workable.test(pos)) {
					return OptionalInt.empty();
				}
			}
			return OptionalInt.of(field.plan(world).map(plan -> plan.steps().size()).orElse(0));
		});
	}

	private boolean isFlat(Predicate<BlockPos> isSoil, Predicate<BlockPos> isOpen) {
		for (BlockPos soil : BlockPos.betweenClosed(this.center.offset(-RADIUS, 0, -RADIUS), this.center.offset(RADIUS, 0, RADIUS))) {
			if (!isSoil.test(soil)) {
				return false;
			}
			for (int up = 1; up <= CLEAR_HEIGHT; up++) {
				if (!isOpen.test(soil.above(up))) {
					return false;
				}
			}
		}
		return true;
	}
}
