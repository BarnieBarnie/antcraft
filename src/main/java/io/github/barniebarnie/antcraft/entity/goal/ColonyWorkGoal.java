package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.entity.navigation.LineOfSight;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Shared plumbing for the work {@link WorkerGoal} runs: travelling, reaching blocks, fetching tools,
 * and a cooldown before trying again after something failed. Work between jobs goes in {@link #betweenJobs}.
 */
public abstract class ColonyWorkGoal {
	protected static final double REACH = 4.0;
	protected static final int IDLE_TICKS = 20;
	/** How often a bot looks for a better tool in the core. */
	protected static final int UPGRADE_CHECK_TICKS = 100;
	/** How far above and below the core {@link #nearest} looks. */
	private static final int SCAN_HEIGHT = 4;

	protected final AntBot bot;
	protected final PathFollower follower;
	protected final BlockBreaker breaker;
	/** What the bot can see, to break only blocks in sight; one for good, as goals compare by value. */
	protected final Goal.Sight sight;
	private @Nullable Goal travelGoal;
	protected int cooldown;
	/** The better tool the bot is on its way to fetch from the core, or null. */
	private @Nullable Item upgrade;
	private long nextUpgradeCheck;

	protected ColonyWorkGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.breaker = new BlockBreaker(bot);
		this.sight = (feet, target) -> LineOfSight.sees(bot.level(), feet, target);
	}

	/**
	 * For {@link WorkerGoal}: works one tick on what there is to do between jobs, after waiting out a cooldown.
	 * Returns false when there is nothing.
	 */
	public boolean upkeep(ServerLevel level, Colony colony) {
		if (this.cooldown > 0) {
			this.cooldown--;
			return true;
		}
		return this.betweenJobs(level, colony);
	}

	/** Whether the bot is carrying a load to put away, which a job that turns up does not take it from. */
	protected boolean carriesLoad() {
		return false;
	}

	/** Works one tick on what there is to do between jobs, such as storing what a job left. Returns false when there is nothing. */
	protected boolean betweenJobs(ServerLevel level, Colony colony) {
		return false;
	}

	/**
	 * Takes the best sword in the core when it beats the weapon the bot carries, so the bot fights hostile mobs
	 * armed. Returns true while busy doing so.
	 */
	protected boolean armFromCore(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null) {
			return false;
		}
		float carried = Tools.weaponScore(this.bot.getMainHandItem());
		for (int i = 0; i < this.bot.getInventory().getContainerSize(); i++) {
			carried = Math.max(carried, Tools.weaponScore(this.bot.getInventory().getItem(i)));
		}
		int slot = -1;
		float best = carried;
		for (int i = 0; i < core.getContainerSize(); i++) {
			ItemStack stack = core.getItem(i);
			if (stack.is(ItemTags.SWORDS) && Tools.weaponScore(stack) > best) {
				best = Tools.weaponScore(stack);
				slot = i;
			}
		}
		if (slot < 0) {
			return false;
		}
		if (this.reach(colony.core())) {
			this.bot.getInventory().addItem(core.removeItem(slot, 1));
			Tools.equipBestWeapon(this.bot);
		}
		return true;
	}

	/**
	 * Now and then looks for a better {@code kind} of tool: it fetches one from the core, leaving
	 * the ones it replaces there, or orders the best one the colony can make. Returns true while the
	 * bot is busy fetching one, so the caller skips its own work this tick.
	 */
	protected boolean upgradeTool(ServerLevel level, Colony colony, ToolKind kind) {
		return this.upgradeTool(level, colony, kind, () -> false);
	}

	/** Makes the next {@link #upgradeTool} look for a better tool now, instead of when its next check is due. */
	protected void checkToolNow() {
		this.nextUpgradeCheck = 0;
	}

	/** Like {@link #upgradeTool(ServerLevel, Colony, ToolKind)}; a bot without one orders one too while it {@code needs} one. */
	protected boolean upgradeTool(ServerLevel level, Colony colony, ToolKind kind, BooleanSupplier needs) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null) {
			this.upgrade = null;
			return false;
		}
		if (this.upgrade == null) {
			if (level.getGameTime() < this.nextUpgradeCheck) {
				return false;
			}
			this.nextUpgradeCheck = level.getGameTime() + UPGRADE_CHECK_TICKS;
			Optional<Item> better = kind.findUpgrade(level, this.bot, core, colony, needs);
			if (better.isEmpty()) {
				return false;
			}
			this.upgrade = better.get();
			this.resetMovement();
		}
		if (this.reach(colony.core())) {
			kind.swap(this.bot, core, this.upgrade);
			this.upgrade = null;
			this.resetMovement();
		}
		return true;
	}

	protected void resetMovement() {
		this.stopTravel();
		this.breaker.reset();
	}

	/** Stops travelling, leaving a block being broken alone; the next {@link #travel} plans a fresh path. */
	protected void stopTravel() {
		this.follower.stop();
		this.travelGoal = null;
	}

	protected PathFollower.Status travel(Goal goal) {
		if (!goal.equals(this.travelGoal)) {
			this.travelGoal = goal;
			this.follower.start(goal);
		}
		PathFollower.Status status = this.follower.tick();
		if (status != PathFollower.Status.RUNNING) {
			this.travelGoal = null;
		}
		return status;
	}

	/**
	 * Moves toward {@code pos} until it is within reach. Returns true once there.
	 * If no path can be found, waits a while before trying again.
	 */
	protected boolean reach(BlockPos pos) {
		PathFollower.Status status = this.approach(new Goal.Reach(pos, REACH), pos);
		if (status == PathFollower.Status.FAILED) {
			this.cooldown = IDLE_TICKS;
		}
		return status == PathFollower.Status.ARRIVED;
	}

	/**
	 * Moves toward a spot where {@code goal} is reached, then looks at {@code pos}: ARRIVED once
	 * there, standing or, for a goal that {@link Goal#mayEndAfloat may end afloat}, swimming; FAILED
	 * when no path can be found, and RUNNING meanwhile.
	 */
	protected PathFollower.Status approach(Goal goal, BlockPos pos) {
		if (goal.isReached(this.bot.blockPosition()) && (this.bot.onGround() || goal.mayEndAfloat() && this.bot.isInWater())) {
			this.travelGoal = null;
			this.bot.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			return PathFollower.Status.ARRIVED;
		}
		return this.travel(goal) == PathFollower.Status.FAILED ? PathFollower.Status.FAILED : PathFollower.Status.RUNNING;
	}

	/** Where the bot can break the block at {@code pos}: within reach, and in sight of it, as a player must be. */
	protected Goal inSight(BlockPos pos) {
		return new Goal.InSight(pos, REACH, this.sight);
	}

	/**
	 * Whether {@code state} can go at {@code pos} without boxing in a bot or mob standing there,
	 * as for a player placing a block. If the bot itself is in the way, it steps to the middle of
	 * its own block, out of the spot. A bot with nothing to do never moves off on its own, such as
	 * one left floating in the last water of a pond, so one in the way is called over to this bot.
	 */
	protected boolean isFreeToPlace(ServerLevel level, BlockPos pos, BlockState state) {
		if (level.isUnobstructed(state, pos, CollisionContext.empty())) {
			return true;
		}
		if (this.bot.getBoundingBox().intersects(new AABB(pos))) {
			BlockPos feet = this.bot.blockPosition();
			this.bot.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 1.0);
		}
		for (AntBot idle : level.getEntitiesOfClass(AntBot.class, new AABB(pos), other -> other != this.bot && isIdle(level, other))) {
			idle.setWalkTarget(this.bot.blockPosition());
		}
		return false;
	}

	/** Neither at work, walking somewhere nor holding a job. */
	private static boolean isIdle(ServerLevel level, AntBot bot) {
		return !bot.isWorking() && bot.getWalkTarget() == null && Optional.ofNullable(bot.getColonyId())
			.flatMap(id -> ColonyManager.get(level).byId(id)).flatMap(colony -> colony.board().jobOf(bot.getUUID())).isEmpty();
	}

	/** Scoops the fluid at {@code pos} up into the empty bucket in {@code slot}, as a player would. Returns the filled bucket, or empty if nothing could be scooped. */
	protected ItemStack scoop(ServerLevel level, BlockPos pos, int slot) {
		BlockState state = level.getBlockState(pos);
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		ItemStack filled = state.getBlock() instanceof BucketPickup pickup ? pickup.pickupBlock(this.bot, level, pos, state) : ItemStack.EMPTY;
		if (!filled.isEmpty()) {
			this.bot.getInventory().setItem(slot, filled);
		}
		return filled;
	}

	/** The block in the colony territory nearest {@code from} that is {@code wanted}, or null. */
	protected static @Nullable BlockPos nearest(ServerLevel level, Colony colony, BlockPos from, Predicate<BlockPos> wanted) {
		BlockPos core = colony.core();
		int r = colony.radius();
		BlockPos nearest = null;
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-r, -SCAN_HEIGHT, -r), core.offset(r, SCAN_HEIGHT, r))) {
			if (wanted.test(pos) && (nearest == null || pos.distSqr(from) < nearest.distSqr(from))) {
				nearest = pos.immutable();
			}
		}
		return nearest;
	}

	protected static int findSlot(Container container, Predicate<ItemStack> filter) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (!container.getItem(i).isEmpty() && filter.test(container.getItem(i))) {
				return i;
			}
		}
		return -1;
	}

	protected static int freeSlots(Container inventory) {
		int free = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}
}
