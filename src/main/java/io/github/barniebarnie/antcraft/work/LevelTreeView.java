package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * {@link TreeScanner.TreeView} backed by a live level, for one stretch of work in a tick (see {@link LoadedBlocks}).
 * Blocks in chunks that are not loaded are neither logs, leaves nor open, so a scan never loads a chunk.
 */
public final class LevelTreeView implements TreeScanner.TreeView {
	private final Level level;
	private final LoadedBlocks blocks;

	public LevelTreeView(Level level) {
		this.level = level;
		this.blocks = new LoadedBlocks(level);
	}

	@Override
	public boolean isLog(BlockPos pos) {
		@Nullable BlockState state = this.blocks.loaded(pos);
		// Logs a builder placed are part of a build, not a tree.
		return state != null && state.is(BlockTags.LOGS) && !ColonyManager.isProtected(this.level, pos);
	}

	@Override
	public boolean isNaturalLeaves(BlockPos pos) {
		@Nullable BlockState state = this.blocks.loaded(pos);
		return state != null && state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
	}

	@Override
	public boolean isOpen(BlockPos pos) {
		@Nullable BlockState state = this.blocks.loaded(pos);
		return state != null && state.canBeReplaced();
	}
}
