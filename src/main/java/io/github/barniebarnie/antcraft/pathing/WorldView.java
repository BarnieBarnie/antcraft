package io.github.barniebarnie.antcraft.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** What the pathfinder needs to know about blocks. Lets the planner run without a live world. */
public interface WorldView {
	/** A bot can occupy this block (air, grass, flowers). */
	boolean isPassable(BlockPos pos);

	/** The block has a solid top a bot can stand on. */
	boolean canStandOn(BlockPos pos);

	/**
	 * The top of the floor this block is, above its bottom: 1 for a full block, 0.5 for a bottom slab, 0.125 for snow
	 * two layers deep (#647, #659).
	 */
	default float floorTop(BlockPos pos) {
		return 1;
	}

	/**
	 * How high above this block's bottom a bot standing on it lifts its feet with a jump: the floor's top plus about
	 * 1.25, or about 0.38 from honey, which halves a jump's speed. A step up or a pillar needs it to clear the top of
	 * what it lands on (#647, #659).
	 */
	default float jumpTop(BlockPos pos) {
		return this.floorTop(pos) + (float) jumpHeight(1);
	}

	/**
	 * How high a jump lifts a mob's feet from the ground, by vanilla's physics: 0.42 blocks a tick up times the block's
	 * jump factor (0.5 for honey), less 0.08 gravity a tick and 2% drag, until it falls; about 1.25 for a factor of 1.
	 */
	static double jumpHeight(float factor) {
		return jumpHeight(factor, 0);
	}

	/**
	 * Like {@link #jumpHeight(float)}, for a mob whose Jump Boost adds {@code boost} to the jump's speed after the
	 * block's factor: 0.1 a level, as vanilla's {@code getJumpBoostPower} (#665).
	 */
	static double jumpHeight(float factor, float boost) {
		double height = 0;
		for (double speed = 0.42 * factor + boost; speed > 0; speed = (speed - 0.08) * 0.98) {
			height += speed;
		}
		return height;
	}

	/** Never enter, stand on, or open up next to this block (lava, fire, any fluid), except to swim in it if {@link #isWater}. */
	boolean isHazard(BlockPos pos);

	/**
	 * A block that hurts a bot brushing it from beside, such as a cactus (#830): paths keep off the blocks beside it
	 * ({@link #besidePrickly}). One a bot is hurt only inside of, such as a sweet berry bush, is just a {@link #isHazard hazard}.
	 */
	default boolean isPrickly(BlockPos pos) {
		return false;
	}

	/** A bot with its feet at {@code feet} stands beside a {@link #isPrickly prickly} block, at its feet or its head. */
	default boolean besidePrickly(BlockPos feet) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			if (this.isPrickly(feet.relative(dir)) || this.isPrickly(feet.above().relative(dir))) {
				return true;
			}
		}
		return false;
	}

	/** A nether portal: a {@link #isHazard hazard} a path enters only to stand in it at its goal, to go through on purpose. */
	default boolean isPortal(BlockPos pos) {
		return false;
	}

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
	 * The faces this {@link #isClimbable climbable} block holds on to, one bit per {@link Direction#get3DDataValue}: a
	 * ladder the block behind it, a vine each block it clings to (or, beside open air, the vine above on that face),
	 * weeping and cave vines the block above, twisting vines and scaffolding the block below (or, up or down, the
	 * climbable there that holds on the same way; #752). 0 for one that holds on to nothing, such as a hatch (#735).
	 */
	default int climbFaces(BlockPos pos) {
		return 0;
	}

	/** A vine, whose faces hold on to the vine above as well as to the block beside them (#735). */
	default boolean isVine(BlockPos pos) {
		return false;
	}

	/**
	 * A bot moving {@code dir} walks up onto this stair block without jumping, because the side it enters by
	 * is low, such as the front of the mineshaft's stairs. False for any other block.
	 */
	default boolean stairUp(BlockPos pos, Direction dir) {
		return false;
	}

	/** Broken by the bot, this block drops a scaffold block it may place, such as dirt or grass, for its dirt (#771). */
	default boolean dropsScaffold(BlockPos pos) {
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
