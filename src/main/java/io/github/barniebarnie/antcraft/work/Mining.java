package io.github.barniebarnie.antcraft.work;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class Mining {
	private Mining() {
	}

	/**
	 * Whether a bot should mine this block: something solid, breakable, not a container,
	 * and not holding back water or lava.
	 */
	public static boolean isMinable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0) {
			return false;
		}
		for (Direction dir : Direction.values()) {
			if (!level.getFluidState(pos.relative(dir)).isEmpty()) {
				return false;
			}
		}
		return true;
	}
}
