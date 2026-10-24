package io.github.barniebarnie.antcraft.pathing;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

public interface Goal {
	boolean isReached(BlockPos feet);

	/**
	 * Whether the goal is reached at {@code feet} once the blocks {@code broken} tests true for are gone, such as those
	 * a path broke on its way there. Only sight cares (see {@link InSight}).
	 */
	default boolean isReached(BlockPos feet, Predicate<BlockPos> broken) {
		return this.isReached(feet);
	}

	/** Estimated cost to reach the goal, in the same units as {@link PathFinder} move costs. */
	double heuristic(BlockPos feet);

	/** Whether a path may end swimming at the surface, rather than only on dry ground (see {@link Afloat}). */
	default boolean mayEndAfloat() {
		return false;
	}

	/** Whether a path to this goal may break the block at {@code pos} standing at {@code feet}: any it can, unless the goal says (see {@link Within}). */
	default boolean mayBreak(BlockPos feet, BlockPos pos) {
		return true;
	}

	static double estimate(BlockPos from, BlockPos to) {
		int horizontal = Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ());
		int vertical = Math.abs(from.getY() - to.getY());
		return horizontal * PathFinder.WALK_COST + vertical * PathFinder.FALL_COST_PER_BLOCK;
	}

	/**
	 * {@code goal}, reached swimming at the surface too, as a player fills a pond from the water: for a
	 * block out of reach from every dry spot, such as the bottom of the middle of a deep pond. A path
	 * to it breaks blocks only where there is no way without (see {@link PathFinder#find}), so the bot
	 * swims out rather than dig a hole in the bank to get closer.
	 */
	record Afloat(Goal goal) implements Goal {
		@Override
		public boolean isReached(BlockPos feet) {
			return this.goal.isReached(feet);
		}

		@Override
		public boolean isReached(BlockPos feet, Predicate<BlockPos> broken) {
			return this.goal.isReached(feet, broken);
		}

		@Override
		public double heuristic(BlockPos feet) {
			return this.goal.heuristic(feet);
		}

		@Override
		public boolean mayEndAfloat() {
			return true;
		}

		@Override
		public boolean mayBreak(BlockPos feet, BlockPos pos) {
			return this.goal.mayBreak(feet, pos);
		}
	}

	/**
	 * {@code goal}, reached standing in the {@code area}, and by a path that breaks only blocks of the area, from a spot in
	 * it: as a miner keeps to its mine, entering through the shaft rather than digging its own way down, and digging out
	 * a branch from inside it, not from above. Goals compare by value, so keep one {@code area} per bot rather than a
	 * fresh lambda per call.
	 */
	record Within(Goal goal, Predicate<BlockPos> area) implements Goal {
		@Override
		public boolean isReached(BlockPos feet) {
			return this.area.test(feet) && this.goal.isReached(feet);
		}

		@Override
		public boolean isReached(BlockPos feet, Predicate<BlockPos> broken) {
			return this.area.test(feet) && this.goal.isReached(feet, broken);
		}

		@Override
		public double heuristic(BlockPos feet) {
			return this.goal.heuristic(feet);
		}

		@Override
		public boolean mayEndAfloat() {
			return this.goal.mayEndAfloat();
		}

		@Override
		public boolean mayBreak(BlockPos feet, BlockPos pos) {
			return this.area.test(feet) && this.area.test(pos) && this.goal.mayBreak(feet, pos);
		}
	}

	/** Stand exactly on this position. */
	record Block(BlockPos pos) implements Goal {
		@Override
		public boolean isReached(BlockPos feet) {
			return feet.equals(this.pos);
		}

		@Override
		public double heuristic(BlockPos feet) {
			return estimate(feet, this.pos);
		}
	}

	/** Stand within {@code range} blocks (Manhattan distance) of this position. */
	record Near(BlockPos pos, int range) implements Goal {
		@Override
		public boolean isReached(BlockPos feet) {
			return feet.distManhattan(this.pos) <= this.range;
		}

		@Override
		public double heuristic(BlockPos feet) {
			return Math.max(0, estimate(feet, this.pos) - this.range * PathFinder.FALL_COST_PER_BLOCK);
		}
	}

	/**
	 * Whether a bot standing at {@code feet} can see {@code target}: a clear line from its eyes to some part of it,
	 * where the blocks {@code broken} tests true for count as air.
	 */
	@FunctionalInterface
	interface Sight {
		boolean sees(BlockPos feet, BlockPos target, Predicate<BlockPos> broken);
	}

	/**
	 * Like {@link Reach}, but the bot must also see the block from where it stands, as a player must
	 * to break it, so it does not break a block through a wall. Goals compare by value, so keep one
	 * {@code sight} per bot rather than a fresh lambda per call.
	 */
	record InSight(BlockPos pos, double reach, Sight sight) implements Goal {
		@Override
		public boolean isReached(BlockPos feet) {
			return this.isReached(feet, pos -> false);
		}

		@Override
		public boolean isReached(BlockPos feet, Predicate<BlockPos> broken) {
			return new Reach(this.pos, this.reach).isReached(feet) && this.sight.sees(feet, this.pos, broken);
		}

		@Override
		public double heuristic(BlockPos feet) {
			return new Reach(this.pos, this.reach).heuristic(feet);
		}
	}

	/** Stand where the bot's eyes are within {@code reach} of the block's centre, e.g. to break or use it. */
	record Reach(BlockPos pos, double reach) implements Goal {
		/** How high a bot's eyes are above its feet. */
		public static final double EYE_HEIGHT = 1.62;

		@Override
		public boolean isReached(BlockPos feet) {
			if (feet.equals(this.pos) || feet.above().equals(this.pos)) {
				return false;
			}
			double dx = feet.getX() - this.pos.getX();
			double dy = feet.getY() + EYE_HEIGHT - (this.pos.getY() + 0.5);
			double dz = feet.getZ() - this.pos.getZ();
			return dx * dx + dy * dy + dz * dz <= this.reach * this.reach;
		}

		@Override
		public double heuristic(BlockPos feet) {
			return Math.max(0, estimate(feet, this.pos) - this.reach * PathFinder.WALK_COST);
		}
	}
}
