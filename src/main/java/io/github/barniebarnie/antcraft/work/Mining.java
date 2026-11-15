package io.github.barniebarnie.antcraft.work;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
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

	/**
	 * Whether {@code state} is a soft block that is broken, keeping its drops, before a block goes where it stands (#747):
	 * grass, ferns, flowers, saplings, snow layers, leaves, vines, moss carpet and the like. Never air, a fluid or a
	 * waterlogged block, fire, structure void or a block entity.
	 */
	public static boolean isSoft(BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity() || state.is(BlockTags.FIRE)
			|| state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.LIGHT)) {
			return false;
		}
		return state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.FLOWERS)
			|| state.is(BlockTags.SAPLINGS) || state.is(Blocks.MOSS_CARPET);
	}

	/**
	 * Whether a mine cell is dug: nothing a bot would mine is left and nothing blocks the way through, such as air,
	 * a torch or water. Bedrock, a chest or stone holding back water is not, so a hall or branch
	 * with such a cell in its way does not count as dug.
	 */
	public static boolean isDug(Level level, BlockPos pos) {
		return !isMinable(level, pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
