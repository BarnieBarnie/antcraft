package io.github.barniebarnie.antcraft.pathing;

public enum MoveType {
	/** One block sideways on the same level. May dig through or bridge under the destination. */
	WALK,
	/** One block sideways and one up. */
	STEP_UP,
	/** One block sideways, then fall to a lower floor. */
	DROP,
	/** Jump and place a block underneath. */
	PILLAR,
	/** Break the block underneath and drop into the hole. */
	DIG_DOWN,
	/** Into water with air above it, to float at the surface: sideways, up from under water, or falling in from a bank. */
	SWIM,
	/** One block up a ladder or other climbable block, opening a trapdoor on the way (see {@link WorldView#isClimbable}). */
	CLIMB_UP,
	/** One block down a ladder or other climbable block, opening a trapdoor on the way. */
	CLIMB_DOWN
}
