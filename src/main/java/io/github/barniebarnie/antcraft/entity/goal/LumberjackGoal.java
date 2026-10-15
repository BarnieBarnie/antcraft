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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Lumberjack work loop: get a tree (from the colony job board, or by searching near
 * home when not in a colony), chop every log, remove the scaffold blocks it placed to get
 * up there, pick up the drops, replant a sapling, and bring logs to the home chest or colony
 * core. It leaves the canopy alone (only breaking leaves in its way): with the logs gone the
 * leaves decay by themselves. That is slow, so when it could not replant it comes back later
 * for saplings and apples the leaves dropped. Before a tree it tops up
 * its scaffold blocks from home, so it can pillar up to logs it cannot reach. It only uses
 * scaffold blocks that break quickly by hand ({@link #SCAFFOLD}), so cleanup is fast.
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
	/**
	 * How long to keep coming back to a felled tree for the saplings its decaying leaves drop. Leaves
	 * take minutes to decay, and dropped items despawn after five.
	 */
	private static final int REVISIT_TICKS = 12000;
	/** At most this many felled trees are remembered to come back to; the oldest is forgotten first. */
	private static final int MAX_REVISITS = 8;
	/**
	 * How many times per tree cleanup may pillar up to scaffold it cannot otherwise reach. Each retry
	 * places new scaffolding that has to come down as well, so this bound keeps cleanup from looping.
	 */
	private static final int SCAFFOLD_RETRIES = 8;
	/** The scaffold blocks lumberjacks fetch and place: ones they can take down quickly without a tool. */
	private static final TagKey<Item> SCAFFOLD = PathFollower.FAST_SCAFFOLD_BLOCKS;

	private enum State { SEARCH, RESTOCK, CHOP, CLEANUP, COLLECT, REPLANT, DEPOSIT }

	/**
	 * States in which placed blocks are scaffolding for the current tree, to be removed afterwards.
	 * Not {@link State#CLEANUP}: cleanup never places blocks, or it would have to remove them again.
	 */
	private static final Set<State> TREE_WORK = EnumSet.of(State.CHOP);
	/**
	 * States that never place blocks. Collecting comes after cleanup, so blocks placed then would be
	 * left behind; drops it cannot walk to (say, on a canopy left to decay) fall down later.
	 */
	private static final Set<State> NO_PLACING = EnumSet.of(State.CLEANUP, State.COLLECT);

	private final AntBot bot;
	private final PathFollower follower;
	private final BlockBreaker breaker;
	private State state = State.SEARCH;
	private TreeScanner.@Nullable Tree tree;
	private @Nullable Goal travelGoal;
	private final Set<BlockPos> unreachableLogs = new HashSet<>();
	private final Set<Integer> unreachableItems = new HashSet<>();
	/** Where the current tree stood: drops are collected around it and a sapling planted there. */
	private @Nullable BlockPos site;
	/** Felled trees not yet replanted, with the game time they were felled, to come back to for saplings. */
	private final Map<BlockPos, Long> revisits = new LinkedHashMap<>();
	/** Scaffold that cannot be reached without placing more; retried once the rest is down. */
	private final Set<BlockPos> unreachablePlaced = new HashSet<>();
	/** Scaffold that could not be reached even by pillaring; left behind. */
	private final Set<BlockPos> abandonedPlaced = new HashSet<>();
	/** The scaffold block cleanup is pillaring up to, or null when not retrying one. */
	private @Nullable BlockPos retrying;
	private int retriesLeft = SCAFFOLD_RETRIES;
	private int cooldown;
	private int restockCooldown;
	private @Nullable UUID jobId;

	public LumberjackGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.breaker = new BlockBreaker(bot);
		this.follower.setScaffold(SCAFFOLD);
		this.follower.setPlaceListener(pos -> {
			if (TREE_WORK.contains(this.state) || this.retrying != null) {
				this.placed().add(pos);
			}
		});
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	/**
	 * Scaffold blocks placed while working on the current tree. The bot keeps them, so they are saved
	 * with it and cleanup resumes after loading. (Goals are made before the bot's fields are set, so
	 * this is looked up each time.)
	 */
	private Set<BlockPos> placed() {
		return this.bot.getScaffold();
	}

	/**
	 * Treats the block at {@code pos} as scaffolding this bot placed for its current tree,
	 * so it is taken down in the next cleanup. Lets game tests set up leftover scaffolding.
	 */
	public void adoptScaffold(BlockPos pos) {
		this.placed().add(pos.immutable());
	}

	/**
	 * Also runs for a bot that is no longer a lumberjack but still has scaffolding up, so it takes
	 * that down before its new role's work. This goal is registered before the other role goals at
	 * the same priority, so it gets the bot first, and they cannot take over while it runs.
	 */
	@Override
	public boolean canUse() {
		return (this.isLumberjack() || !this.placed().isEmpty()) && this.bot.getWalkTarget() == null;
	}

	private boolean isLumberjack() {
		return this.bot.getRole() == BotRole.LUMBERJACK;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		// Interrupted mid-tree: take the scaffolding down before anything else.
		this.enter(this.placed().isEmpty() ? State.SEARCH : State.CLEANUP);
	}

	@Override
	public void stop() {
		this.follower.stop();
		this.breaker.reset();
		this.travelGoal = null;
		if (!this.isLumberjack()) {
			// Done with the scaffolding left from being a lumberjack; the new role reports its own work.
			this.bot.setWorking(false);
		}
	}

	@Override
	public void tick() {
		if (!(this.bot.level() instanceof ServerLevel level)) {
			return;
		}
		if (!this.isLumberjack() && this.state != State.CLEANUP) {
			// The role changed mid-tree: give the tree back and only take the scaffolding down.
			this.releaseJob(level);
			this.enter(State.CLEANUP);
		}
		if (this.jobId != null && level.getGameTime() % 20 == 0) {
			this.colony(level).ifPresent(colony -> colony.board().heartbeat(this.bot.getUUID(), level.getGameTime()));
		}
		switch (this.state) {
			case SEARCH -> this.search(level);
			case RESTOCK -> this.restock(level);
			case CHOP -> this.chop(level);
			case CLEANUP -> this.cleanup(level);
			case COLLECT -> this.collect(level);
			case REPLANT -> this.replant(level);
			case DEPOSIT -> this.deposit(level);
		}
	}

	/** Gives the claimed tree job back to the board, so another lumberjack can take it. */
	private void releaseJob(ServerLevel level) {
		if (this.jobId != null) {
			this.colony(level).ifPresent(colony -> colony.board().release(this.bot.getUUID()));
			this.jobId = null;
		}
	}

	private void enter(State next) {
		this.state = next;
		this.bot.setWorking(next != State.SEARCH);
		this.travelGoal = null;
		this.follower.stop();
		this.follower.setMayPlace(!NO_PLACING.contains(next));
		this.breaker.reset();
		this.retrying = null;
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
		if (this.revisit(level)) {
			return;
		}
		if (this.restockCooldown == 0 && PathFollower.scaffoldCount(this.bot.getInventory(), SCAFFOLD) < RESTOCK_BELOW && this.homeScaffold(level) > 0) {
			this.enter(State.RESTOCK);
			return;
		}
		Optional<TreeScanner.Tree> found = this.nextTree(level);
		if (found.isPresent()) {
			this.tree = found.get();
			this.unreachableLogs.clear();
			this.unreachablePlaced.clear();
			this.abandonedPlaced.clear();
			this.retriesLeft = SCAFFOLD_RETRIES;
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
		return home == null ? 0 : PathFollower.scaffoldCount(home, SCAFFOLD);
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
			int wanted = SCAFFOLD_STOCK - PathFollower.scaffoldCount(this.bot.getInventory(), SCAFFOLD);
			Storage.moveItems(container, this.bot.getInventory(), stack -> stack.is(SCAFFOLD), wanted);
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
			if (level.getBlockState(log).is(BlockTags.LOGS) && !this.unreachableLogs.contains(log) && !this.placed().contains(log)) {
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
			// The leaves decay by themselves now; take the scaffolding down while still up there.
			this.enter(State.CLEANUP);
			return;
		}

		Goal reach = new Goal.Reach(next, REACH);
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
			this.travelGoal = null;
			// Straight into the inventory: dropped logs could land on the canopy, out of reach.
			this.breaker.tick(level, next, true);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.unreachableLogs.add(next);
		}
	}

	/**
	 * Takes down the scaffold blocks placed for this tree, getting them back. Blocks within reach go
	 * first, top down, so a pillar the bot stands on comes down under it. Walking to the others never
	 * places new scaffolding. Blocks that cannot be reached that way are retried once the rest is down:
	 * the bot pillars up to them, and that pillar is taken down in turn, under it. Retries are limited
	 * per tree, and a block it cannot reach even by pillaring is left behind.
	 */
	private void cleanup(ServerLevel level) {
		BlockPos feet = this.bot.blockPosition();
		this.placed().removeIf(pos -> !level.getBlockState(pos).getBlock().asItem().getDefaultInstance().is(PathFollower.SCAFFOLD_BLOCKS));
		if (this.retrying != null) {
			this.retry(feet);
			return;
		}
		BlockPos next = this.placed().stream()
			.filter(pos -> !this.unreachablePlaced.contains(pos) && !this.abandonedPlaced.contains(pos))
			.max(Comparator.<BlockPos, Boolean>comparing(pos -> new Goal.Reach(pos, REACH).isReached(feet)).thenComparingInt(BlockPos::getY))
			.orElse(null);
		if (next == null) {
			BlockPos retry = this.retriesLeft <= 0 ? null : this.placed().stream()
				.filter(pos -> !this.abandonedPlaced.contains(pos))
				.max(Comparator.comparingInt(BlockPos::getY))
				.orElse(null);
			if (retry != null) {
				this.retriesLeft--;
				this.retrying = retry;
				this.travelGoal = null;
				this.follower.setMayPlace(true);
				return;
			}
			this.placed().clear();
			this.unreachableItems.clear();
			this.site = this.tree != null ? this.tree.base() : null;
			this.enter(this.site != null ? State.COLLECT : State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(next, REACH);
		if (reach.isReached(feet) && this.bot.onGround()) {
			this.travelGoal = null;
			if (this.breaker.tick(level, next, true)) {
				this.placed().remove(next);
			}
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.unreachablePlaced.add(next);
		}
	}

	/** Pillars up to the scaffold block being retried; once there, cleanup looks at every block again from up there. */
	private void retry(BlockPos feet) {
		BlockPos target = this.retrying;
		Goal reach = new Goal.Reach(target, REACH);
		if (!this.placed().contains(target) || (reach.isReached(feet) && this.bot.onGround())) {
			this.unreachablePlaced.clear();
			this.endRetry();
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.abandonedPlaced.add(target);
			this.endRetry();
		}
	}

	private void endRetry() {
		this.retrying = null;
		this.travelGoal = null;
		this.follower.stop();
		this.follower.setMayPlace(false);
	}

	private void collect(ServerLevel level) {
		AABB area = new AABB(this.site).inflate(COLLECT_RADIUS);
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
		BlockPos base = this.site;
		if (!level.getBlockState(base).isAir() || !level.getBlockState(base.below()).is(BlockTags.DIRT)) {
			this.revisits.remove(base);
			this.enter(State.SEARCH);
			return;
		}
		ItemStack sapling = findItem(this.bot.getInventory(), stack -> stack.is(ItemTags.SAPLINGS));
		if (sapling == null) {
			// The leaves may still drop one as they decay.
			if (!this.revisits.containsKey(base)) {
				if (this.revisits.size() >= MAX_REVISITS) {
					this.revisits.remove(this.revisits.keySet().iterator().next());
				}
				this.revisits.put(base, level.getGameTime());
			}
			this.enter(State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(base, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			if (sapling.getItem() instanceof BlockItem blockItem) {
				level.setBlockAndUpdate(base, blockItem.getBlock().defaultBlockState());
				sapling.shrink(1);
			}
			this.revisits.remove(base);
			this.enter(State.SEARCH);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.enter(State.SEARCH);
		}
	}

	/**
	 * Goes back to a felled tree that was not replanted once its decaying leaves have dropped a
	 * sapling or apple, to pick the drops up and replant. Forgets trees felled too long ago.
	 */
	private boolean revisit(ServerLevel level) {
		long now = level.getGameTime();
		this.revisits.values().removeIf(felled -> now - felled > REVISIT_TICKS);
		for (BlockPos base : this.revisits.keySet()) {
			boolean dropped = !level.getEntitiesOfClass(ItemEntity.class, new AABB(base).inflate(COLLECT_RADIUS),
				item -> item.isAlive() && !this.unreachableItems.contains(item.getId()) && isLeafDrop(item.getItem())
					&& this.bot.wantsToPickUp(level, item.getItem())).isEmpty();
			if (dropped) {
				this.site = base;
				this.enter(State.COLLECT);
				return true;
			}
		}
		return false;
	}

	private static boolean isLeafDrop(ItemStack stack) {
		return stack.is(ItemTags.SAPLINGS) || stack.is(Items.APPLE);
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
