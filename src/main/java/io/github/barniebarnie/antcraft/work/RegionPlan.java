package io.github.barniebarnie.antcraft.work;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Turns a box of blocks into a build plan, so a structure can be copied elsewhere. */
public final class RegionPlan {
	private RegionPlan() {
	}

	/**
	 * A plan that rebuilds the box between the corners {@code from} and {@code to} (inclusive, any
	 * order) with its lowest corner at {@code at}. Layer by layer from the bottom; air becomes a
	 * clear step, and flowing fluid is left out. A two-block block, such as
	 * a door or a bed, is one step for its lower half or foot, which sets the other half. Only block states are copied, not block-entity
	 * contents.
	 */
	public static BuildPlan copy(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to, BlockPos at) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		List<BuildPlan.Step> steps = new ArrayList<>();
		for (int y = min.getY(); y <= max.getY(); y++) {
			for (int z = min.getZ(); z <= max.getZ(); z++) {
				for (int x = min.getX(); x <= max.getX(); x++) {
					BlockPos source = new BlockPos(x, y, z);
					BlockState state = world.apply(source);
					// Flowing fluid cannot be placed; it follows from its source. The second half of a door or bed follows from the first.
					if ((!(state.getBlock() instanceof LiquidBlock) || state.getFluidState().isSource()) && !BuildPlan.isSecondHalf(state)) {
						steps.add(new BuildPlan.Step(at.offset(source.subtract(min)), state));
					}
				}
			}
		}
		return new BuildPlan(steps);
	}
}
