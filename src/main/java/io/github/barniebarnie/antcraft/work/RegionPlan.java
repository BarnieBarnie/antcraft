package io.github.barniebarnie.antcraft.work;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Turns a box of blocks into a build plan, so a structure can be copied elsewhere. */
public final class RegionPlan {
	private RegionPlan() {
	}

	/**
	 * A plan that rebuilds the box between the corners {@code from} and {@code to} (inclusive, any
	 * order) with its lowest corner at {@code at}. Layer by layer from the bottom; air becomes a
	 * clear step, flowing fluid is left out, and so is a position {@code world} gives {@code null} for. A two-block block, such as
	 * a door or a bed, is one step for its lower half or foot, which sets the other half. Only block states are copied, not block-entity
	 * contents. Empty when that leaves no step, such as for a box holding only a door's upper half.
	 *
	 * <p>Each layer is a batch, split into cells (see {@link BuildPlan#inCells}) so several builders can work
	 * one layer, and each cell needs only the cells of the layer below under it, so what a block rests on comes first
	 * while builders work several layers apart at once.
	 */
	public static Optional<BuildPlan> copy(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to, BlockPos at) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		List<BuildPlan.Step> steps = new ArrayList<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		for (int y = min.getY(); y <= max.getY(); y++) {
			int first = steps.size();
			for (int z = min.getZ(); z <= max.getZ(); z++) {
				for (int x = min.getX(); x <= max.getX(); x++) {
					BlockPos source = new BlockPos(x, y, z);
					BlockState state = world.apply(source);
					// Flowing fluid cannot be placed; it follows from its source. The second half of a door or bed follows from the first.
					if (state != null && (!(state.getBlock() instanceof LiquidBlock) || state.getFluidState().isSource()) && !BuildPlan.isSecondHalf(state)) {
						steps.add(new BuildPlan.Step(at.offset(source.subtract(min)), state));
					}
				}
			}
			// A layer with nothing to build is left out; the next needs the last layer below with any steps.
			if (steps.size() > first) {
				batches.add(new BuildPlan.Batch(first, steps.size(), batches.isEmpty() ? List.of() : List.of(batches.size() - 1)));
			}
		}
		return steps.isEmpty() ? Optional.empty() : Optional.of(new BuildPlan(steps, batches).inCells());
	}

	/**
	 * A plan that builds a structure template (a structure block's {@code .nbt}) mirrored by
	 * {@code mirror}, then turned by {@code rotation}, as a structure block places it, with the lowest corner of the turned box at {@code at}, batched like
	 * {@link #copy}. A template with several palettes (block variants, like vanilla shipwrecks) is
	 * built from palette {@code palette}, which must be below {@link #paletteCount}. Structure void
	 * is left as it is; block-entity contents and entities are skipped.
	 */
	public static Optional<BuildPlan> load(StructureTemplate template, BlockPos at, Mirror mirror, Rotation rotation, int palette) {
		Vec3i size = template.getSize();
		if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
			return Optional.empty();
		}
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (StructureTemplate.StructureBlockInfo block : template.palettes.get(palette).blocks()) {
			blocks.put(turn(block.pos(), mirror, rotation), block.state().mirror(mirror).rotate(rotation));
		}
		return copy(blocks::get, turn(BlockPos.ZERO, mirror, rotation),
			turn(new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1), mirror, rotation), at);
	}

	/** How many palettes {@code template} holds: 1 for a structure block's save. */
	public static int paletteCount(StructureTemplate template) {
		return template.palettes.size();
	}

	private static BlockPos turn(BlockPos pos, Mirror mirror, Rotation rotation) {
		return StructureTemplate.transform(pos, mirror, rotation, BlockPos.ZERO);
	}
}
