package io.github.barniebarnie.antcraft.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
	 * A bot moving {@code dir} walks through this block, opening it if it is shut, such as a wooden door or fence
	 * gate across its way. A path never breaks it.
	 */
	default boolean canOpen(BlockPos pos, Direction dir) {
		return false;
	}

	/**
	 * A bot holds on here to climb up or down, and may walk into it: a ladder or vine, or a wooden trapdoor in a floor
	 * or ceiling over one, which it opens to climb through and walks on shut.
	 */
	default boolean isClimbable(BlockPos pos) {
		return false;
	}

	/**
	 * A bot moving {@code dir} walks up onto this stair block without jumping, because the side it enters by
	 * is low, such as the front of the mineshaft's stairs. False for any other block.
	 */
	default boolean stairUp(BlockPos pos, Direction dir) {
		return false;
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
