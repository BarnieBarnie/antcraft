package io.github.barniebarnie.antcraft.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Block states of a level's loaded chunks, for code that looks at many blocks in a row, such as a path search or a
 * colony's survey. {@link Level#getBlockState} asks the chunk source for the chunk each time, which on the server, past
 * its cache of the last four chunks, adds a ticket and runs the chunk tickets' updates, and loads or generates a chunk
 * that is not loaded, holding up the tick. This looks up loaded chunks only, remembering the last one.
 * One for a stretch of work in one tick: a chunk unloaded later is still remembered.
 */
public final class LoadedBlocks {
	private final Level level;
	private long lastChunk = Long.MIN_VALUE;
	private @Nullable LevelChunk chunk;

	public LoadedBlocks(Level level) {
		this.level = level;
	}

	/** The state at {@code pos}, or null if its chunk is not loaded. */
	public @Nullable BlockState loaded(BlockPos pos) {
		if (!this.level.isInValidBounds(pos)) {
			return Blocks.VOID_AIR.defaultBlockState();
		}
		int x = SectionPos.blockToSectionCoord(pos.getX());
		int z = SectionPos.blockToSectionCoord(pos.getZ());
		long key = ChunkPos.pack(x, z);
		if (key != this.lastChunk || this.chunk == null) {
			this.chunk = this.level.getChunkSource().getChunkNow(x, z);
			this.lastChunk = key;
		}
		return this.chunk == null ? null : this.chunk.getBlockState(pos);
	}

	/** The state at {@code pos}, loading its chunk if it is not loaded, as {@link Level#getBlockState} does. */
	public BlockState get(BlockPos pos) {
		BlockState state = this.loaded(pos);
		return state != null ? state : this.level.getBlockState(pos);
	}
}
