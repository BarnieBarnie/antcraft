package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.BlockBreaker;
import io.github.barniebarnie.antcraft.entity.navigation.LineOfSight;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.LevelTreeView;
import io.github.barniebarnie.antcraft.work.Replanting;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import io.github.barniebarnie.antcraft.work.TreeScanner;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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
 * Tree work, as the executor of tree and replant jobs for {@link WorkerGoal}, through {@link #begin} and
 * {@link #work}: chop every log of the job's tree, remove the scaffold blocks it placed to get up there, pick
 * up the drops, replant a sapling, and bring logs to the colony core (its home). It leaves the canopy alone
 * (only breaking leaves in its way): with the logs gone the leaves decay by themselves. When it has no sapling
 * to replant it posts a replant job, which a bot takes once a sapling lies near the base or the core holds one.
 * Before a tree it tops up its scaffold blocks from home, so it can pillar up to logs it cannot reach, and
 * when it runs out on the way up it fetches more and goes back to the same tree. It only uses scaffold blocks
 * that break quickly by hand ({@link #SCAFFOLD}), so cleanup is fast. Between trees it swaps its axe for a
 * better one at home, or orders the best axe the colony can make.
 */
public class LumberjackGoal implements WorkerGoal.Executor {
	private static final double REACH = 4.0;
	private static final int COLLECT_RADIUS = 8;
	private static final int IDLE_RESCAN_TICKS = 40;
	/** Fetches scaffold blocks from home when carrying fewer than this. */
	private static final int RESTOCK_BELOW = 8;
	private static final int SCAFFOLD_STOCK = 16;
	/** How long to wait before fetching scaffold again after home could not be reached. */
	private static final int RESTOCK_RETRY_TICKS = 600;
	/** How often, between trees, it looks for a better axe at home. */
	private static final int UPGRADE_CHECK_TICKS = 100;
	/**
	 * A tree with logs it could not reach, even with scaffold, goes back on the job board to retry this long
	 * after; say once there is more scaffold, or a player cleared the way.
	 */
	static final int TREE_RETRY_TICKS = 600;
	/** How many visits a tree gets before its unreachable logs are given up for good. */
	static final int TREE_TRIES = 5;
	/** The scaffold blocks lumberjacks fetch and place: ones they can take down quickly without a tool. */
	private static final TagKey<Item> SCAFFOLD = PathFollower.FAST_SCAFFOLD_BLOCKS;

	private enum State { SEARCH, RESTOCK, UPGRADE, CHOP, CLEANUP, COLLECT, FETCH_SAPLING, REPLANT, DEPOSIT }

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
	/** What it can see, to break only logs in sight; one for good, as goals compare by value. */
	private final Goal.Sight sight;
	/** Where the current tree stood: drops are collected around it and a sapling planted there. */
	private @Nullable BlockPos site;
	/** Where saplings go at the site: its base, or all four spots of a 2x2 trunk. */
	private List<BlockPos> spots = List.of();
	/** The sapling kind of the tree that stood at the site, or empty when any sapling will do. */
	private Optional<Item> sapling = Optional.empty();
	/** The game time the site's tree was felled, from when a 1x1 tree waits for its own sapling kind. */
	private long felled;
	/** Takes the scaffolding for the current tree down; its retries are counted per tree. */
	private final ScaffoldCleanup scaffoldCleanup;
	/** How many scaffold blocks it takes from home at a time. */
	private int scaffoldStock = SCAFFOLD_STOCK;
	/** Restocking mid-tree: it goes back to the current tree afterwards, which stays its own meanwhile. */
	private boolean resumeTree;
	private int cooldown;
	private int restockCooldown;
	private @Nullable UUID jobId;
	/** What became of the job begun through {@link #begin}, for {@link WorkerGoal} to report to the board. */
	private WorkerGoal.@Nullable Outcome outcome;
	/** Whether the job begun is a log of a tree, rather than the tree, so it leaves the drops and replanting to the tree's job. */
	private boolean logJob;
	/**
	 * The logs of the tree whose next log the bot keeps its scaffold up for, between log jobs of that tree, so it
	 * does not pillar up anew for every log; empty when it keeps none up.
	 */
	private List<BlockPos> keptFor = List.of();
	/** Whether the bot still takes its logs home after the job begun. */
	private boolean depositAfterJob;
	/** The better axe it is on its way to fetch from home, or null. */
	private @Nullable Item upgrade;
	private long nextUpgradeCheck;

	public LumberjackGoal(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.breaker = new BlockBreaker(bot);
		this.sight = (feet, target) -> LineOfSight.sees(bot.level(), feet, target);
		this.follower.setScaffold(SCAFFOLD);
		this.scaffoldCleanup = new ScaffoldCleanup(bot, this.follower, this.breaker, this::travel, () -> {
			this.travelGoal = null;
			this.follower.stop();
		});
	}

	/**
	 * Scaffold blocks placed while working on the current tree. The bot keeps them, so they are saved
	 * with it, and taken down between jobs should the tree be left half done (see {@link BuilderGoal}).
	 */
	private Set<BlockPos> placed() {
		return this.bot.getScaffold();
	}

	/** Changes how many scaffold blocks it takes from home at a time. Lets game tests run it short of scaffold. */
	public void setScaffoldStock(int stock) {
		this.scaffoldStock = stock;
	}

	/** Drops the job begun and stops where it is; scaffolding up stays with the bot, to be taken down between jobs. */
	@Override
	public void reset() {
		this.jobId = null;
		this.state = State.SEARCH;
		this.resumeTree = false;
		this.follower.stop();
		this.breaker.reset();
		this.travelGoal = null;
	}

	private void step(ServerLevel level) {
		switch (this.state) {
			case SEARCH -> { }
			case RESTOCK -> this.restock(level);
			case UPGRADE -> this.fetchUpgrade(level);
			case CHOP -> this.chop(level);
			case CLEANUP -> this.cleanup(level);
			case COLLECT -> this.collect(level);
			case FETCH_SAPLING -> this.fetchSapling(level);
			case REPLANT -> this.replant(level);
			case DEPOSIT -> this.deposit(level);
		}
	}

	/**
	 * For {@link WorkerGoal}: starts on a claimed tree or replant job. Returns false when there is nothing
	 * left to do for it: the tree is gone, or the base was replanted or built over.
	 */
	@Override
	public boolean begin(ServerLevel level, Job job) {
		this.outcome = null;
		this.depositAfterJob = true;
		this.keptFor = List.of();
		if (job.type() == JobType.REPLANT) {
			if (job.blocks().stream().noneMatch(spot -> Replanting.isPlantable(level, spot))) {
				return false;
			}
			this.jobId = job.id();
			this.startReplant(level, job);
			return true;
		}
		if (job.type() == JobType.CHOP_LOG) {
			List<BlockPos> logs = logsLeft(level, job);
			if (logs.isEmpty()) {
				return false;
			}
			this.jobId = job.id();
			this.startTree(level, new TreeScanner.Tree(logs.getFirst(), logs));
			this.logJob = true;
			return true;
		}
		// What is left of a tree felled before is found by the logs the job remembers, leaves or not. With
		// its logs gone, by its log jobs, it still picks up the drops and replants it with the job's sapling.
		LevelTreeView view = new LevelTreeView(level);
		Optional<TreeScanner.Tree> tree = TreeScanner.treeAt(view, job.target()).or(() -> TreeScanner.remaining(view, job.target(), job.area()))
			.or(() -> job.area().isEmpty() ? Optional.empty() : Optional.of(new TreeScanner.Tree(job.target(), job.area())));
		if (tree.isEmpty()) {
			return false;
		}
		this.jobId = job.id();
		this.startTree(level, tree.get());
		job.item().ifPresent(sapling -> this.sapling = Optional.of(sapling));
		return true;
	}

	/**
	 * For {@link WorkerGoal}: works one tick on the job begun, and on what follows it, such as taking the
	 * scaffold down and replanting, then on taking logs home or fetching a better axe or scaffold between
	 * trees. It takes all its logs home after each job. Returns false once it is ready for the next job.
	 */
	@Override
	public boolean work(ServerLevel level) {
		if (this.state == State.SEARCH) {
			if (this.restockCooldown > 0) {
				this.restockCooldown--;
			}
			if (this.cooldown > 0) {
				// Waits a while before the next job, say after it could not reach the core.
				this.cooldown--;
				return true;
			}
			if (this.depositAfterJob && countLogs(this.bot.getInventory()) > 0 && this.bot.getHome() != null) {
				// Logs it carries count as colony stock, so it brings them home before its next job: in its
				// inventory no one can craft with them.
				// Once per job, so a full or unreachable core does not keep it from other work.
				this.depositAfterJob = false;
				this.enter(State.DEPOSIT);
			} else if (!this.prepare(level)) {
				return false;
			}
		}
		this.step(level);
		return true;
	}

	/** What became of the job begun, once that is known; reported once. */
	@Override
	public Optional<WorkerGoal.Outcome> takeOutcome() {
		Optional<WorkerGoal.Outcome> taken = Optional.ofNullable(this.outcome);
		this.outcome = null;
		return taken;
	}

	/**
	 * Whether this bot could start on the job now: a replant job once it could get the saplings, logs once it
	 * can get at one of them and no other bot stands on one or below one in its column, and a tree once its log
	 * jobs are done. A bot in the column, such as on the pillar it keeps up for the tree's next log, would leave
	 * this one to climb beside it on a staircase of scaffold, which it falls off when it takes it down.
	 */
	@Override
	public boolean isReady(ServerLevel level, Colony colony, Job job) {
		return switch (job.type()) {
			case REPLANT -> this.hasSaplingsFor(level, colony, job);
			case CHOP_LOG -> {
				List<BlockPos> logs = logsLeft(level, job);
				yield TreeScanner.isReachable(new LevelTreeView(level), logs) && logs.stream().noneMatch(log -> this.otherBotUnder(level, log));
			}
			case CHOP_TREE -> colony.board().treeReady(job);
			default -> true;
		};
	}

	/** Whether another bot stands on {@code log} or below it in its column. */
	private boolean otherBotUnder(ServerLevel level, BlockPos log) {
		return !level.getEntitiesOfClass(AntBot.class, new AABB(log.getX(), level.getMinY(), log.getZ(), log.getX() + 1, log.getY() + 2, log.getZ() + 1),
			other -> other != this.bot && other.getOnPos().getX() == log.getX() && other.getOnPos().getZ() == log.getZ()
				&& other.getOnPos().getY() <= log.getY()).isEmpty();
	}

	/** The logs of a log job still standing: a layer of a tree, or one log for a job saved before logs were grouped. */
	private static List<BlockPos> logsLeft(ServerLevel level, Job job) {
		return job.blocks().stream().filter(log -> level.getBlockState(log).is(BlockTags.LOGS)).toList();
	}

	/** A log of the tree it keeps its scaffold up for: it takes that scaffold down when done (see {@link WorkerGoal}). */
	@Override
	public boolean keepsScaffoldFor(Job job) {
		return job.type() == JobType.CHOP_LOG && this.keptFor.contains(job.target());
	}

	/**
	 * The logs of the tree of the log job just done when another log of it is open and ready for this bot, so it
	 * keeps its scaffold up for that one; else empty.
	 */
	private List<BlockPos> nextLogOfTree(ServerLevel level, BlockPos log) {
		Optional<Colony> colony = this.colony(level);
		if (colony.isEmpty()) {
			return List.of();
		}
		List<Job> jobs = colony.get().board().jobs();
		List<BlockPos> logs = jobs.stream().filter(job -> job.type() == JobType.CHOP_TREE && job.area().contains(log))
			.findFirst().map(Job::area).orElse(List.of());
		boolean next = jobs.stream().anyMatch(job -> job.type() == JobType.CHOP_LOG && job.isOpen() && !job.givenUp()
			&& logs.contains(job.target()) && this.isReady(level, colony.get(), job));
		return next ? logs : List.of();
	}

	/** Gives the claimed job back to the board, so another bot can take it. */
	private void releaseJob(ServerLevel level) {
		this.endJob(WorkerGoal.Outcome.RELEASE);
	}

	/** Takes the claimed job off the board: it is done, or cannot be done any more. */
	private void completeJob(ServerLevel level) {
		this.endJob(WorkerGoal.Outcome.COMPLETE);
	}

	private void endJob(WorkerGoal.Outcome how) {
		if (this.jobId != null) {
			this.outcome = how;
			this.jobId = null;
		}
	}

	/**
	 * Gives the tree back to the board with the logs it could not reach, to be retried later. After its
	 * last try it is given up: it stays on the board, so it is not posted anew, but no one takes it.
	 */
	private void retryJobLater(ServerLevel level) {
		this.endJob(WorkerGoal.Outcome.RETRY);
	}

	private void enter(State next) {
		this.state = next;
		if (next != State.RESTOCK) {
			this.resumeTree = false;
		}
		this.bot.setWorking(next != State.SEARCH);
		this.travelGoal = null;
		this.follower.stop();
		this.follower.setMayPlace(!NO_PLACING.contains(next));
		this.breaker.reset();
		this.scaffoldCleanup.cancelRetry();
	}

	/**
	 * Between trees: fetches a better axe, or tops up its
	 * scaffold blocks. Returns whether it is off to do one.
	 */
	private boolean prepare(ServerLevel level) {
		if (this.checkUpgrade(level)) {
			return true;
		}
		if (this.restockCooldown == 0 && this.needsScaffold() && this.homeScaffold(level) > 0) {
			this.enter(State.RESTOCK);
			return true;
		}
		return false;
	}

	private void startTree(ServerLevel level, TreeScanner.Tree found) {
		this.tree = found;
		this.spots = TreeScanner.replantSpots(found);
		// A 2x2 tree only grows back from four saplings of its own kind; a 1x1 tree is replanted with
		// its own kind too, while one may still turn up (see Replanting.saplingNeeded).
		this.sapling = Replanting.saplingFor(level.getBlockState(found.logs().getFirst()).getBlock());
		this.felled = level.getGameTime();
		this.logJob = false;
		this.unreachableLogs.clear();
		this.scaffoldCleanup.reset();
		this.enter(State.CHOP);
	}

	/**
	 * Replants the job's spots, first getting the saplings: ones it carries, ones lying nearby, or
	 * ones from the core. A 2x2 tree is only planted once it has all four, of its own kind.
	 */
	private void startReplant(ServerLevel level, Job job) {
		this.site = job.target();
		this.spots = job.blocks();
		this.sapling = job.item();
		this.felled = job.posted();
		Predicate<ItemStack> wanted = this.wanted(level);
		if (count(this.bot.getInventory(), wanted) >= this.toPlant(level, this.spots).size()) {
			this.enter(State.REPLANT);
		} else if (this.saplingsNear(level, this.site, wanted) > 0) {
			this.enter(State.COLLECT);
		} else {
			this.enter(State.FETCH_SAPLING);
		}
	}

	/** Whether this bot could get the saplings for the replant job, counting those it carries, nearby and in the core. */
	private boolean hasSaplingsFor(ServerLevel level, Colony colony, Job job) {
		int needed = this.toPlant(level, job.blocks()).size();
		Predicate<ItemStack> wanted = this.wanted(level, job.item(), job.blocks(), job.posted());
		Container core = Storage.containerAt(level, colony.core());
		int available = count(this.bot.getInventory(), wanted) + this.saplingsNear(level, job.target(), wanted)
			+ (core == null ? 0 : count(core, wanted));
		return available >= needed;
	}

	private int saplingsNear(ServerLevel level, BlockPos base, Predicate<ItemStack> wanted) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(base).inflate(COLLECT_RADIUS),
				item -> item.isAlive() && !this.unreachableItems.contains(item.getId()) && wanted.test(item.getItem())
					&& this.bot.wantsToPickUp(level, item.getItem()))
			.stream().mapToInt(item -> item.getItem().getCount()).sum();
	}

	/** The saplings the site's spots take now (see Replanting.wanted). */
	private Predicate<ItemStack> wanted(ServerLevel level) {
		return this.wanted(level, this.sapling, this.spots, this.felled);
	}

	/** The saplings a site of {@code kind} on {@code spots}, felled at {@code felled}, takes now. */
	private Predicate<ItemStack> wanted(ServerLevel level, Optional<Item> kind, List<BlockPos> spots, long felled) {
		return Replanting.wanted(kind, spots.size(), this.toPlant(level, spots).size(), felled, level.getGameTime(),
			AntCraftConfig.replantMatchWaitTicks());
	}

	/** The site's spots to plant now: all of them, or one when a 2x2 site is partly built over (see Replanting.spotsToPlant). */
	private List<BlockPos> toPlant(ServerLevel level, List<BlockPos> spots) {
		return Replanting.spotsToPlant(spots, spot -> Replanting.isPlantable(level, spot));
	}

	private static Predicate<ItemStack> saplingMatcher(Optional<Item> kind) {
		return stack -> kind.map(stack::is).orElseGet(() -> stack.is(ItemTags.SAPLINGS));
	}

	/** Takes the saplings for the replant job from the colony core. */
	private void fetchSapling(ServerLevel level) {
		BlockPos core = this.colony(level).map(Colony::core).orElse(null);
		Container container = core == null ? null : Storage.containerAt(level, core);
		if (container == null) {
			this.releaseJob(level);
			this.enter(State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(core, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			this.bot.getLookControl().setLookAt(core.getX() + 0.5, core.getY() + 0.5, core.getZ() + 0.5);
			Predicate<ItemStack> wanted = this.wanted(level);
			// Its own kind first, should any sapling do by now.
			int toPlant = this.toPlant(level, this.spots).size();
			Storage.moveItems(container, this.bot.getInventory(), saplingMatcher(this.sapling).and(wanted), toPlant - count(this.bot.getInventory(), wanted));
			Storage.moveItems(container, this.bot.getInventory(), wanted, toPlant - count(this.bot.getInventory(), wanted));
			// If another bot took the saplings first, replanting gives the job back.
			this.enter(State.REPLANT);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.releaseJob(level);
			this.cooldown = IDLE_RESCAN_TICKS;
			this.enter(State.SEARCH);
		}
	}

	private boolean needsScaffold() {
		return PathFollower.scaffoldCount(this.bot.getInventory(), SCAFFOLD) < Math.min(RESTOCK_BELOW, this.scaffoldStock);
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
			int wanted = this.scaffoldStock - PathFollower.scaffoldCount(this.bot.getInventory(), SCAFFOLD);
			Storage.moveItems(container, this.bot.getInventory(), stack -> stack.is(SCAFFOLD), wanted);
			this.enter(this.resumeTree ? State.CHOP : State.SEARCH);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.restockCooldown = RESTOCK_RETRY_TICKS;
			this.enter(this.resumeTree ? State.CHOP : State.SEARCH);
		}
	}

	/**
	 * Now and then, between trees, heads home, the core, for a better axe; when it holds no better axe
	 * the bot orders the best one the colony can make. Returns true when it is now on its way to fetch one.
	 */
	private boolean checkUpgrade(ServerLevel level) {
		if (level.getGameTime() < this.nextUpgradeCheck) {
			return false;
		}
		this.nextUpgradeCheck = level.getGameTime() + UPGRADE_CHECK_TICKS;
		Container home = this.bot.getHome() == null ? null : Storage.containerAt(level, this.bot.getHome());
		if (home == null) {
			return false;
		}
		Optional<Item> better = ToolKind.AXE.findUpgrade(level, this.bot, home, this.colony(level).orElse(null));
		better.ifPresent(axe -> {
			this.upgrade = axe;
			this.enter(State.UPGRADE);
		});
		return better.isPresent();
	}

	/** Takes the better axe from home and leaves the axes it replaces there. */
	private void fetchUpgrade(ServerLevel level) {
		BlockPos home = this.bot.getHome();
		Container container = home == null ? null : Storage.containerAt(level, home);
		Item target = this.upgrade;
		if (container == null || target == null) {
			this.upgrade = null;
			this.enter(State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(home, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			this.bot.getLookControl().setLookAt(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5);
			ToolKind.AXE.swap(this.bot, container, target);
			this.upgrade = null;
			this.enter(State.SEARCH);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			this.upgrade = null;
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
			if (this.unreachableLogs.stream().anyMatch(log -> level.getBlockState(log).is(BlockTags.LOGS))) {
				this.retryJobLater(level);
			} else {
				this.completeJob(level);
				this.keptFor = this.logJob ? this.nextLogOfTree(level, this.tree.base()) : List.of();
				if (!this.keptFor.isEmpty()) {
					// Up on its scaffold for the next log of the tree: it stays there, logs and all, rather than
					// take the scaffold down and climb it again. Its next job takes it down, or the work between jobs.
					this.depositAfterJob = false;
					this.enter(State.SEARCH);
					return;
				}
			}
			// The leaves decay by themselves now; take the scaffolding down while still up there.
			this.enter(State.CLEANUP);
			return;
		}

		// Like a player, it breaks only a log it can see, never one behind a wall or the canopy.
		Goal reach = new Goal.InSight(next, REACH, this.sight);
		if (reach.isReached(this.bot.blockPosition()) && this.bot.onGround()) {
			this.travelGoal = null;
			// Straight into the inventory: dropped logs could land on the canopy, out of reach.
			if (this.breaker.tick(level, next, true)) {
				// With the log gone, the bot may see logs it could not before.
				this.unreachableLogs.clear();
			}
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			if (this.restockCooldown == 0 && this.needsScaffold() && this.homeScaffold(level) > 0) {
				// Ran out of scaffold on the way up: fetch more and come back to this tree.
				this.resumeTree = true;
				this.enter(State.RESTOCK);
			} else {
				this.unreachableLogs.add(next);
			}
		}
	}

	/** Takes down the scaffold blocks placed for this tree (see {@link ScaffoldCleanup}), then collects the drops. */
	private void cleanup(ServerLevel level) {
		if (!this.scaffoldCleanup.tick(level, pos -> false)) {
			return;
		}
		this.unreachableItems.clear();
		// The tree's job picks up the drops and replants it once its logs are gone.
		this.site = this.tree != null && !this.logJob ? this.tree.base() : null;
		this.enter(this.site != null ? State.COLLECT : State.SEARCH);
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

	/**
	 * Plants a sapling on every open spot of the site, once it carries enough of the right kind;
	 * a 2x2 tree is never planted partly, as it would not grow back. A 2x2 site partly built over
	 * gets a 1x1 tree on one open spot instead.
	 */
	private void replant(ServerLevel level) {
		BlockPos base = this.site;
		List<BlockPos> open = this.toPlant(level, this.spots);
		if (open.isEmpty()) {
			// Replanted by someone else, or built over.
			this.completeJob(level);
			this.enter(State.SEARCH);
			return;
		}
		Predicate<ItemStack> wanted = this.wanted(level);
		if (count(this.bot.getInventory(), wanted) < open.size()) {
			// The leaves may still drop one as they decay. Whichever bot is nearest when saplings turn up
			// replants it; the job remembers every spot and the kind, so a 2x2 tree is planted whole and
			// a 1x1 tree waits for its own kind.
			this.releaseJob(level);
			this.colony(level).ifPresent(colony -> colony.board().post(JobType.REPLANT, base, this.spots, this.sapling, this.felled));
			this.enter(State.SEARCH);
			return;
		}
		Goal reach = new Goal.Reach(base, REACH);
		if (reach.isReached(this.bot.blockPosition())) {
			for (BlockPos spot : open) {
				// Its own kind first, should any sapling do by now.
				ItemStack sapling = Optional.ofNullable(findItem(this.bot.getInventory(), saplingMatcher(this.sapling).and(wanted)))
					.orElseGet(() -> findItem(this.bot.getInventory(), wanted));
				if (sapling != null && sapling.getItem() instanceof BlockItem blockItem) {
					level.setBlockAndUpdate(spot, blockItem.getBlock().defaultBlockState());
					sapling.shrink(1);
				}
			}
			this.completeJob(level);
			this.enter(State.SEARCH);
		} else if (this.travel(reach) == PathFollower.Status.FAILED) {
			// A replant job for a base no one can walk to is dropped, so bots do not keep trying.
			this.completeJob(level);
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
		return count(inventory, stack -> stack.is(ItemTags.LOGS));
	}

	private static int count(Container container, Predicate<ItemStack> predicate) {
		int count = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (predicate.test(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static @Nullable ItemStack findItem(Container inventory, java.util.function.Predicate<ItemStack> predicate) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && predicate.test(stack)) {
				return stack;
			}
		}
		return null;
	}
}
