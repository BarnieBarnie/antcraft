package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobBoard;
import io.github.barniebarnie.antcraft.colony.JobScore;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The work loop of every colony bot, all alike: claims the job that {@link JobScore} rates best for the bot and
 * runs it on the executor of its type: tree and replant jobs on {@link LumberjackGoal}, crop jobs on
 * {@link FarmerGoal}, branch jobs on {@link MinerGoal}, craft and Bot Core jobs on {@link CrafterGoal}, haul jobs
 * on {@link HaulerGoal} and build and dig jobs on {@link BuilderGoal}. It heartbeats the job while the executor
 * works on it, and completes, releases or retries it as the executor reports.
 * <p>
 * With no job to take it does the work between jobs (see {@link ColonyWorkGoal#upkeep}): it arms itself from the
 * core, swaps a tool it carries for a better one, takes down scaffolding left up, unloads a full pack, stores
 * produce and shears sheep, and keeps the core and storage, picking up loose items. While scaffolding is up it
 * takes only jobs that take it down when done. A job that turns up meanwhile takes the bot from that. Only with none of it to do is the bot idle.
 */
public class WorkerGoal extends net.minecraft.world.entity.ai.goal.Goal {
	/** What became of a job, as its executor reports it. */
	enum Outcome { COMPLETE, RELEASE, RETRY }

	/** Runs the jobs of some types for the worker goal. */
	public interface Executor {
		/** Whether this bot could start on the job now. */
		boolean isReady(ServerLevel level, Colony colony, Job job);

		/** Starts on a claimed job. Returns false when there is nothing left to do for it. */
		boolean begin(ServerLevel level, Job job);

		/** Works one tick on the job begun, and on what follows it. Returns false once ready for the next job. */
		boolean work(ServerLevel level);

		/** What became of the job begun, once that is known; reported once. */
		Optional<Outcome> takeOutcome();

		/** Drops the job begun and what follows it, as the worker goal stops. */
		void reset();

		/** How long a job given back to retry later waits. */
		default int retryTicks() {
			return LumberjackGoal.TREE_RETRY_TICKS;
		}

		/** How many tries a job gets before it is given up. */
		default int tries() {
			return LumberjackGoal.TREE_TRIES;
		}
	}
	/** Jobs whose executor takes down all the bot's scaffolding when done, so a bot with some up may take them. */
	private static final Set<JobType> CLEARS_SCAFFOLD = EnumSet.of(JobType.CHOP_TREE, JobType.BUILD, JobType.CLEAR_AREA);
	/** How often an idle bot looks at the board. */
	private static final int CHECK_TICKS = 10;
	/**
	 * What a tool the colony has no good enough one of costs a bot that carries a weaker one of its kind: it can
	 * still do the job, such as a branch with a pickaxe that leaves the ores it cannot harvest.
	 */
	// ponytail: naive weight, tune from game tests.
	static final double WEAK_TOOL_COST = 48;

	private final AntBot bot;
	/** The executor of each job type claimed here. */
	private final Map<JobType, Executor> executors;
	/** The work between jobs, in the order it is looked for. */
	private final List<ColonyWorkGoal> upkeeps;
	/** The work between jobs the bot is at, or null. */
	private @Nullable ColonyWorkGoal upkeep;
	/** The executor of the job begun last, at work on it or on what follows it. */
	private @Nullable Executor active;
	/** The job claimed, until its executor reports what became of it. */
	private @Nullable Job job;
	private Optional<Job> last = Optional.empty();
	/** Whether the executor is still at work, on the job or on what follows it, or the bot on work between jobs. */
	private boolean busy;
	private long nextCheck;

	public WorkerGoal(AntBot bot, LumberjackGoal lumberjack, FarmerGoal farmer, MinerGoal miner, CrafterGoal crafter, HaulerGoal hauler,
			BuilderGoal builder) {
		this.bot = bot;
		this.executors = Map.of(JobType.CHOP_TREE, lumberjack, JobType.REPLANT, lumberjack,
			JobType.HARVEST_CROP, farmer, JobType.PLANT_CROP, farmer,
			JobType.MINE_BRANCH, miner,
			JobType.CRAFT, crafter, JobType.ACTIVATE_BOT, crafter,
			JobType.HAUL, hauler,
			JobType.BUILD, builder, JobType.CLEAR_AREA, builder);
		// Arming and better tools are the same for every bot; the shared plumbing does them.
		List<ColonyWorkGoal> upkeeps = new ArrayList<>();
		upkeeps.add(new ColonyWorkGoal(bot) {
			@Override
			protected boolean betweenJobs(ServerLevel level, Colony colony) {
				return this.armFromCore(level, colony);
			}
		});
		// One each, as each looks for an upgrade only now and then.
		for (ToolKind kind : List.of(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.HOE)) {
			upkeeps.add(new ColonyWorkGoal(bot) {
				@Override
				protected boolean betweenJobs(ServerLevel level, Colony colony) {
					return kind.bestCarried(this.bot) != null && this.upgradeTool(level, colony, kind);
				}
			});
		}
		upkeeps.addAll(List.of(builder, miner, farmer, hauler));
		this.upkeeps = List.copyOf(upkeeps);
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	/** The executor of jobs of {@code type}, such as for a game test to set it up. */
	public Executor executor(JobType type) {
		return this.executors.get(type);
	}

	@Override
	public boolean canUse() {
		if (!(this.bot.level() instanceof ServerLevel level) || this.bot.getWalkTarget() != null || this.bot.isWorking()
				|| level.getGameTime() < this.nextCheck) {
			return false;
		}
		this.nextCheck = level.getGameTime() + CHECK_TICKS;
		this.busy = this.colony(level).map(colony -> this.next(level, colony) || this.startUpkeep(level, colony)).orElse(false);
		return this.busy;
	}

	@Override
	public boolean canContinueToUse() {
		return this.busy && this.bot.getWalkTarget() == null && this.bot.getColonyId() != null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void stop() {
		if (this.job != null && this.bot.level() instanceof ServerLevel level) {
			this.colony(level).ifPresent(colony -> colony.board().release(this.bot.getUUID()));
		}
		this.job = null;
		this.busy = false;
		if (this.active != null) {
			this.active.reset();
			this.active = null;
		}
		this.stopUpkeep();
		this.bot.setWorking(false);
	}

	@Override
	public void tick() {
		if (!(this.bot.level() instanceof ServerLevel level)) {
			return;
		}
		Optional<Colony> colony = this.colony(level);
		if (colony.isEmpty()) {
			this.busy = false;
			return;
		}
		JobBoard board = colony.get().board();
		if (this.job != null && level.getGameTime() % 20 == 0) {
			board.heartbeat(this.bot.getUUID(), level.getGameTime());
		}
		if (this.upkeep != null) {
			// A job that turns up takes the bot from work between jobs.
			if (level.getGameTime() >= this.nextCheck) {
				this.nextCheck = level.getGameTime() + CHECK_TICKS;
				if (this.next(level, colony.get())) {
					this.busy = true;
					return;
				}
			}
			this.busy = this.upkeep.upkeep(level, colony.get()) || this.startUpkeep(level, colony.get());
			return;
		}
		if (this.active != null) {
			this.busy = this.active.work(level);
			this.active.takeOutcome().ifPresent(outcome -> this.report(level, colony.get(), outcome));
		} else {
			this.busy = false;
		}
		if (!this.busy) {
			this.busy = this.next(level, colony.get()) || this.startUpkeep(level, colony.get());
		}
	}

	/**
	 * Starts on the first work between jobs there is, other than the one the bot was just at, so one that
	 * keeps finding a little to do does not keep the others waiting. Returns false when there is none.
	 */
	private boolean startUpkeep(ServerLevel level, Colony colony) {
		ColonyWorkGoal done = this.upkeep;
		this.stopUpkeep();
		for (ColonyWorkGoal next : this.upkeeps) {
			if (next != done && next.upkeep(level, colony)) {
				this.upkeep = next;
				this.bot.setWorking(true);
				return true;
			}
		}
		this.bot.setWorking(false);
		return false;
	}

	private void stopUpkeep() {
		if (this.upkeep != null) {
			this.upkeep.resetMovement();
			this.upkeep = null;
		}
	}

	private void report(ServerLevel level, Colony colony, Outcome outcome) {
		Job done = this.job;
		this.job = null;
		if (done == null) {
			return;
		}
		switch (outcome) {
			case COMPLETE -> {
				colony.board().complete(done.id());
				this.last = Optional.of(done);
			}
			case RELEASE -> colony.board().release(this.bot.getUUID());
			case RETRY -> {
				Executor executor = this.executors.get(done.type());
				if (!colony.board().retryLater(this.bot.getUUID(), level.getGameTime() + executor.retryTicks(), executor.tries())) {
					AntCraft.LOGGER.info("Colony at {} gave up on the {} job at {} after {} tries",
						colony.core().toShortString(), done.type().getSerializedName(), done.target().toShortString(), executor.tries());
				}
			}
		}
	}

	/**
	 * Begins the bot's job, or claims and begins the best one. Jobs with nothing left to do are taken
	 * off the board. Returns whether the bot is on a job now.
	 */
	private boolean next(ServerLevel level, Colony colony) {
		JobBoard board = colony.board();
		UUID id = this.bot.getUUID();
		long now = level.getGameTime();
		while (true) {
			Optional<Job> held = board.jobOf(id);
			if (held.isEmpty()) {
				// Scaffolding left up, such as from a job the bot was called away from, comes down before other work
				// (see BuilderGoal); only jobs that take scaffolding down at their end may come first.
				boolean scaffoldUp = !this.bot.getScaffold().isEmpty();
				JobScore score = this.score(level, colony);
				Optional<Job> best = board.best(now, open -> (!scaffoldUp || CLEARS_SCAFFOLD.contains(open.type()))
					&& this.executors.get(open.type()).isReady(level, colony, open), score);
				if (best.isEmpty()) {
					return false;
				}
				held = board.claim(id, now, open -> open.id().equals(best.get().id()), score);
			}
			if (held.isEmpty()) {
				return false;
			}
			Executor executor = this.executors.get(held.get().type());
			if (executor.begin(level, held.get())) {
				if (this.active != null && this.active != executor) {
					this.active.reset();
				}
				this.stopUpkeep();
				this.active = executor;
				this.job = held.get();
				this.bot.setJobType(this.job.type());
				return true;
			}
			board.complete(held.get().id());
		}
	}

	/**
	 * How well jobs suit this bot. An item it does not carry costs the walk to the core if the core holds
	 * it, or for a tool one at least as good; a sapling not there costs nothing, as the job's ready check
	 * found one lying near the site. A tool the colony has no good enough one of costs a bot that carries
	 * a weaker one {@link #WEAK_TOOL_COST}.
	 */
	private JobScore score(ServerLevel level, Colony colony) {
		List<Item> carried = Stream.concat(this.bot.getInventory().getItems().stream(), Stream.of(this.bot.getMainHandItem()))
			.filter(stack -> !stack.isEmpty()).map(ItemStack::getItem).toList();
		// ponytail: only the core counts as a source; storage chests join when a job type needs them.
		Container core = Storage.containerAt(level, colony.core());
		double toCore = Math.sqrt(this.bot.blockPosition().distSqr(colony.core()));
		ToDoubleFunction<Item> fetch = item -> {
			Optional<ToolKind> kind = ToolKind.of(item);
			if (core != null && (core.countItem(item) > 0 || kind.filter(k -> k.betterIn(Storage.stock(core), item).isPresent()).isPresent())) {
				return toCore;
			}
			if (kind.filter(k -> k.bestCarried(this.bot) != null).isPresent()) {
				return WEAK_TOOL_COST;
			}
			return new ItemStack(item).is(ItemTags.SAPLINGS) ? 0 : Double.POSITIVE_INFINITY;
		};
		return new JobScore(this.bot.blockPosition(), carried, ColonyWorkGoal.freeSlots(this.bot.getInventory()), this.last, fetch);
	}

	private Optional<Colony> colony(ServerLevel level) {
		UUID id = this.bot.getColonyId();
		return id == null ? Optional.empty() : ColonyManager.get(level).byId(id);
	}
}
