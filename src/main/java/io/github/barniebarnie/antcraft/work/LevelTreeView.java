package io.github.barniebarnie.antcraft.work;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** {@link TreeScanner.TreeView} backed by a live level. */
public record LevelTreeView(Level level) implements TreeScanner.TreeView {
	@Override
	public boolean isLog(BlockPos pos) {
		return this.level.getBlockState(pos).is(BlockTags.LOGS);
	}

	@Override
	public boolean isNaturalLeaves(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
	}
}
