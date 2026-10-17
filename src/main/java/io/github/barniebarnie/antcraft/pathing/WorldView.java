package io.github.barniebarnie.antcraft.pathing;

import net.minecraft.core.BlockPos;

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

	/** Whether a path may place a scaffold block here; not where a builder's plan puts a block of its own, say. */
	default boolean mayPlace(BlockPos pos) {
		return true;
	}
}
