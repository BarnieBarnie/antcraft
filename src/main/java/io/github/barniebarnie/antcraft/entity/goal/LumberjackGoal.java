package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.LevelTreeView;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.TreeScanner;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Lumberjack work loop: get a tree (from the colony job board, or by searching near
 * home when not in a colony), chop every log, pick up the drops, replant a sapling,
 * and bring logs to the home chest or colony core.
 */
public class LumberjackGoal extends net.minecraft.world.entity.ai.goal.Goal {
	private static final int SEARCH_RADIUS = 24;
	private static final double REACH = 4.0;
	private static final int DEPOSIT_AT_LOGS = 32;
	private static final int COLLECT_RADIUS = 8;
	private static final int IDLE_RESCAN_TICKS = 40;
	/** Checking the job board is cheap, so colony bots look for work more often than solo bots search. */
	private static final int IDLE_JOB_CHECK_TICKS = 10;

	private enum State { SEARCH, CHOP, COLLECT, REPLANT, DEPOSIT }

	private final AntBot bot;
	private final PathFollower follower;
	private final BlockBreaker breaker;
	private State state = State.SEARCH;
	private TreeScanner.@Nullable Tree tree;
	private @Nullable Goal travelGoal;
	private final Set<BlockPos> unreachableLogs = new HashSet<>();
	private final Set<Integer> unreachableItems = new HashSet<>();
	private int cooldown;
	private @Nullable UUID jobId;

