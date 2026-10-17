package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
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
 * left behind. Shared by the roles that pillar up, such as lumberjacks and builders.
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

	/** Whether it is pillaring up to a block it could not reach otherwise; the blocks placed for that are scaffold too. */
	boolean isRetrying() {
		return this.retrying != null;
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
			.max(Comparator.<BlockPos, Boolean>comparing(pos -> new Goal.Reach(pos, REACH).isReached(feet)).thenComparingInt(BlockPos::getY))
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
		Goal reach = new Goal.Reach(next, REACH);
		if (reach.isReached(feet) && this.bot.onGround()) {
			this.stopTravel.run();
			if (this.breaker.tick(level, next, true)) {
				placed.remove(next);
			}
		} else if (this.travel.apply(reach) == PathFollower.Status.FAILED) {
			this.unreachable.add(next);
		}
		return false;
	}

	/** Pillars up to the scaffold block being retried; once there, cleanup looks at every block again from up there. */
	private void retry(BlockPos feet) {
		BlockPos target = this.retrying;
		Goal reach = new Goal.Reach(target, REACH);
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
