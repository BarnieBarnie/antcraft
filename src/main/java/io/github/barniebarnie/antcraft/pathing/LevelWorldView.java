package io.github.barniebarnie.antcraft.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** {@link WorldView} backed by a live level, using the given tool for break times. */
public class LevelWorldView implements WorldView {
	private final Level level;
	private final ItemStack tool;

	public LevelWorldView(Level level, ItemStack tool) {
		this.level = level;
		this.tool = tool;
	}

	@Override
	public boolean isPassable(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		return state.getCollisionShape(this.level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		return this.level.getBlockState(pos).isFaceSturdy(this.level, pos, Direction.UP);
	}

	@Override
	public boolean isHazard(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		return !state.getFluidState().isEmpty()
			|| state.is(BlockTags.FIRE)
			|| state.is(Blocks.MAGMA_BLOCK)
			|| state.is(Blocks.CACTUS)
			|| state.is(Blocks.SWEET_BERRY_BUSH)
			|| state.is(Blocks.POWDER_SNOW)
			|| state.is(Blocks.CAMPFIRE)
			|| state.is(Blocks.SOUL_CAMPFIRE)
			|| state.is(Blocks.WITHER_ROSE);
	}

	@Override
	public float breakTicks(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		// Leave containers and other block entities alone.
		if (state.hasBlockEntity()) {
			return -1;
		}
		return breakTicks(state, this.level, pos, this.tool);
	}

	public static int breakTicks(BlockState state, Level level, BlockPos pos, ItemStack tool) {
		boolean canHarvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		return BreakTime.ticks(state.getDestroySpeed(level, pos), tool.getDestroySpeed(state), canHarvest);
	}
}
