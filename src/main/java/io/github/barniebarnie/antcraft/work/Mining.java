package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class Mining {
	private Mining() {
	}

	/**
	 * Whether a bot should mine this block: something solid, breakable, not a container,
	 * not holding back water or lava,
	 * and not built by a colony builder.
	 */
	public static boolean isMinable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0
				|| ColonyManager.isProtected(level, pos)) {
			return false;
		}
		for (Direction dir : Direction.values()) {
			if (!level.getFluidState(pos.relative(dir)).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a mine cell is dug: nothing a bot would mine is left and nothing blocks the way through, such as air,
	 * a torch or water. Bedrock, a chest, a protected block or stone holding back water is not, so a hall or branch
	 * with such a cell in its way does not count as dug.
	 */
	public static boolean isDug(Level level, BlockPos pos) {
		return !isMinable(level, pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