	public LumberjackGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.breaker = new BlockBreaker(bot);
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.bot.getRole() == BotRole.LUMBERJACK && this.bot.getWalkTarget() == null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		this.enter(State.SEARCH);
	}

	@Override
	public void stop() {
		this.follower.stop();
		this.breaker.reset();
		this.travelGoal = null;
	}

	@Override
	public void tick() {
		if (!(this.bot.level() instanceof ServerLevel level)) {
			return;
		}
		if (this.jobId != null && level.getGameTime() % 20 == 0) {
			this.colony(level).ifPresent(colony -> colony.board().heartbeat(this.bot.getUUID(), level.getGameTime()));
		}
		switch (this.state) {
			case SEARCH -> this.search(level);
			case CHOP -> this.chop(level);
			case COLLECT -> this.collect(level);
			case REPLANT -> this.replant(level);
			case DEPOSIT -> this.deposit(level);
		}
	}

	private void enter(State next) {
		this.state = next;
		this.travelGoal = null;
		this.follower.stop();
		this.breaker.reset();
	}

	private void search(ServerLevel level) {
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		int logs = countLogs(this.bot.getInventory());
		if (logs >= DEPOSIT_AT_LOGS) {
			this.enter(State.DEPOSIT);
			return;
		}
		Optional<TreeScanner.Tree> found = this.nextTree(level);
		if (found.isPresent()) {
			this.tree = found.get();
			this.unreachableLogs.clear();
			this.enter(State.CHOP);
		} else if (logs > 0 && this.bot.getHome() != null) {
			this.enter(State.DEPOSIT);
		} else {
			this.cooldown = this.bot.getColonyId() != null ? IDLE_JOB_CHECK_TICKS : IDLE_RESCAN_TICKS;
		}
	}

	/** In a colony, claims a tree job from the board; otherwise searches around home. */
	private Optional<TreeScanner.Tree> nextTree(ServerLevel level) {
		LevelTreeView view = new LevelTreeView(level);
		if (this.bot.getColonyId() == null) {
			BlockPos origin = this.bot.getHome() != null ? this.bot.getHome() : this.bot.blockPosition();
			return TreeScanner.findNearest(view, origin, SEARCH_RADIUS);
		}
		Optional<Colony> colony = this.colony(level);
		if (colony.isEmpty()) {
			// The colony was dissolved: carry on alone.
			this.bot.setColonyId(null);
			this.bot.setHome(null);
			return Optional.empty();
		}
		while (true) {
			Optional<Job> job = colony.get().board().claim(this.bot.getUUID(), Set.of(JobType.CHOP_TREE), this.bot.blockPosition(), level.getGameTime());
			if (job.isEmpty()) {
				return Optional.empty();
			}
			Optional<TreeScanner.Tree> tree = TreeScanner.treeAt(view, job.get().target());
			if (tree.isPresent()) {
				this.jobId = job.get().id();
				return tree;
			}
			// The tree is gone; drop the job and try the next one.
			colony.get().board().complete(job.get().id());
		}
	}

	private Optional<Colony> colony(ServerLevel level) {
		UUID id = this.bot.getColonyId();
		return id == null ? Optional.empty() : ColonyManager.get(level).byId(id);
	}

	private void chop(ServerLevel level) {
		BlockPos next = null;
		for (BlockPos log : this.tree.logs()) {
			if (level.getBlockState(log).is(BlockTags.LOGS) && !this.unreachableLogs.contains(log)) {
				next = log;
				break;
			}
		}
		if (next == null) {
			if (this.jobId != null) {
				UUID done = this.jobId;
				this.colony(level).ifPresent(colony -> colony.board().complete(done));
				this.jobId = null;
			}
			this.unreachableItems.clear();
			this.enter(State.COLLECT);
			return;
		}

		Goal reach = new Goal.Reach(next, REACH);
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
			this.travelGoal = null;
			this.breaker.tick(level, next);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.unreachableLogs.add(next);
		}
	}

	private void collect(ServerLevel level) {
		AABB area = new AABB(this.tree.base()).inflate(COLLECT_RADIUS);
		ItemEntity nearest = level.getEntitiesOfClass(ItemEntity.class, area,
				item -> item.isAlive() && !this.unreachableItems.contains(item.getId()) && this.bot.wantsToPickUp(level, item.getItem()))
			.stream()
			.min(Comparator.comparingDouble(this.bot::distanceToSqr))
			.orElse(null);
		if (nearest == null) {
			this.enter(State.REPLANT);
			return;
		}
		if (this.travel(new Goal.Near(nearest.blockPosition(), 0)) == PathFollower.Status.FAILED) {
			this.unreachableItems.add(nearest.getId());
			this.travelGoal = null;
		}
	}

	private void replant(ServerLevel level) {
		BlockPos base = this.tree.base();
		ItemStack sapling = findItem(this.bot.getInventory(), stack -> stack.is(ItemTags.SAPLINGS));
		if (sapling == null || !level.getBlockState(base).isAir() || !level.getBlockState(base.below()).is(BlockTags.DIRT)) {
			this.enter(State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(base, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			if (sapling.getItem() instanceof BlockItem blockItem) {
				level.setBlockAndUpdate(base, blockItem.getBlock().defaultBlockState());
				sapling.shrink(1);
			}
			this.enter(State.SEARCH);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.enter(State.SEARCH);
		}
	}

	private void deposit(ServerLevel level) {
		BlockPos home = this.bot.getHome();
		Container container = home == null ? null : Storage.containerAt(level, home);
		if (container == null) {
			this.cooldown = IDLE_RESCAN_TICKS;
			this.enter(State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(home, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			this.bot.getLookControl().setLookAt(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5);
			Storage.moveItems(this.bot.getInventory(), container, stack -> stack.is(ItemTags.LOGS));
			this.enter(State.SEARCH);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.cooldown = IDLE_RESCAN_TICKS;
			this.enter(State.SEARCH);
		}
	}

	private PathFollower.Status travel(Goal goal) {
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

	private static int countLogs(SimpleContainer inventory) {
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(ItemTags.LOGS)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static @Nullable ItemStack findItem(SimpleContainer inventory, java.util.function.Predicate<ItemStack> predicate) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && predicate.test(stack)) {
				return stack;
			}
		}
		return null;
	}
}
