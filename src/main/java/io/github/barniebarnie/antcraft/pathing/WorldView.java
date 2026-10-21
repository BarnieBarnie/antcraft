package io.github.barniebarnie.antcraft.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/** What the pathfinder needs to know about blocks. Lets the planner run without a live world. */
public interface WorldView {
	/** A bot can occupy this block (air, grass, flowers). */
	boolean isPassable(BlockPos pos);

	/** The block has a solid top a bot can stand on. */
	boolean canStandOn(BlockPos pos);

	/** Never enter, stand on, or open up next to this block (lava, fire, any fluid), except to swim in it if {@link #isWater}. */
	boolean isHazard(BlockPos pos);

	/** Water a bot can swim in: at the surface where the block above is open, and up through it from below. */
	default boolean isWater(BlockPos pos) {
		return false;
	}

	/** Ticks needed to break the block, or a negative number if it must not be broken. */
	float breakTicks(BlockPos pos);

	/**
	 * The way a bot walks up onto this bottom stair block without jumping, from its low front, such as
	 * on the mineshaft's stairs; null if it is no such stair. From any other side it is a full block.
	 */
	default @Nullable Direction stairUp(BlockPos pos) {
		return null;
	}

	/** Bedrock, which no shaft digs through; not other blocks that cannot be broken, such as a barrier. */
	default boolean isBedrock(BlockPos pos) {
		return false;
	}

	/** Whether a path may place a scaffold block here; not where a builder's plan puts a block of its own, say. */
	default boolean mayPlace(BlockPos pos) {
		return true;
	}
}
