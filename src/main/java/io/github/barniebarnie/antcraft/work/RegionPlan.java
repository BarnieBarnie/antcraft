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

	/** Columns per side of the square of a layer one batch covers, so a batch stays small enough to scan every tick. */
	static final int CHUNK = 4;

	/**
	 * A plan that rebuilds the box between the corners {@code from} and {@code to} (inclusive, any
	 * order) with its lowest corner at {@code at}. Layer by layer from the bottom; air becomes a
	 * clear step, flowing fluid is left out, and so is a position {@code world} gives {@code null} for. A two-block block, such as
	 * a door or a bed, is one step for its lower half or foot, which sets the other half. Only block states are copied, not block-entity
	 * contents. Empty when that leaves no step, such as for a box holding only a door's upper half.
	 *
	 * <p>Each square of {@link #CHUNK} columns of a layer is a batch, so several builders can work
	 * one layer, and each needs every batch of the layer below, so what a block rests on comes first.
	 */
	public static Optional<BuildPlan> copy(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to, BlockPos at) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		List<BuildPlan.Step> steps = new ArrayList<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		// The batches of the last layer below with any steps.
		List<Integer> below = List.of();
		for (int y = min.getY(); y <= max.getY(); y++) {
			List<Integer> layer = new ArrayList<>();
			for (int cz = min.getZ(); cz <= max.getZ(); cz += CHUNK) {
				for (int cx = min.getX(); cx <= max.getX(); cx += CHUNK) {
					int first = steps.size();
					for (int z = cz; z <= Math.min(cz + CHUNK - 1, max.getZ()); z++) {
						for (int x = cx; x <= Math.min(cx + CHUNK - 1, max.getX()); x++) {
							BlockPos source = new BlockPos(x, y, z);
							BlockState state = world.apply(source);
							// Flowing fluid cannot be placed; it follows from its source. The second half of a door or bed follows from the first.
							if (state != null && (!(state.getBlock() instanceof LiquidBlock) || state.getFluidState().isSource()) && !BuildPlan.isSecondHalf(state)) {
								steps.add(new BuildPlan.Step(at.offset(source.subtract(min)), state));
							}
						}
					}
					if (steps.size() > first) {
						layer.add(batches.size());
						batches.add(new BuildPlan.Batch(first, steps.size(), below));
					}
				}
			}
			if (!layer.isEmpty()) {
				below = List.copyOf(layer);
			}
		}
		return steps.isEmpty() ? Optional.empty() : Optional.of(new BuildPlan(steps, batches));
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
