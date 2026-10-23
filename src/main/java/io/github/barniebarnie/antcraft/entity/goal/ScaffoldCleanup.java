package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.entity.navigation.LineOfSight;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Takes down the scaffold blocks a bot placed for its work ({@link AntBot#getScaffold}), getting
 * them back. Blocks within reach go first, top down, so a pillar the bot stands on comes down under
 * it. Walking to the others never places new scaffolding. Blocks that cannot be reached that way are
 * retried once the rest is down: the bot pillars up to them, and that pillar is taken down in turn,
 * under it. Retries are limited per {@link #reset}, and a block it cannot reach even by pillaring is
 * left behind, and so is one it cannot see from anywhere in reach, since like a player it breaks
 * only blocks in sight. One it stands on with a drop below further than a path may take it towers down
 * from, placing a scaffold block under it before taking it down; carrying none, it digs down, breaking that block
 * for its own block and placing it one lower. When breaking it gives no block it may place, it fetches some from its
 * home once, and leaves the block up only when it gets none there. Shared by trees and builds, and by the work between jobs that takes down what any path placed.
 */
final class ScaffoldCleanup {
	private static final double REACH = 4.0;
	/**
	 * How many times cleanup may pillar up to scaffold it cannot otherwise reach. Each retry places
	 * new scaffolding that has to come down as well, so this bound keeps cleanup from looping.
	 */
	private static final int RETRIES = 8;
	/** How many scaffold blocks it fetches from home to tower down with. */
	private static final int FETCH = 8;

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
	/** Whether it is on its way to fetch scaffold blocks from home, and whether it went once since {@link #reset}. */
	private boolean fetching;
	private boolean fetched;

	ScaffoldCleanup(AntBot bot, PathFollower follower, BlockBreaker breaker, Function<Goal, PathFollower.Status> travel, Runnable stopTravel) {
		this.bot = bot;
		this.follower = follower;
		this.breaker = breaker;
		this.travel = travel;
		this.stopTravel = stopTravel;
		this.sight = (feet, target, broken) -> LineOfSight.sees(bot.level(), feet, target, broken);
	}

	/**
	 * Where the bot can take the block at {@code pos} down: within reach, and in sight of it. For one over water, or a
	 * bot in water, that may be swimming at the surface, so a bot in a pool it cannot climb out of swims to its scaffold
	 * rather than dig into the bank.
	 */
	private Goal inSight(BlockPos pos) {
		Goal goal = new Goal.InSight(pos, REACH, this.sight);
		boolean wet = this.bot.isInWater() || !this.bot.level().getFluidState(pos).isEmpty() || !this.bot.level().getFluidState(pos.below()).isEmpty();
		return wet ? new Goal.Afloat(goal) : goal;
	}

	/** Whether the bot is at a spot where {@code goal} is reached: standing, or swimming for one that may end afloat. */
	private boolean at(Goal goal, BlockPos feet) {
		return goal.isReached(feet) && (this.bot.onGround() || goal.mayEndAfloat() && this.bot.isInWater());
	}

	/** Starts afresh, such as for a new tree: every block is tried again, with all retries. */
	void reset() {
		this.unreachable.clear();
		this.abandoned.clear();
		this.retrying = null;
		this.retriesLeft = RETRIES;
		this.fetching = false;
		this.fetched = false;
	}

	/** Stops pillaring up to a block, such as when the bot turns to other work. */
	void cancelRetry() {
		this.retrying = null;
		this.fetching = false;
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
		if (this.fetching) {
			this.fetch(level, feet);
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
		if (this.at(reach, feet)) {
			this.stopTravel.run();
			if (next.equals(feet.below()) && fallUnder(level, next) > PathFollower.MAX_DROP) {
				// Taking it down from under itself would drop the bot further than a path may. It towers down instead:
				// a block under this one first, so taking it down drops the bot one block.
				BlockPos under = next.below();
				if (!level.getBlockState(under).canBeReplaced()) {
					this.abandoned.add(next);
				} else if (!this.follower.placeBlock(level, under)) {
					// Carrying none, it digs down: breaks this one for its block and places that under it in the same
					// tick, before it falls. If breaking it gives no block to place, it fetches some from home, once.
					if (!dropsScaffold(level, next)) {
						if (this.fetched || !this.homeHoldsScaffold(level)) {
							this.abandoned.add(next);
						} else {
							this.fetched = true;
							this.fetching = true;
						}
					} else if (this.breaker.tick(level, next, true)) {
						placed.remove(next);
						this.follower.placeBlock(level, under);
					}
				}
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

	/** Whether breaking the block at {@code pos} with what the bot holds gives it a block its follower places, with room in its pack. */
	private boolean dropsScaffold(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		ItemStack tool = this.bot.getMainHandItem();
		return Tools.canHarvest(tool, state) && ColonyWorkGoal.freeSlots(this.bot.getInventory()) > 0
			&& Block.getDrops(state, level, pos, level.getBlockEntity(pos), this.bot, tool).stream().anyMatch(drop -> drop.is(this.follower.scaffold()));
	}

	/** Whether the bot's home holds blocks its follower places. */
	private boolean homeHoldsScaffold(ServerLevel level) {
		BlockPos home = this.bot.getHome();
		Container core = home == null ? null : Storage.containerAt(level, home);
		return core != null && PathFollower.scaffoldCount(core, this.follower.scaffold()) > 0;
	}

	/** Goes to the bot's home and takes {@link #FETCH} blocks its follower places; gives up when it cannot get there. */
	private void fetch(ServerLevel level, BlockPos feet) {
		BlockPos home = this.bot.getHome();
		Container core = home == null ? null : Storage.containerAt(level, home);
		if (core == null) {
			this.fetching = false;
			return;
		}
		Goal reach = this.inSight(home);
		if (this.at(reach, feet)) {
			this.stopTravel.run();
			Storage.moveItems(core, this.bot.getInventory(), stack -> stack.is(this.follower.scaffold()), FETCH);
			this.fetching = false;
		} else if (this.travel.apply(reach) == PathFollower.Status.FAILED) {
			this.fetching = false;
		}
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
		if (!this.bot.getScaffold().contains(target) || this.at(reach, feet)) {
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
