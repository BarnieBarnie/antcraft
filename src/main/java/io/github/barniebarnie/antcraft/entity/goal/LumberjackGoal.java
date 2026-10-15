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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Lumberjack work loop: get a tree (from the colony job board, or by searching near
 * home when not in a colony), chop every log, clear the leaves the tree leaves behind,
 * remove the scaffold blocks it placed to get up there, pick up the drops, replant a
 * sapling, and bring logs to the home chest or colony core. Before a tree it tops up
 * its scaffold blocks from home, so it can pillar up to logs it cannot reach.
 */
public class LumberjackGoal extends net.minecraft.world.entity.ai.goal.Goal {
	private static final int SEARCH_RADIUS = 24;
	private static final double REACH = 4.0;
	private static final int DEPOSIT_AT_LOGS = 32;
	private static final int COLLECT_RADIUS = 8;
	private static final int IDLE_RESCAN_TICKS = 40;
	/** Checking the job board is cheap, so colony bots look for work more often than solo bots search. */
	private static final int IDLE_JOB_CHECK_TICKS = 10;
	/** Fetches scaffold blocks from home when carrying fewer than this. */
	private static final int RESTOCK_BELOW = 8;
	private static final int SCAFFOLD_STOCK = 16;
	/** How long to wait before fetching scaffold again after home could not be reached. */
	private static final int RESTOCK_RETRY_TICKS = 600;
	/** Leaves need a few ticks to notice their logs are gone. */
	private static final int LEAF_SETTLE_TICKS = 20;
	/** Leaves decay further than this from a log, so a tree's canopy cannot reach further. */
	private static final int LEAF_RANGE = LeavesBlock.DECAY_DISTANCE - 1;

	private enum State { SEARCH, RESTOCK, CHOP, LEAVES, CLEANUP, COLLECT, REPLANT, DEPOSIT }

	/** States in which placed blocks are scaffolding for the current tree, to be removed afterwards. */
	private static final Set<State> TREE_WORK = EnumSet.of(State.CHOP, State.LEAVES, State.CLEANUP);

	private final AntBot bot;
	private final PathFollower follower;
	private final BlockBreaker breaker;
	private State state = State.SEARCH;
	private TreeScanner.@Nullable Tree tree;
	private @Nullable Goal travelGoal;
	private final Set<BlockPos> unreachableLogs = new HashSet<>();
	private final Set<Integer> unreachableItems = new HashSet<>();
	private final Set<BlockPos> unreachableLeaves = new HashSet<>();
	private @Nullable BlockPos leaf;
	/** Scaffold blocks placed while working on the current tree. */
	private final Set<BlockPos> placed = new HashSet<>();
	private final Set<BlockPos> unreachablePlaced = new HashSet<>();
	private int cooldown;
	private int restockCooldown;
	private @Nullable UUID jobId;

