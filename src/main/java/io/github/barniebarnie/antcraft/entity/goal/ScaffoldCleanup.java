package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.entity.navigation.LineOfSight;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Takes down the scaffold blocks a bot placed for its work ({@link AntBot#getScaffold}), getting
 * them back. Blocks within reach go first, top down, so a pillar the bot stands on comes down under
 * it. Walking to the others never places new scaffolding. Blocks that cannot be reached that way are
 * retried once the rest is down: the bot pillars up to them, and that pillar is taken down in turn,
 * under it. Retries are limited per {@link #reset}, and a block it cannot reach even by pillaring is
 * left behind, and so is one it cannot see from anywhere in reach, since like a player it breaks
 * only blocks in sight, and one it stands on with a drop below further than a path may take. Shared by trees and builds, and by the work between jobs that takes down what any path placed.
 */
final class ScaffoldCleanup {
	private static final double REACH = 4.0;
	/**
	 * How many times cleanup may pillar up to scaffold it cannot otherwise reach. Each retry places
	 * new scaffolding that has to come down as well, so this bound keeps cleanup from looping.
	 */
	private static final int RETRIES = 8;

	private final AntBot bot;
	private final PathFollower follower;
	private final BlockBreaker breaker;
	/** Travels toward a goal with the bot's own follower, starting it anew when the goal changes. */
	private final Function<Goal, PathFollower.Status> travel;
	/** Stops travelling, so the next travel plans a fresh path. */
	private final Runnable stopTravel;
	/** What the bot can see, to break only scaffold in sight; one for good, as goals compare by value. */
	private final Goal.Sight sight;

	/** Scaffold that cannot be reached without placing more; retried once the rest is down. */
	private final Set<BlockPos> unreachable = new HashSet<>();
	/** Scaffold that could not be reached even by pillaring; left behind. */
	private final Set<BlockPos> abandoned = new HashSet<>();
	/** The scaffold block cleanup is pillaring up to, or null when not retrying one. */
	private @Nullable BlockPos retrying;
	private int retriesLeft = RETRIES;

	ScaffoldCleanup(AntBot bot, PathFollower follower, BlockBreaker breaker, Function<Goal, PathFollower.Status> travel, Runnable stopTravel) {
		this.bot = bot;
		this.follower = follower;
		this.breaker = breaker;
		this.travel = travel;
		this.stopTravel = stopTravel;
		this.sight = (feet, target) -> LineOfSight.sees(bot.level(), feet, target);
	}

	/** Where the bot can take the block at {@code pos} down: within reach, and in sight of it. */
	private Goal inSight(BlockPos pos) {
		return new Goal.InSight(pos, REACH, this.sight);
	}

	/** Starts afresh, such as for a new tree: every block is tried again, with all retries. */
	void reset() {
		this.unreachable.clear();
		this.abandoned.clear();
		this.retrying = null;
		this.retriesLeft = RETRIES;
	}

	/** Stops pillaring up to a block, such as when the bot turns to other work. */
	void cancelRetry() {
		this.retrying = null;
	}

	/**
	 * Works on the cleanup for a tick, with the follower not placing blocks except while retrying.
	 * Blocks that {@code keep} holds, such as ones a builder placed for its plan, are never taken
	 * down and are no longer counted as scaffold. Returns true once done: the scaffold is all down,
	 * or what is left is out of reach.
	 */
	boolean tick(ServerLevel level, Predicate<BlockPos> keep) {
		Set<BlockPos> placed = this.bot.getScaffold();
		BlockPos feet = this.bot.blockPosition();
		placed.removeIf(pos -> keep.test(pos) || !level.getBlockState(pos).getBlock().asItem().getDefaultInstance().is(PathFollower.SCAFFOLD_BLOCKS));
		if (this.retrying != null) {
			this.retry(feet);
			return false;
		}
		BlockPos next = placed.stream()
			.filter(pos -> !this.unreachable.contains(pos) && !this.abandoned.contains(pos))
			.max(Comparator.<BlockPos, Boolean>comparing(pos -> this.inSight(pos).isReached(feet)).thenComparingInt(BlockPos::getY))
			.orElse(null);
		if (next == null) {
			BlockPos retry = this.retriesLeft <= 0 ? null : placed.stream()
				.filter(pos -> !this.abandoned.contains(pos))
				.max(Comparator.comparingInt(BlockPos::getY))
				.orElse(null);
			if (retry != null) {
				this.retriesLeft--;
				this.retrying = retry;
				this.stopTravel.run();
				this.follower.setMayPlace(true);
				return false;
			}
			placed.clear();
			return true;
		}
		Goal reach = this.inSight(next);
		if (reach.isReached(feet) && this.bot.onGround()) {
			this.stopTravel.run();
			if (next.equals(feet.below()) && fallUnder(level, next) > PathFollower.MAX_DROP) {
				// Taking it down from under itself would drop the bot further than a path may: it leaves it up.
				this.abandoned.add(next);
				return false;
			}
			if (this.breaker.tick(level, next, true)) {
				placed.remove(next);
			}
		} else if (this.travel.apply(reach) == PathFollower.Status.FAILED) {
			this.unreachable.add(next);
		}
		return false;
	}

	/** How far the bot standing on {@code pos} falls once that block is gone. */
	private static int fallUnder(ServerLevel level, BlockPos pos) {
		BlockPos.MutableBlockPos below = pos.below().mutable();
		while (below.getY() > level.getMinY() && level.getBlockState(below).getCollisionShape(level, below).isEmpty()
				&& level.getFluidState(below).isEmpty()) {
			below.move(0, -1, 0);
		}
		return pos.getY() - below.getY();
	}

	/** Pillars up to the scaffold block being retried; once there, cleanup looks at every block again from up there. */
	private void retry(BlockPos feet) {
		BlockPos target = this.retrying;
		Goal reach = this.inSight(target);
		if (!this.bot.getScaffold().contains(target) || (reach.isReached(feet) && this.bot.onGround())) {
			this.unreachable.clear();
			this.endRetry();
		} else if (this.travel.apply(reach) == PathFollower.Status.FAILED) {
			this.abandoned.add(target);
			this.endRetry();
		}
	}

	private void endRetry() {
		this.retrying = null;
		this.stopTravel.run();
		this.follower.setMayPlace(false);
	}
}
