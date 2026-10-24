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
	 * while builders work several layers apart at once. A block hung from the one above, such as a lantern, goes in the
	 * layer of that block, after it.
	 */
	public static Optional<BuildPlan> copy(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to, BlockPos at) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		List<BuildPlan.Step> steps = new ArrayList<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		// Steps hung from the block above, such as a lantern, by where that block goes: they are added right after it, in its layer.
		Map<BlockPos, List<BuildPlan.Step>> hung = new HashMap<>();
		for (int y = min.getY(); y <= max.getY(); y++) {
			int first = steps.size();
			for (int z = min.getZ(); z <= max.getZ(); z++) {
				for (int x = min.getX(); x <= max.getX(); x++) {
					BlockPos source = new BlockPos(x, y, z);
					BlockState state = world.apply(source);
					if (copied(state)) {
						BuildPlan.Step step = new BuildPlan.Step(at.offset(source.subtract(min)), state);
						BlockState above = y < max.getY() ? world.apply(source.above()) : null;
						if (copied(above) && !above.isAir() && BuildPlan.holder(step, step.pos().above()::equals).isPresent()) {
							hung.computeIfAbsent(step.pos().above(), pos -> new ArrayList<>()).add(step);
						} else {
							add(step, steps, hung);
						}
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

	/** Whether {@code state} gets a step: flowing fluid follows from its source, and the second half of a door or bed from the first. */
	private static boolean copied(BlockState state) {
		return state != null && (!(state.getBlock() instanceof LiquidBlock) || state.getFluidState().isSource()) && !BuildPlan.isSecondHalf(state);
	}

	/** Adds {@code step} to {@code steps}, then what hangs from it, and what hangs from that in turn. */
	private static void add(BuildPlan.Step step, List<BuildPlan.Step> steps, Map<BlockPos, List<BuildPlan.Step>> hung) {
		steps.add(step);
		for (BuildPlan.Step held : hung.getOrDefault(step.pos(), List.of())) {
			add(held, steps, hung);
		}
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

	/**
	 * Where {@link #load} puts the block at {@code pos} of a structure of {@code size}, relative to the structure's origin:
	 * mirrored, turned and moved the same way, so a spot in a structure stays on the same block wherever it is built.
	 */
	public static BlockPos placed(BlockPos pos, Vec3i size, BlockPos at, Mirror mirror, Rotation rotation) {
		BlockPos min = BlockPos.min(turn(BlockPos.ZERO, mirror, rotation),
			turn(new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1), mirror, rotation));
		return at.offset(turn(pos, mirror, rotation).subtract(min));
	}

	/** How many palettes {@code template} holds: 1 for a structure block's save. */
	public static int paletteCount(StructureTemplate template) {
		return template.palettes.size();
	}

	private static BlockPos turn(BlockPos pos, Mirror mirror, Rotation rotation) {
		return StructureTemplate.transform(pos, mirror, rotation, BlockPos.ZERO);
	}
}
