package io.github.barniebarnie.antcraft.pathing;

import net.minecraft.core.BlockPos;

public interface Goal {
	boolean isReached(BlockPos feet);

	/** Estimated cost to reach the goal, in the same units as {@link PathFinder} move costs. */
	double heuristic(BlockPos feet);

	static double estimate(BlockPos from, BlockPos to) {
		int horizontal = Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ());
		int vertical = Math.abs(from.getY() - to.getY());
		return horizontal * PathFinder.WALK_COST + vertical * PathFinder.FALL_COST_PER_BLOCK;
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

	/** Stand where the bot's eyes are within {@code reach} of the block's centre, e.g. to break or use it. */
	record Reach(BlockPos pos, double reach) implements Goal {
		private static final double EYE_HEIGHT = 1.62;

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
