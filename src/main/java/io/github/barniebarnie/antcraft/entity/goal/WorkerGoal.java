package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobBoard;
import io.github.barniebarnie.antcraft.colony.JobScore;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
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
 * The work loop of every colony bot: claims the job that {@link JobScore} rates best for the bot, of any
 * type it has an executor for, and runs it there. It heartbeats the job while the executor works on it,
 * and completes, releases or retries it as the executor reports. So far tree and replant jobs
 * ({@link LumberjackGoal}), crop jobs ({@link FarmerGoal}), branch jobs ({@link MinerGoal}), craft and Bot Core jobs
 * ({@link CrafterGoal}) and haul jobs ({@link HaulerGoal}) are taken this way, by a bot of any role; the
 * other job types stay with their role goals until they move here. Until then a bot's role is a bonus in the score: a job of its own role
 * that scores better is left to the role's goal. Runs ahead of the role goals, and takes the bot from one
 * only while that reports no work.
 */
public class WorkerGoal extends net.minecraft.world.entity.ai.goal.Goal {
	/** What became of a job, as its executor reports it. */
	enum Outcome { COMPLETE, RELEASE, RETRY }

	/** Runs the jobs of some types for the worker goal. */
	interface Executor {
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
	/** The job types each role's goal does, preferred by bots of that role. */
	private static final Map<BotRole, Set<JobType>> ROLE_JOBS = Map.of(
		BotRole.LUMBERJACK, EnumSet.of(JobType.CHOP_TREE, JobType.REPLANT),
		BotRole.MINER, EnumSet.of(JobType.MINE_BRANCH, JobType.CLEAR_AREA),
		BotRole.FARMER, EnumSet.of(JobType.HARVEST_CROP, JobType.PLANT_CROP),
		BotRole.BUILDER, EnumSet.of(JobType.BUILD, JobType.CLEAR_AREA),
		BotRole.CRAFTER, EnumSet.of(JobType.CRAFT, JobType.ACTIVATE_BOT),
		BotRole.HAULER, EnumSet.of(JobType.HAUL)
	);
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
	/** The executor of the job begun last, at work on it or on what follows it. */
	private @Nullable Executor active;
	/** The job claimed, until its executor reports what became of it. */
	private @Nullable Job job;
	private Optional<Job> last = Optional.empty();
	/** Whether the executor is still at work, on the job or on what follows it. */
	private boolean busy;
	private long nextCheck;

	public WorkerGoal(AntBot bot, LumberjackGoal lumberjack, FarmerGoal farmer, MinerGoal miner, CrafterGoal crafter, HaulerGoal hauler) {
		this.bot = bot;
		this.executors = Map.of(JobType.CHOP_TREE, lumberjack, JobType.REPLANT, lumberjack,
			JobType.HARVEST_CROP, farmer, JobType.PLANT_CROP, farmer,
			JobType.MINE_BRANCH, miner,
			JobType.CRAFT, crafter, JobType.ACTIVATE_BOT, crafter,
			JobType.HAUL, hauler);
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (!(this.bot.level() instanceof ServerLevel level) || this.bot.getWalkTarget() != null || this.bot.isWorking()
				|| level.getGameTime() < this.nextCheck) {
			return false;
		}
		this.nextCheck = level.getGameTime() + CHECK_TICKS;
		this.busy = this.colony(level).map(colony -> this.next(level, colony)).orElse(false);
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
		if (this.active != null) {
			this.busy = this.active.work(level);
			this.active.takeOutcome().ifPresent(outcome -> this.report(level, colony.get(), outcome));
		} else {
			this.busy = false;
		}
		if (!this.busy) {
			this.busy = this.next(level, colony.get());
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
		Set<JobType> own = ROLE_JOBS.getOrDefault(this.bot.getRole(), Set.of());
		while (true) {
			Optional<Job> held = board.jobOf(id);
			if (held.isEmpty()) {
				JobScore score = this.score(level, colony, own);
				Optional<Job> best = board.best(now, open -> this.executors.containsKey(open.type())
					? this.executors.get(open.type()).isReady(level, colony, open) : own.contains(open.type()), score);
				// A job of the bot's own role is left to the role's goal, which claims it itself.
				if (best.isEmpty() || !this.executors.containsKey(best.get().type())) {
					return false;
				}
				held = board.claim(id, now, open -> open.id().equals(best.get().id()), score);
			}
			if (held.isEmpty() || !this.executors.containsKey(held.get().type())) {
				return false;
			}
			Executor executor = this.executors.get(held.get().type());
			if (executor.begin(level, held.get())) {
				if (this.active != null && this.active != executor) {
					this.active.reset();
				}
				this.active = executor;
				this.job = held.get();
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
	private JobScore score(ServerLevel level, Colony colony, Set<JobType> own) {
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
		return new JobScore(this.bot.blockPosition(), carried, ColonyWorkGoal.freeSlots(this.bot.getInventory()), this.last, fetch, own);
	}

	private Optional<Colony> colony(ServerLevel level) {
		UUID id = this.bot.getColonyId();
		return id == null ? Optional.empty() : ColonyManager.get(level).byId(id);
	}
}