	public LumberjackGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.breaker = new BlockBreaker(bot);
		this.follower.setPlaceListener(pos -> {
			if (TREE_WORK.contains(this.state)) {
				this.placed.add(pos);
			}
		});
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
		// Interrupted mid-tree: take the scaffolding down before anything else.
		this.enter(this.placed.isEmpty() ? State.SEARCH : State.CLEANUP);
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
			case RESTOCK -> this.restock(level);
			case CHOP -> this.chop(level);
			case LEAVES -> this.leaves(level);
			case CLEANUP -> this.cleanup(level);
			case COLLECT -> this.collect(level);
			case REPLANT -> this.replant(level);
			case DEPOSIT -> this.deposit(level);
		}
	}

	private void enter(State next) {
		this.state = next;
		this.bot.setWorking(next != State.SEARCH);
		this.travelGoal = null;
		this.follower.stop();
		this.breaker.reset();
	}

	private void search(ServerLevel level) {
		if (this.restockCooldown > 0) {
			this.restockCooldown--;
		}
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		int logs = countLogs(this.bot.getInventory());
		if (logs >= DEPOSIT_AT_LOGS) {
			this.enter(State.DEPOSIT);
			return;
		}
		if (this.restockCooldown == 0 && PathFollower.scaffoldCount(this.bot.getInventory()) < RESTOCK_BELOW && this.homeScaffold(level) > 0) {
			this.enter(State.RESTOCK);
			return;
		}
		Optional<TreeScanner.Tree> found = this.nextTree(level);
		if (found.isPresent()) {
			this.tree = found.get();
			this.unreachableLogs.clear();
			this.unreachableLeaves.clear();
			this.unreachablePlaced.clear();
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

	private int homeScaffold(ServerLevel level) {
		Container home = this.bot.getHome() == null ? null : Storage.containerAt(level, this.bot.getHome());
		return home == null ? 0 : PathFollower.scaffoldCount(home);
	}

	private void restock(ServerLevel level) {
		BlockPos home = this.bot.getHome();
		Container container = home == null ? null : Storage.containerAt(level, home);
		if (container == null) {
			this.enter(State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(home, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			this.bot.getLookControl().setLookAt(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5);
			int wanted = SCAFFOLD_STOCK - PathFollower.scaffoldCount(this.bot.getInventory());
			Storage.moveItems(container, this.bot.getInventory(), stack -> stack.is(PathFollower.SCAFFOLD_BLOCKS), wanted);
			this.enter(State.SEARCH);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.restockCooldown = RESTOCK_RETRY_TICKS;
			this.enter(State.SEARCH);
		}
	}

	private Optional<Colony> colony(ServerLevel level) {
		UUID id = this.bot.getColonyId();
		return id == null ? Optional.empty() : ColonyManager.get(level).byId(id);
	}

	private void chop(ServerLevel level) {
		BlockPos next = null;
		for (BlockPos log : this.tree.logs()) {
			if (level.getBlockState(log).is(BlockTags.LOGS) && !this.unreachableLogs.contains(log) && !this.placed.contains(log)) {
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
			this.enter(State.LEAVES);
			this.leaf = null;
			this.cooldown = LEAF_SETTLE_TICKS;
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

	/** Breaks the leaves that no longer touch a log; they would decay anyway, but slowly. */
	private void leaves(ServerLevel level) {
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		if (this.leaf == null || !isDecaying(level.getBlockState(this.leaf))) {
			this.leaf = this.nearestDecayingLeaf(level);
			this.travelGoal = null;
			if (this.leaf == null) {
				this.enter(State.CLEANUP);
				return;
			}
		}
		Goal reach = new Goal.Reach(this.leaf, REACH);
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
			this.travelGoal = null;
			if (this.breaker.tick(level, this.leaf, true)) {
				this.leaf = null;
			}
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.unreachableLeaves.add(this.leaf);
			this.leaf = null;
		}
	}

	private @Nullable BlockPos nearestDecayingLeaf(ServerLevel level) {
		BlockPos min = this.tree.logs().getFirst();
		BlockPos max = min;
		for (BlockPos log : this.tree.logs()) {
			min = BlockPos.min(min, log);
			max = BlockPos.max(max, log);
		}
		BlockPos feet = this.bot.blockPosition();
		BlockPos nearest = null;
		for (BlockPos pos : BlockPos.betweenClosed(min.offset(-LEAF_RANGE, -LEAF_RANGE, -LEAF_RANGE), max.offset(LEAF_RANGE, LEAF_RANGE, LEAF_RANGE))) {
			if (isDecaying(level.getBlockState(pos)) && !this.unreachableLeaves.contains(pos)
				&& (nearest == null || pos.distSqr(feet) < nearest.distSqr(feet))) {
				nearest = pos.immutable();
			}
		}
		return nearest;
	}

	/** Natural leaves cut off from every log. */
	private static boolean isDecaying(BlockState state) {
		return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)
			&& state.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE;
	}

	/** Takes down the scaffold blocks placed for this tree, getting them back. Blocks within reach go first, top down. */
	private void cleanup(ServerLevel level) {
		BlockPos feet = this.bot.blockPosition();
		this.placed.removeIf(pos -> !level.getBlockState(pos).getBlock().asItem().getDefaultInstance().is(PathFollower.SCAFFOLD_BLOCKS));
		BlockPos next = this.placed.stream()
			.filter(pos -> !this.unreachablePlaced.contains(pos))
			.max(Comparator.<BlockPos, Boolean>comparing(pos -> new Goal.Reach(pos, REACH).isReached(feet)).thenComparingInt(BlockPos::getY))
			.orElse(null);
		if (next == null) {
			this.placed.clear();
			this.unreachableItems.clear();
			this.enter(this.tree != null ? State.COLLECT : State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(next, REACH);
		if (reach.isReached(feet) && this.bot.onGround()) {
			this.travelGoal = null;
			if (this.breaker.tick(level, next, true)) {
				this.placed.remove(next);
			}
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.unreachablePlaced.add(next);
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
