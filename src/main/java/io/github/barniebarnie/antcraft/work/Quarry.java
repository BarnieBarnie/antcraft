package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A small open pit the colony digs for cobblestone and dirt while its mineshaft yields nothing yet: {@link #SIZE}
 * square and {@link #DEPTH} layers deep, each layer a row shorter on its north side than the one above, so bots walk
 * out up the steps. {@code origin} is the lowest corner of the top layer.
 */
public record Quarry(BlockPos origin) {
	private static final int SIZE = 5;
	private static final int DEPTH = 3;
	/** Columns round the pit kept clear of the core and the other sites, so it is dug a little away from them. */
	private static final int MARGIN = 1;

	public static final Codec<Quarry> CODEC = BlockPos.CODEC.xmap(Quarry::new, Quarry::origin);

	public Quarry {
		origin = origin.immutable();
	}

	/** The pit, the margin round it and two blocks of headroom above it. */
	public BoundingBox area() {
		return new BoundingBox(this.origin.getX() - MARGIN, this.origin.getY() - DEPTH + 1, this.origin.getZ() - MARGIN,
			this.origin.getX() + SIZE - 1 + MARGIN, this.origin.getY() + 2, this.origin.getZ() + SIZE - 1 + MARGIN);
	}

	/** The blocks of layer {@code depth}, 0 the top. */
	private Iterable<BlockPos> layer(int depth) {
		return BlockPos.betweenClosed(this.origin.offset(0, -depth, depth), this.origin.offset(SIZE - 1, -depth, SIZE - 1));
	}

	/** The plan that digs the pit: a dig batch per layer, top down, of the blocks still there. Empty once it is dug. */
	public Optional<BuildPlan> plan(Function<BlockPos, BlockState> world) {
		List<BuildPlan.Step> steps = new ArrayList<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		for (int depth = 0; depth < DEPTH; depth++) {
			int from = steps.size();
			for (BlockPos pos : this.layer(depth)) {
				BuildPlan.Step step = BuildPlan.Step.clear(pos.immutable());
				if (!step.isDone(world.apply(pos))) {
					steps.add(step);
				}
			}
			if (steps.size() > from) {
				batches.add(new BuildPlan.Batch(from, steps.size(), batches.isEmpty() ? List.of() : List.of(batches.size() - 1), true));
			}
		}
		return steps.isEmpty() ? Optional.empty() : Optional.of(new BuildPlan(steps, batches));
	}

	/**
	 * The site nearest the core, within {@code radius} of it, clear of the core and of the {@code avoid} areas, where the
	 * whole pit is solid ground every block of which is {@code workable}, as is the layer above it (no fluid over it).
	 */
	public static Optional<Quarry> site(BlockPos core, int radius, List<BoundingBox> avoid, Function<BlockPos, BlockState> world,
			Predicate<BlockPos> workable) {
		return Sites.nearest(core, radius - SIZE / 2, avoid, pos -> new Quarry(pos.offset(-SIZE / 2, -1, -SIZE / 2)), Quarry::area,
			quarry -> quarry.fits(world, workable));
	}

	private boolean fits(Function<BlockPos, BlockState> world, Predicate<BlockPos> workable) {
		for (int depth = 0; depth < DEPTH; depth++) {
			for (BlockPos pos : this.layer(depth)) {
				if (!workable.test(pos) || world.apply(pos).canBeReplaced()) {
					return false;
				}
			}
		}
		for (BlockPos pos : BlockPos.betweenClosed(this.origin.above(), this.origin.offset(SIZE - 1, 1, SIZE - 1))) {
			if (!workable.test(pos)) {
				return false;
			}
		}
		return true;
	}
}
