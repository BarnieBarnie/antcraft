package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.FireSpread;
import io.github.barniebarnie.antcraft.work.LavaSources;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.Terraform;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Build work. As the executor of build and dig jobs for {@link WorkerGoal}, through {@link #begin} and {@link #work},
 * it gets any colony bot to work through a batch of a plan (see {@link BuildPlan.Batch}) step by step. For each block
 * to place it fetches what the batch still needs of it from the core; steps whose item the core
 * lacks wait while the rest goes on. It places blocks only where no bot or mob stands, and blocks
 * it places are protected from other bots. It digs with the best pickaxe in the core, such as
 * down a mineshaft, and orders one if it has none and a plan has such digging to do. Fluids in the
 * way it scoops up with a bucket or fills with a cheap block from the core, and fluid beside a block
 * it digs that the plan does not clear it walls off with such a block; those it cannot clear are
 * reported as blocked. It pours water and lava from a bucket, as a player would, filling an
 * empty one at a source in the territory when the core has no full one, lava also beside the mine and only from a
 * spot safe from it (see {@link LavaSources}); between jobs it fills one the same way for an order and stores it. A waterlogged block it
 * places dry and fills from a water bucket the same way, and one standing waterlogged where the plan
 * wants it dry it empties with an empty bucket. A door, bed or tall plant it places whole
 * from one item, clearing the spot for its other half first; one whose other half would go into
 * an unbreakable block is reported. Fire it lights on the block below with flint and steel from
 * the core, which the colony orders when it has none; fire that would go out or spread to a block that burns, a block
 * that burns where lit fire would spread to it, blocks a
 * player cannot place, such as a portal, and a fluid without a bucket or source
 * to get it from are reported too. To reach a high step it pillars up with scaffold blocks from the
 * core, never where a plan puts a block, and takes the scaffolding down before it puts a plan aside, unless another build job is up on the same ledge.
 * Dig jobs, batches that only clear blocks, it digs out the same way, leaving the logs of trees on the
 * board to whoever takes their tree jobs (see {@link Digging}). A step any solid block will do, such as a terraform's
 * fill, takes whichever fill block there is, sand and gravel only where they rest on the block below
 * and the step may fall, never for a mineshaft's walls. In a terraform it leaves lava and
 * the blocks holding it back alone, to be reported as blocked. Like a player it breaks only blocks
 * it can see; a step it finds no way to, in sight or within reach, it gives up on for the job and
 * reports as blocked once the rest is done. The bot takes home what it dug or has left over after each job.
 * Between jobs it takes down scaffolding left up, such as from a job it was called away from.
 */
public class BuilderGoal extends ColonyWorkGoal implements WorkerGoal.Executor {
	/** Cheap blocks a builder fills a fluid source with to clear it without a bucket. */
	private static final Set<Item> FILLERS = BuildPlan.HOLDING_FILL_BLOCKS;
	private static final Set<BuildPlan.Clearing> WORKABLE = EnumSet.of(BuildPlan.Clearing.BREAK, BuildPlan.Clearing.SCOOP, BuildPlan.Clearing.FILL);
	/** How often the builder looks again for fluid sources to fill a bucket at. */
	private static final int SOURCE_SCAN_TICKS = 100;
	/** The scaffold blocks builders pillar with: ones they take down again quickly without a tool. */
	private static final TagKey<Item> SCAFFOLD = PathFollower.FAST_SCAFFOLD_BLOCKS;
	/** Takes scaffold blocks from the core for a high step when carrying fewer than this. */
	private static final int RESTOCK_BELOW = 8;
	private static final int SCAFFOLD_STOCK = 16;
	/** Slots a fetch leaves free of what the batch needs later, for scaffold and what the builder digs. */
	private static final int FETCH_FREE_SLOTS = 3;
	/** A step this many blocks above the bot's feet or more is out of reach from where it stands, so it takes scaffold first. */
	private static final int SCAFFOLD_HEIGHT = 4;
	/** How long a builder keeps trying to get to a step before giving it up, should its path never fail outright. */
	private static final int STEP_REACH_TICKS = 1200;
	/** How many times a builder finds no path to a step, a while apart, before giving it up; a path may fail for a moment, such as just after loading. */
	private static final int STEP_TRIES = 3;
	/**
	 * How long a builder within reach of a step waits for its spot to be free, such as of a mob standing in it, before
	 * giving the step up. An idle bot steps aside in a second or so and a wandering mob moves on in a few; 15 s is
	 * well over that, and well under {@link #STEP_REACH_TICKS}.
	 */
	private static final int SPOT_WAIT_TICKS = 300;

	/** Fluid sources found per full bucket they fill, cleared every {@link #SOURCE_SCAN_TICKS}. */
	private final Map<Item, Optional<BlockPos>> sources = new HashMap<>();
	/** Sources it found no spot to see from, skipped until no other source is left. */
	private final Set<BlockPos> hiddenSources = new HashSet<>();
	private long nextSourceScan;
	/** Where a bot fills a bucket with lava from: a spot that is safe from it (see {@link LavaSources#isSafeSpot}); one for good, as goals compare by value. */
	private final Predicate<BlockPos> safeFromLava = feet -> LavaSources.isSafeSpot(pos -> this.bot.level().getBlockState(pos), feet);
	/** The full bucket filled for an order between jobs, on its way to the core; null if none. */
	private @Nullable Item gathered;
	private final ScaffoldCleanup scaffoldCleanup;
	/** Taking its scaffolding down, before putting a plan aside or once it has no job. */
	private boolean cleaning;
	/** Whether it tried to climb out of the water it is in between jobs; once per time it is afloat, so one stuck does not keep trying. */
	private boolean climbedOut;
	/** What the builder found done of the batch of build job {@link #progressJob}, so it does not look at those blocks every tick. */
	private BuildPlan.Progress progress = new BuildPlan.Progress();
	private @Nullable UUID progressJob;
	/** Steps of job {@link #progressJob} it found no way to, left for the colony to recheck once the rest is done. */
	private final Set<BlockPos> unreachable = new HashSet<>();
	/**
	 * Cells to dig and wall off it found no spot to see together with their fluid from, given up only until it digs a
	 * block next to one, which may open a view (see {@link #digAndWall}).
	 */
	private final Set<BlockPos> noView = new HashSet<>();
	/** How often it found no path to each step of the job so far. */
	private final Map<BlockPos, Integer> failures = new HashMap<>();
	/** The step it is on its way to, since when, and when it last went for it, to give it up after {@link #STEP_REACH_TICKS}. */
	private @Nullable BlockPos reaching;
	private long reachingSince;
	private long reachingLast;
	/** The step whose spot it found taken, since when, and when it last looked, to give it up after {@link #SPOT_WAIT_TICKS}. */
	private @Nullable BlockPos waiting;
	private long waitingSince;
	private long waitingLast;
	/** The build or dig job begun through {@link #begin}, until it is done. */
	private @Nullable Job job;
	/** What became of the job begun, for {@link WorkerGoal} to report to the board. */
	private WorkerGoal.@Nullable Outcome outcome;
	/** What the bot carried when it began the job, so it takes home only what the job left it with. */
	private Map<Item, Integer> carriedBefore = Map.of();
	/** Taking what the job left it with to the core. */
	private boolean storing;

	public BuilderGoal(AntBot bot) {
		super(bot);
		this.follower.setScaffold(SCAFFOLD);
		this.scaffoldCleanup = new ScaffoldCleanup(bot, this.follower, this.breaker, this::travel, this::stopTravel);
	}

	/** Stops where it is; scaffolding it was taking down stays up, to come down between jobs. */
	private void stop() {
		this.resetMovement();
		if (this.cleaning) {
			this.scaffoldCleanup.cancelRetry();
			this.follower.setMayPlace(false);
		}
	}

	/**
	 * Work between jobs, for any bot: taking down scaffolding a job left up, such as one it was called away from,
	 * from any job, as every block a path places is scaffold. Never a block a plan placed, which the colony protects.
	 * A bot left afloat, such as in a pool under a rim too high to jump onto, climbs out the same way, once each time.
	 */
	@Override
	protected boolean betweenJobs(ServerLevel level, Colony colony) {
		if (this.cleaning) {
			this.cleanUp(level, colony);
			return true;
		}
		if (!this.bot.isInWater()) {
			this.climbedOut = false;
		}
		if (!this.bot.getScaffold().isEmpty() || this.bot.isInWater() && !this.climbedOut) {
			this.climbedOut |= this.bot.isInWater();
			this.startCleanup();
			return true;
		}
		return this.gather(level, colony);
	}

	@Override
	protected boolean carriesLoad() {
		return this.gathered != null;
	}

	/**
	 * Fills an empty bucket for an order of a full one, such as a lava bucket, which cannot be crafted, at the nearest source
	 * it can fill one at, and takes it to the core. Only while the colony and its bots hold fewer of them than are ordered,
	 * and an empty bucket, carried or in the core. Returns false when there is none of it to do, such as with no source in
	 * reach, so the bot stays where it is.
	 */
	private boolean gather(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (this.gathered != null && this.bot.getInventory().countItem(this.gathered) == 0) {
			this.gathered = null;
		}
		if (core == null) {
			return false;
		}
		if (this.gathered != null) {
			if (this.reach(colony.core())) {
				Item item = this.gathered;
				colony.fulfil(item, Storage.moveItems(this.bot.getInventory(), core, stack -> stack.is(item), 1));
				this.gathered = null;
				this.resetMovement();
			}
			return true;
		}
		Map<Item, Integer> held = Storage.held(level, colony);
		Optional<Item> wanted = colony.orders().entrySet().stream()
			.filter(order -> isPoured(order.getKey()) && held.getOrDefault(order.getKey(), 0) < order.getValue())
			.map(Map.Entry::getKey)
			.filter(item -> this.source(level, colony, item) != null)
			.findFirst();
		if (wanted.isEmpty()) {
			return false;
		}
		int empty = findSlot(this.bot.getInventory(), BuilderGoal::isBucket);
		if (empty >= 0) {
			this.gathered = this.fillAt(level, colony, wanted.get(), empty) ? wanted.get() : null;
		} else if (findSlot(core, BuilderGoal::isBucket) < 0) {
			return false;
		} else if (this.reach(colony.core())) {
			Storage.moveItems(core, this.bot.getInventory(), BuilderGoal::isBucket, 1);
		}
		return true;
	}

	/** Whether this bot has something to do in the job's batch, or can finish it or report it blocked. */
	@Override
	public boolean isReady(ServerLevel level, Colony colony, Job job) {
		Container core = Storage.containerAt(level, colony.core());
		// A plan left only with steps it cannot clear is taken too, to be finished and reported.
		return colony.build(job.id()).filter(plan -> this.nextStep(level, colony, core, plan, isDig(job)).isPresent()
			|| !this.isWaiting(level, colony, core, plan, isDig(job))).isPresent();
	}

	@Override
	public boolean begin(ServerLevel level, Job job) {
		Optional<Colony> colony = this.colonyOf(level);
		if (colony.isEmpty() || colony.get().build(job.id()).isEmpty()) {
			return false;
		}
		this.outcome = null;
		this.job = job;
		this.storing = false;
		this.carriedBefore = Storage.stock(this.bot.getInventory());
		if (!job.id().equals(this.progressJob)) {
			this.progressJob = job.id();
			this.progress = new BuildPlan.Progress();
			this.forgetUnreachable();
		}
		if (this.cleaning) {
			// Scaffolding still up from before comes down at the end of this job.
			this.cleaning = false;
			this.scaffoldCleanup.cancelRetry();
			this.follower.setMayPlace(true);
		}
		this.resetMovement();
		return true;
	}

	@Override
	public boolean work(ServerLevel level) {
		Optional<Colony> colony = this.colonyOf(level);
		if (colony.isEmpty()) {
			return false;
		}
		this.bot.setWorking(this.job != null);
		if (this.cooldown > 0) {
			this.cooldown--;
			return true;
		}
		if (this.job != null) {
			this.work(level, colony.get(), this.job);
			return true;
		}
		if (this.storing) {
			this.store(level, colony.get());
			return true;
		}
		return false;
	}

	@Override
	public Optional<WorkerGoal.Outcome> takeOutcome() {
		Optional<WorkerGoal.Outcome> taken = Optional.ofNullable(this.outcome);
		this.outcome = null;
		return taken;
	}

	@Override
	public void reset() {
		this.job = null;
		this.outcome = null;
		this.storing = false;
		this.stop();
	}

	private Optional<Colony> colonyOf(ServerLevel level) {
		return Optional.ofNullable(this.bot.getColonyId()).flatMap(id -> ColonyManager.get(level).byId(id));
	}

	/** Ends the job begun, then takes home what the job left it with (see {@link #store}). */
	private void endJob(ServerLevel level, WorkerGoal.Outcome how) {
		BlockPos at = this.job == null ? null : this.job.target();
		this.outcome = how;
		this.job = null;
		this.bot.setWorking(false);
		this.resetMovement();
		// The batch's stairs crafted on the spot are made from what was dug there, so that stays with the bot.
		boolean craftHere = at != null && this.colonyOf(level).filter(colony -> colony.board().jobs().stream()
			.anyMatch(job -> job.isOpen() && job.type() == JobType.CRAFT && job.target().equals(at))).isPresent();
		this.storing = !craftHere && !this.leftOver().isEmpty();
	}

	/**
	 * What the bot carries beyond what it did when it began the job, such as the blocks it dug or materials
	 * it did not use; never a tool.
	 */
	private Map<Item, Integer> leftOver() {
		Map<Item, Integer> left = new HashMap<>();
		Storage.stock(this.bot.getInventory()).forEach((item, count) -> {
			int more = count - this.carriedBefore.getOrDefault(item, 0);
			if (more > 0 && !item.getDefaultInstance().isDamageableItem()) {
				left.put(item, more);
			}
		});
		return left;
	}

	/** Takes what the job left the bot with to the core, once; what the core has no room for it keeps. */
	private void store(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null) {
			this.storing = false;
			return;
		}
		if (this.reach(colony.core())) {
			this.leftOver().forEach((item, count) -> Storage.moveItems(this.bot.getInventory(), core, stack -> stack.is(item), count));
			this.storing = false;
			this.resetMovement();
		}
	}

	/** Works one tick on the batch of build or dig job {@code job}. */
	private void work(ServerLevel level, Colony colony, Job job) {
		if (this.upgradeTool(level, colony, ToolKind.PICKAXE, () -> needsPickaxe(level, colony))) {
			return;
		}
		if (this.cleaning) {
			this.cleanUp(level, colony);
			return;
		}
		Container core = Storage.containerAt(level, colony.core());
		Optional<BuildPlan> plan = colony.build(job.id());
		boolean dig = isDig(job);
		Optional<BuildPlan.Step> next = plan.flatMap(p -> this.nextStep(level, colony, core, p, dig, this.progress)).map(s -> this.withFiller(level, core, s));
		if (next.isEmpty()) {
			// The scaffolding comes down first, while the job is still this bot's, unless another build job is up on the
			// same ledge: it stays up for that one.
			if (!this.bot.getScaffold().isEmpty() && !this.moreBuildsFromHere(level, colony, job)) {
				this.startCleanup();
			} else {
				this.putAside(level, colony, core, job, plan, dig);
			}
			return;
		}
		BuildPlan.Step step = next.get();
		BlockState current = level.getBlockState(step.pos());
		// Whatever stands in the way goes first.
		if (step.needsClearing(current)) {
			this.clear(level, colony, core, plan.get(), step);
			return;
		}
		// So does whatever stands where a door's or bed's other half goes, which has no step of its own.
		Optional<BuildPlan.Step> other = step.otherHalf().filter(half -> !step.isPlaced(current) && half.needsClearing(level.getBlockState(half.pos())));
		if (other.isPresent()) {
			this.clear(level, colony, core, plan.get(), other.get());
			return;
		}
		Item item = step.item(current);
		if (item != Items.AIR && this.bot.getInventory().countItem(item) == 0) {
			if (isPoured(item) && (core == null || core.countItem(item) == 0)) {
				this.fill(level, colony, core, plan.get(), item);
			} else {
				this.fetch(level, colony, core, plan.get(), item);
			}
			return;
		}
		// A block is placed where the bot sees the spot, as a player must; a wall behind stone the plan digs is put off
		// until then (see isBehindUndug).
		if (this.restockScaffold(colony, core, step) || !this.placesWhereItStands(level, step) && !this.reachStep(level, step.pos())) {
			return;
		}
		if (step.isPlaced(current) && item == Items.BUCKET) {
			// The block is right but holds water the plan wants out, so it is scooped up rather than broken.
			this.scoop(level, step.pos(), findSlot(this.bot.getInventory(), BuilderGoal::isBucket));
		} else if (step.isPlaced(current)) {
			this.pour(level, step.pos(), current, item);
		} else if (step.isLit()) {
			this.light(level, colony, step);
		} else if (this.isFreeToPlace(level, step.pos(), step.dry())
			&& step.otherHalf().map(half -> this.isFreeToPlace(level, half.pos(), half.state())).orElse(true)) {
			this.place(level, colony, step);
		} else {
			this.waitForSpot(level, step.pos());
		}
	}

	/**
	 * Whether {@code step} places a ladder or a trapdoor in the space the bot stands in, at its feet or head, that leaves
	 * room for it when it stands in the middle, such as a ladder on the wall or a hatch overhead: a player places one
	 * there, as the builder of a ladder shaft must, with no other spot in the 1x1 column to stand. Other steps are reached
	 * from a spot beside them.
	 */
	private boolean placesWhereItStands(ServerLevel level, BuildPlan.Step step) {
		BlockPos feet = this.bot.blockPosition();
		if (!step.state().is(BlockTags.CLIMBABLE) && !step.state().is(BlockTags.TRAPDOORS) || !feet.equals(step.pos()) && !feet.above().equals(step.pos())) {
			return false;
		}
		AABB bot = this.bot.getDimensions(this.bot.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(feet));
		return step.dry().getCollisionShape(level, step.pos()).toAabbs().stream().noneMatch(box -> box.move(step.pos()).intersects(bot));
	}

	/** Whether another open build or dig job this bot is ready for is up on the ledge it stands on its scaffold (see {@link WorkerGoal#onLedge}). */
	private boolean moreBuildsFromHere(ServerLevel level, Colony colony, Job done) {
		return colony.board().jobs().stream().anyMatch(job -> job.isOpen() && !job.givenUp() && !job.id().equals(done.id())
			&& (job.type() == JobType.BUILD || job.type() == JobType.CLEAR_AREA) && WorkerGoal.onLedge(this.bot, job) && this.isReady(level, colony, job));
	}

	/** Waits at the step at {@code pos} for its spot to be free, giving the step up for this job after {@link #SPOT_WAIT_TICKS}. */
	private void waitForSpot(ServerLevel level, BlockPos pos) {
		long now = level.getGameTime();
		if (!pos.equals(this.waiting) || now - this.waitingLast > IDLE_TICKS + 1) {
			this.waiting = pos;
			this.waitingSince = now;
		}
		this.waitingLast = now;
		if (now - this.waitingSince > SPOT_WAIT_TICKS) {
			this.unreachable.add(pos);
			this.waiting = null;
		}
	}

	/**
	 * Moves toward the block of a step at {@code pos} until it is within reach and in sight of it, as to
	 * break it. Returns true once there. With no way there, or none found after {@link #STEP_TRIES}
	 * tries or {@link #STEP_REACH_TICKS}, it gives the step up for this job (see {@link #unreachable}).
	 */
	private boolean reachStep(ServerLevel level, BlockPos pos) {
		return this.reachStep(level, pos, this.inSight(pos));
	}

	/** {@link #reachStep(ServerLevel, BlockPos)}, until {@code goal} is reached. */
	private boolean reachStep(ServerLevel level, BlockPos pos, Goal goal) {
		long now = level.getGameTime();
		if (!pos.equals(this.reaching) || now - this.reachingLast > IDLE_TICKS + 1) {
			this.reaching = pos;
			this.reachingSince = now;
		}
		this.reachingLast = now;
		// Water to fill or scoop may be out of reach from every dry spot, such as in the middle of a deep pond, and so may
		// a block standing in it, such as one filled in at its surface: the bot swims out to it, as a player would, rather
		// than dig into the bank.
		boolean wet = !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty();
		PathFollower.Status status = this.approach(wet ? new Goal.Afloat(goal) : goal, pos);
		if (status == PathFollower.Status.ARRIVED) {
			this.reachingSince = now;
			return true;
		}
		boolean failed = status == PathFollower.Status.FAILED;
		if (failed) {
			this.cooldown = IDLE_TICKS;
		}
		if (failed && this.failures.merge(pos, 1, Integer::sum) >= STEP_TRIES || now - this.reachingSince > STEP_REACH_TICKS) {
			this.unreachable.add(pos);
			this.reaching = null;
			this.stopTravel();
		}
		return false;
	}

	private void forgetUnreachable() {
		this.unreachable.clear();
		this.noView.clear();
		this.failures.clear();
	}

	/** Whether the builder gave {@code step} up for this job, or the spot of its other half. */
	private boolean isGivenUp(BuildPlan.Step step) {
		return this.unreachable.contains(step.pos()) || step.otherHalf().filter(half -> this.unreachable.contains(half.pos())).isPresent();
	}

	private static boolean isDig(Job job) {
		return job.type() == JobType.CLEAR_AREA;
	}

	/**
	 * {@code step}, or for a step any solid block will do, such as a terraform's fill, the same step
	 * with a fill block such as cobblestone or sand that the bot or the core has, when neither has
	 * the step's own block. Sand and gravel only where the step may fall and rests on the block
	 * below (see {@link BuildPlan.Step#fillBlocks}), so a mineshaft's wall never drops into the shaft.
	 * Likewise a step any of a kind will do, such as any stone stairs or any wooden trapdoor, takes whichever of
	 * its {@link BuildPlan.Step#standIns} there is.
	 */
	private BuildPlan.Step withFiller(ServerLevel level, @Nullable Container core, BuildPlan.Step step) {
		if (!step.anySolid() && step.standIns().isEmpty() || this.has(core, stack -> stack.is(step.item()))) {
			return step;
		}
		Set<Item> fillers = !step.standIns().isEmpty() ? step.standIns() : step.fillBlocks(level.getBlockState(step.pos().below()));
		Predicate<ItemStack> usable = stack -> fillers.contains(stack.getItem());
		SimpleContainer inventory = this.bot.getInventory();
		int slot = findSlot(inventory, usable);
		Item filler = slot >= 0 ? inventory.getItem(slot).getItem() : null;
		if (filler == null && core != null && (slot = findSlot(core, usable)) >= 0) {
			filler = core.getItem(slot).getItem();
		}
		// Stairs and trapdoors keep the way they face.
		return filler == null ? step : new BuildPlan.Step(step.pos(), Block.byItem(filler).withPropertiesOf(step.state()), step.anySolid(), step.mayFall(),
			step.anyStoneStairs(), step.anyWoodenTrapdoor());
	}

	/** Every position a plan of the colony puts a block or clears, where scaffold must never go. */
	private static Set<BlockPos> planned(Colony colony) {
		return colony.builds().stream().flatMap(plan -> plan.steps().stream()).map(BuildPlan.Step::pos).collect(Collectors.toSet());
	}

	private void startCleanup() {
		this.cleaning = true;
		this.scaffoldCleanup.reset();
		this.resetMovement();
		this.follower.setMayPlace(false);
	}

	/** Takes the scaffolding down (see {@link ScaffoldCleanup}); never a block it placed for a plan, which the colony protects. */
	private void cleanUp(ServerLevel level, Colony colony) {
		if (this.scaffoldCleanup.tick(level, pos -> colony.isProtected(level::getBlockState, pos))) {
			this.cleaning = false;
			this.resetMovement();
			this.follower.setMayPlace(true);
		}
	}

	/**
	 * Before going for a step too high to reach from where it stands, takes scaffold blocks from the
	 * core to pillar up with, if it is short of them. Returns true while it is on its way to the core.
	 */
	private boolean restockScaffold(Colony colony, @Nullable Container core, BuildPlan.Step step) {
		int carried = PathFollower.scaffoldCount(this.bot.getInventory(), SCAFFOLD);
		if (step.pos().getY() - this.bot.getBlockY() < SCAFFOLD_HEIGHT || carried >= RESTOCK_BELOW
			|| core == null || PathFollower.scaffoldCount(core, SCAFFOLD) == 0) {
			return false;
		}
		if (this.reach(colony.core())) {
			Storage.moveItems(core, this.bot.getInventory(), stack -> stack.is(SCAFFOLD), SCAFFOLD_STOCK - carried);
		}
		return true;
	}

	/**
	 * The step to work on: the first one not done that is neither blocked by an unbreakable block
	 * nor waiting for an item that neither the bot nor the core has. Those are left for later, so
	 * the rest of the plan goes on, such as digging a mineshaft before its torches are made. A
	 * fluid also goes when a bucket can be filled at a source; a block without an item never does.
	 * A {@code dig} job leaves the logs of trees with a tree job to that job (see {@link Digging}).
	 */
	private Optional<BuildPlan.Step> nextStep(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, boolean dig) {
		return this.nextStep(level, colony, core, plan, dig, new BuildPlan.Progress());
	}

	/** {@link #nextStep(ServerLevel, Colony, Container, BuildPlan, boolean)}, skipping the steps {@code progress} found done. */
	private Optional<BuildPlan.Step> nextStep(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, boolean dig,
			BuildPlan.Progress progress) {
		Map<BlockPos, BuildPlan.Step> opened = opened(plan);
		return plan.nextStep(level::getBlockState, planned -> {
			BlockState current = level.getBlockState(planned.pos());
			if (dig && Digging.isForTreeJob(level, colony, planned.pos()) || leavesAlone(level, plan, planned) || this.isGivenUp(planned)
					|| isBehindUndug(level, opened, planned)) {
				return false;
			}
			BuildPlan.Step step = this.withFiller(level, core, planned);
			if (step.needsClearing(current)) {
				// A dig beside a fluid needs a filler block for each wall and a block that breaks, or it is reported rather than fetched forever.
				List<BlockPos> walls = this.fluidsToWall(level, core, step);
				return WORKABLE.contains(this.clearing(level, core, step))
					|| !walls.isEmpty() && !isUnbreakable(level, step.pos()) && this.fillers(core, walls.size()).isPresent();
			}
			Item item = step.item(current);
			if (!isPossible(level, step) || plan.waitsForWalls(level::getBlockState, step)) {
				return false;
			}
			return this.has(core, stack -> stack.is(item))
				|| isPoured(item) && this.has(core, BuilderGoal::isBucket) && this.source(level, colony, item) != null;
		}, progress);
	}

	/** Whether a cell beside {@code step} that the plan opens up is still to dig. */
	private static boolean isBesideUndug(ServerLevel level, Map<BlockPos, BuildPlan.Step> opened, BuildPlan.Step step) {
		for (Direction side : Direction.values()) {
			BuildPlan.Step cell = opened.get(step.pos().relative(side));
			if (cell != null && cell.needsClearing(level.getBlockState(cell.pos()))) {
				return true;
			}
		}
		return false;
	}

	/** Whether a cell beside {@code step} that the plan opens up still holds a block to dig, rather than a fluid. */
	private static boolean isBesideUndugBlock(ServerLevel level, Map<BlockPos, BuildPlan.Step> opened, BuildPlan.Step step) {
		for (Direction side : Direction.values()) {
			BuildPlan.Step cell = opened.get(step.pos().relative(side));
			if (cell != null) {
				BlockState state = level.getBlockState(cell.pos());
				if (state.getFluidState().isEmpty() && cell.needsClearing(state)) {
					return true;
				}
			}
		}
		return false;
	}

	/** The steps of {@code plan} that open a cell up, such as to dig it out or put a torch in, by position. */
	private static Map<BlockPos, BuildPlan.Step> opened(BuildPlan plan) {
		Map<BlockPos, BuildPlan.Step> opened = new HashMap<>();
		plan.steps().stream().filter(step -> !step.anySolid() && !step.state().isSolid()).forEach(step -> opened.put(step.pos(), step));
		return opened;
	}

	/**
	 * Whether {@code step} is a wall any solid block will do that the plan opens the way to only by digging out a cell
	 * beside it, and every such cell is still to dig, such as a cave behind the stone of a mineshaft: it is put off until
	 * then, as it cannot be seen before. One in a fluid is put off while any such cell is still to clear: it is walled off
	 * as that cell is dug, in the same tick, so it does not flow in (see {@link #digAndWall}). So is a fluid the plan
	 * clears itself, beside a cell that still holds a block to dig, such as water behind the stone of a mineshaft cell:
	 * a builder fills a fluid only where it sees it.
	 */
	private static boolean isBehindUndug(ServerLevel level, Map<BlockPos, BuildPlan.Step> opened, BuildPlan.Step step) {
		BlockPos pos = step.pos();
		if (!step.anySolid()) {
			return step.isClear() && !level.getFluidState(pos).isEmpty() && isBesideUndugBlock(level, opened, step);
		}
		if (!level.getFluidState(pos).isEmpty()) {
			return isBesideUndug(level, opened, step);
		}
		boolean beside = false;
		for (Direction side : Direction.values()) {
			BuildPlan.Step cell = opened.get(pos.relative(side));
			if (cell != null) {
				BlockState state = level.getBlockState(cell.pos());
				if (!cell.needsClearing(state) || !state.getFluidState().isEmpty()) {
					return false;
				}
				beside = true;
			}
		}
		return beside;
	}

	/**
	 * Fills an empty bucket to pour {@code bucket}'s fluid with at the nearest source it can see, as a player must, fetching
	 * the empty bucket from the core first.
	 */
	private void fill(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, Item bucket) {
		int empty = findSlot(this.bot.getInventory(), BuilderGoal::isBucket);
		BlockPos source = this.source(level, colony, bucket);
		if (empty < 0) {
			this.fetch(level, colony, core, plan, Items.BUCKET);
		} else if (source == null) {
			this.cooldown = IDLE_TICKS;
		} else {
			this.fillAt(level, colony, bucket, empty);
		}
	}

	/**
	 * Goes to the nearest source to fill the empty bucket in {@code slot} into {@code bucket} at, and fills it once there:
	 * within reach and in sight of it, and for lava at a spot safe from it (see {@link LavaSources}). Returns true once filled.
	 */
	private boolean fillAt(ServerLevel level, Colony colony, Item bucket, int slot) {
		BlockPos source = this.source(level, colony, bucket);
		if (source == null) {
			this.cooldown = IDLE_TICKS;
			return false;
		}
		Goal goal = bucket == Items.LAVA_BUCKET ? new SafeSpot(this.inSight(source), this.safeFromLava) : this.inSight(source);
		PathFollower.Status status = this.approach(goal, source);
		if (status == PathFollower.Status.FAILED) {
			this.hiddenSources.add(source);
			this.sources.remove(bucket);
		} else if (status == PathFollower.Status.ARRIVED) {
			this.sources.remove(bucket);
			return !this.scoop(level, source, slot).isEmpty();
		}
		return false;
	}

	/** {@code goal}, reached only where {@code safe} says the bot stands safe. */
	private record SafeSpot(Goal goal, Predicate<BlockPos> safe) implements Goal {
		@Override
		public boolean isReached(BlockPos feet) {
			return this.goal.isReached(feet) && this.safe.test(feet);
		}

		@Override
		public boolean isReached(BlockPos feet, Predicate<BlockPos> broken) {
			return this.goal.isReached(feet, broken) && this.safe.test(feet);
		}

		@Override
		public double heuristic(BlockPos feet) {
			return this.goal.heuristic(feet);
		}
	}

	/** The source nearest the bot to fill an empty bucket into {@code bucket} at, looked up again now and then. */
	private @Nullable BlockPos source(ServerLevel level, Colony colony, Item bucket) {
		if (level.getGameTime() >= this.nextSourceScan) {
			this.sources.clear();
			this.nextSourceScan = level.getGameTime() + SOURCE_SCAN_TICKS;
		}
		return this.sources.computeIfAbsent(bucket, key -> {
			Optional<BlockPos> found = Optional.ofNullable(source(level, colony, this.bot.blockPosition(), key, this.hiddenSources));
			if (found.isEmpty()) {
				// Looked at again on the next scan: a wall may be gone by then.
				this.hiddenSources.clear();
			}
			return found;
		}).orElse(null);
	}

	/** The fluid source in the territory nearest {@code from} that fills an empty bucket into {@code bucket}; never one a plan places or placed, such as a cobblestone generator's, so a builder does not undo its own work, nor one in {@code skip}. */
	private static @Nullable BlockPos source(ServerLevel level, Colony colony, BlockPos from, Item bucket, Set<BlockPos> skip) {
		Set<BlockPos> planned = planned(colony);
		Predicate<BlockPos> fills = pos -> {
			FluidState fluid = level.getFluidState(pos);
			return fluid.isSource() && fluid.getType().getBucket() == bucket && level.getBlockState(pos).getBlock() instanceof LiquidBlock && !planned.contains(pos) && !skip.contains(pos)
				&& !colony.isProtected(level::getBlockState, pos);
		};
		if (bucket != Items.LAVA_BUCKET) {
			return nearest(level, colony, from, fills);
		}
		// Lava also in the mine, beside the cells dug out, and only where a bot can fill a bucket safe from it.
		Set<BlockPos> found = new HashSet<>(all(level, colony, fills));
		for (BlockPos cell : colony.mineCells()) {
			for (Direction side : Direction.values()) {
				BlockPos pos = cell.relative(side);
				if (fills.test(pos)) {
					found.add(pos);
				}
			}
		}
		return LavaSources.choose(level::getBlockState, from, found, REACH).map(LavaSources.Fill::source).orElse(null);
	}

	/** Whether {@code item} is a full bucket, poured as a fluid source or into a waterlogged block. */
	private static boolean isPoured(Item item) {
		return item instanceof BucketItem && item != Items.BUCKET;
	}

	/**
	 * Breaks what is in the way of {@code step}, or gets a fluid out of the way: it scoops a source
	 * up with an empty bucket, or else fills it with a block such as dirt, which it breaks next.
	 */
	private void clear(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, BuildPlan.Step step) {
		BlockPos pos = step.pos();
		SimpleContainer inventory = this.bot.getInventory();
		BuildPlan.Clearing clearing = this.clearing(level, core, step);
		List<BlockPos> walls = clearing == BuildPlan.Clearing.BLOCKED ? this.fluidsToWall(level, core, step) : List.of();
		if (!walls.isEmpty()) {
			this.digAndWall(level, colony, core, pos, walls);
			return;
		}
		switch (clearing) {
			case SCOOP -> {
				int bucket = findSlot(inventory, BuilderGoal::isBucket);
				if (bucket < 0) {
					this.fetch(level, colony, core, plan, Items.BUCKET);
				} else if (this.reachStep(level, pos)) {
					this.scoop(level, pos, bucket);
				}
			}
			case FILL -> this.fillIn(level, colony, core, plan, pos);
			default -> {
				if (this.reachStep(level, pos) && this.breaker.tick(level, pos, true)) {
					colony.unprotect(pos);
					this.dug(pos);
				}
			}
		}
	}

	/**
	 * Fills the fluid at {@code pos} with a filler block, fetching one from the core first: dirt while
	 * neither the bot nor the core has a pickaxe, since cobblestone is slow to break by hand (see {@link BuildPlan#waterFiller}).
	 * It fills only where it sees {@code pos}, from the water where no dry spot does (see {@link #reachStep}). While {@code pos} stays taken it waits, and gives the step up after a while.
	 */
	private void fillIn(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, BlockPos pos) {
		Optional<List<Item>> filler = this.fillers(core, 1);
		if (filler.isEmpty()) {
			return;
		}
		Item item = filler.get().getFirst();
		if (this.bot.getInventory().countItem(item) == 0) {
			this.fetch(level, colony, core, plan, item);
		} else if (this.reachStep(level, pos)) {
			BlockState block = Block.byItem(item).defaultBlockState();
			if (this.isFreeToPlace(level, pos, block)) {
				this.put(level, pos, block, item);
			} else {
				this.waitForSpot(level, pos);
			}
		}
	}

	/**
	 * Breaks the block at {@code pos} that holds back the fluid at {@code walls}, and in the same tick walls the fluid off
	 * with filler blocks, before it can flow in: as a player digging into water it could not see through the rock, it
	 * stands where it sees the block and, once that is gone, the fluid behind it, both within reach. Filler blocks as in
	 * {@link #fillIn}, one per wall, of mixed kinds where one runs short, all fetched at once first. With no spot that sees both, the block is given up until the bot
	 * digs another next to it, which may open a view, such as the block above a tunnel too low to see through.
	 */
	private void digAndWall(ServerLevel level, Colony colony, @Nullable Container core, BlockPos pos, List<BlockPos> walls) {
		Optional<List<Item>> fillers = this.fillers(core, walls.size());
		if (fillers.isEmpty()) {
			return;
		}
		List<Item> items = fillers.get();
		SimpleContainer inventory = this.bot.getInventory();
		if (items.stream().anyMatch(item -> inventory.countItem(item) < Collections.frequency(items, item))) {
			// Takes all of them at once: a fetch of one kind would put another it carries back as spare. The core holds the rest, or fillers were empty.
			if (core != null && this.reach(colony.core())) {
				items.stream().distinct().forEach(item -> Storage.moveItems(core, inventory, stack -> stack.is(item),
					Math.max(0, Collections.frequency(items, item) - inventory.countItem(item))));
			}
			return;
		}
		Goal.Sight through = new Through(this.sight, pos);
		List<Goal> goals = new ArrayList<>(List.of(this.inSight(pos)));
		walls.forEach(wall -> goals.add(new Goal.InSight(wall, REACH, through)));
		List<BlockState> blocks = items.stream().map(item -> Block.byItem(item).defaultBlockState()).toList();
		if (!this.reachStep(level, pos, new All(goals))) {
			if (this.unreachable.contains(pos)) {
				this.noView.add(pos);
			}
			return;
		}
		if (!IntStream.range(0, walls.size()).allMatch(i -> this.isFreeToPlace(level, walls.get(i), blocks.get(i)))) {
			// A wall that cannot go in, such as where a mob stays in the water, gives the dig up after a while.
			this.waitForSpot(level, pos);
		} else if (this.breaker.tick(level, pos, true)) {
			colony.unprotect(pos);
			IntStream.range(0, walls.size()).forEach(i -> this.put(level, walls.get(i), blocks.get(i), items.get(i)));
			this.dug(pos);
		}
	}

	/**
	 * A filler block for each of {@code count} fluids to wall off, of the kinds the bot and the core hold between them, the
	 * one {@link BuildPlan#waterFiller} prefers first and others where that runs short; empty if they hold fewer than {@code count}.
	 */
	private Optional<List<Item>> fillers(@Nullable Container core, int count) {
		boolean pickaxe = this.has(core, stack -> stack.is(ItemTags.PICKAXES));
		List<Item> fillers = new ArrayList<>();
		while (fillers.size() < count) {
			Optional<Item> next = BuildPlan.waterFiller(item -> this.bot.getInventory().countItem(item) + (core == null ? 0 : core.countItem(item))
				> Collections.frequency(fillers, item), pickaxe);
			if (next.isEmpty()) {
				return Optional.empty();
			}
			fillers.add(next.get());
		}
		return Optional.of(fillers);
	}

	/** Tries the cells next to {@code pos} it found no view for again, now that the block at {@code pos} is gone. */
	private void dug(BlockPos pos) {
		this.noView.removeIf(cell -> {
			boolean near = cell.closerThan(pos, 2);
			if (near) {
				this.unreachable.remove(cell);
				this.failures.remove(cell);
			}
			return near;
		});
	}

	/** {@code sight}, with the block at {@code broken} as air, such as one about to be broken. A record, so goals compare by value. */
	private record Through(Goal.Sight sight, BlockPos broken) implements Goal.Sight {
		@Override
		public boolean sees(BlockPos feet, BlockPos target, Predicate<BlockPos> broken) {
			return this.sight.sees(feet, target, broken.or(this.broken::equals));
		}
	}

	/** Every one of {@code goals} at once; estimated by the first. */
	private record All(List<Goal> goals) implements Goal {
		@Override
		public boolean isReached(BlockPos feet) {
			return this.goals.stream().allMatch(goal -> goal.isReached(feet));
		}

		@Override
		public boolean isReached(BlockPos feet, Predicate<BlockPos> broken) {
			return this.goals.stream().allMatch(goal -> goal.isReached(feet, broken));
		}

		@Override
		public double heuristic(BlockPos feet) {
			return this.goals.getFirst().heuristic(feet);
		}
	}

	/**
	 * The fluids beside or above the block that step {@code step} digs out (see {@link #isDug}) that hold it standing (see
	 * {@link BuildPlan.Step#clearing}), such as water in the rock beside a mineshaft or in a hall that is dug later: the
	 * builder walls them off with filler blocks as it breaks the block, so they cannot flow in. Water the plan clears
	 * itself, in a cell beside, is filled in so too, as it is not seen before (see {@link #isBehindUndug}). Empty if there are none, or no filler at hand.
	 */
	private List<BlockPos> fluidsToWall(ServerLevel level, @Nullable Container core, BuildPlan.Step step) {
		return fluidsToWall(level, step, this.has(core, BuilderGoal::isFiller));
	}

	private static List<BlockPos> fluidsToWall(ServerLevel level, BuildPlan.Step step, boolean hasFiller) {
		if (!isDug(level, step) || !level.getFluidState(step.pos()).isEmpty() || !hasFiller) {
			return List.of();
		}
		List<BlockPos> walls = new ArrayList<>();
		for (Direction side : new Direction[] {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			BlockPos beside = step.pos().relative(side);
			BlockState state = level.getBlockState(beside);
			if (!state.getFluidState().isEmpty()) {
				if (!state.canBeReplaced()) {
					return List.of();
				}
				walls.add(beside);
			}
		}
		return walls;
	}

	/** How {@code step}'s block is cleared with what the bot and the core hold; see {@link BuildPlan.Step#clearing}. */
	private BuildPlan.Clearing clearing(ServerLevel level, @Nullable Container core, BuildPlan.Step step) {
		return clearing(level, step, this.has(core, BuilderGoal::isBucket), this.has(core, BuilderGoal::isFiller));
	}

	/**
	 * Whether a bot could do a step now, with the buckets and filler blocks the colony holds, its bots'
	 * included: such as once a blocking block is gone, or once there is a full bucket in the core or a
	 * source to fill one at for a fluid, or a filler block for each fluid to wall off as a block beside it is dug. Never for a
	 * block a player cannot place, so that step stays reported, nor for a fluid waiting for a wall of its plan still to place.
	 */
	public static BiPredicate<BuildPlan, BuildPlan.Step> workable(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		Map<Item, Integer> held = Storage.held(level, colony);
		boolean hasBucket = held.getOrDefault(Items.BUCKET, 0) > 0;
		int fillers = FILLERS.stream().mapToInt(item -> held.getOrDefault(item, 0)).sum();
		boolean hasFiller = fillers > 0;
		// ponytail: looks for a source once per fluid and recheck, from the core rather than per step.
		Map<Item, Boolean> canPour = new HashMap<>();
		return (plan, step) -> {
			Item item = step.item(level.getBlockState(step.pos()));
			return isPossible(level, step) && !leavesAlone(level, plan, step) && !plan.waitsForWalls(level::getBlockState, step)
				&& (clearing(level, step, hasBucket, hasFiller) != BuildPlan.Clearing.BLOCKED || isWallable(level, step, fillers))
				&& (!isPoured(item) || canPour.computeIfAbsent(item,
					bucket -> core != null && core.countItem(bucket) > 0 || source(level, colony, colony.core(), bucket, Set.of()) != null));
		};
	}

	/** Whether {@code step}'s block can be dug and its fluids walled off as it goes (see {@link #digAndWall}) with {@code fillers} filler blocks. */
	private static boolean isWallable(ServerLevel level, BuildPlan.Step step, int fillers) {
		int walls = fluidsToWall(level, step, fillers > 0).size();
		return walls > 0 && walls <= fillers && !isUnbreakable(level, step.pos());
	}

	/**
	 * Whether {@code step} digs its block out and leaves the spot open to the fluids beside it: a clear step, or one that
	 * places a block that does not fill its spot, such as a ladder in a mineshaft, where a block stands in the way that
	 * has to be broken first. A full block put in place of the one broken shuts the fluid out again.
	 */
	private static boolean isDug(ServerLevel level, BuildPlan.Step step) {
		return step.isClear() || step.needsClearing(level.getBlockState(step.pos())) && !step.state().isCollisionShapeFullBlock(level, step.pos());
	}

	private static BuildPlan.Clearing clearing(ServerLevel level, BuildPlan.Step step, boolean hasBucket, boolean hasFiller) {
		BlockPos pos = step.pos();
		BuildPlan.Clearing clearing = BuildPlan.Clearing.BREAK;
		// A wrong block in a place step is simply replaced, fluid or not, so only steps that dig a block out mind fluids.
		if (isDug(level, step)) {
			FluidState fluid = level.getFluidState(pos);
			boolean settling = !fluid.isEmpty() && level.getFluidTicks().hasScheduledTick(pos, fluid.getType());
			clearing = step.clearing(level::getBlockState, settling, hasBucket, hasFiller);
		}
		return clearing == BuildPlan.Clearing.BREAK && isUnbreakable(level, pos) ? BuildPlan.Clearing.BLOCKED : clearing;
	}

	private boolean has(@Nullable Container core, Predicate<ItemStack> filter) {
		return findSlot(this.bot.getInventory(), filter) >= 0 || core != null && findSlot(core, filter) >= 0;
	}

	private static boolean isBucket(ItemStack stack) {
		return stack.is(Items.BUCKET);
	}

	private static boolean isFiller(ItemStack stack) {
		return FILLERS.contains(stack.getItem());
	}

	/** Whether a plan still has a block in the way that is dug with a pickaxe, such as stone. */
	private static boolean needsPickaxe(ServerLevel level, Colony colony) {
		return colony.builds().stream().flatMap(plan -> plan.steps().stream()).anyMatch(step -> {
			BlockState state = level.getBlockState(step.pos());
			return state.is(BlockTags.MINEABLE_WITH_PICKAXE) && step.needsClearing(state);
		});
	}

	/**
	 * Whether {@code plan} waits for materials, for a fluid to settle or, for a {@code dig} job, for
	 * a tree in it to be felled, rather than being left only with steps it cannot do. A fluid waits
	 * for an empty bucket only while there is a source to fill it at, and for a wall of another batch while it is not blocked.
	 */
	private boolean isWaiting(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, boolean dig) {
		return plan.nextStep(level::getBlockState, step -> {
			BlockState current = level.getBlockState(step.pos());
			if (dig && Digging.isForTreeJob(level, colony, step.pos())) {
				return true;
			}
			if (leavesAlone(level, plan, step) || this.isGivenUp(step)) {
				return false;
			}
			Item item = step.item(current);
			// A fluid waiting for its walls in its batch waits only as long as they do, which they tell for themselves.
			if (step.needsClearing(current)) {
				return this.clearing(level, core, step) == BuildPlan.Clearing.SETTLE;
			}
			if (!isPossible(level, step)) {
				return false;
			}
			return plan.waitsForWalls(level::getBlockState, step) ? waitsForOtherBatches(level, colony, plan, step)
				: !isPoured(item) || this.source(level, colony, item) != null;
		}).isPresent();
	}

	/**
	 * Whether the fluid of {@code step} waits for a wall of another batch of its plan (see {@link BuildPlan#walls}) that is
	 * not reported blocked: that batch's builder places it in time. One reported blocked leaves the fluid to be reported too.
	 */
	private static boolean waitsForOtherBatches(ServerLevel level, Colony colony, BuildPlan plan, BuildPlan.Step step) {
		List<BlockPos> blocked = colony.blockedSteps();
		return plan.missingWalls(level::getBlockState, step).stream()
			.anyMatch(wall -> plan.walls().contains(wall) && !blocked.contains(wall.pos()));
	}

	/** Whether a block of {@code plan} is still to clear once a fluid there settles, so the plan is not worked through yet. */
	private boolean settles(ServerLevel level, @Nullable Container core, BuildPlan plan) {
		return plan.steps().stream().anyMatch(step -> step.needsClearing(level.getBlockState(step.pos()))
			&& this.clearing(level, core, step) == BuildPlan.Clearing.SETTLE);
	}

	/**
	 * Whether a terraform's {@code step} is left alone for lava, to be reported as blocked: a fill
	 * step where lava is, or a block to clear that is lava or holds it back (see {@link Terraform#touchesLava}).
	 */
	private static boolean leavesAlone(ServerLevel level, BuildPlan plan, BuildPlan.Step step) {
		if (plan.kind() != BuildPlan.Kind.TERRAFORM) {
			return false;
		}
		return step.isClear() ? Terraform.touchesLava(level::getBlockState, step.pos()) : level.getFluidState(step.pos()).is(FluidTags.LAVA);
	}

	/**
	 * Whether a builder can do {@code step} at all, with what it needs at hand: fire only where it
	 * burns forever and cannot spread (see {@link #canLight}), no block that burns where lit fire
	 * spreads to it, and a door or bed not while an unbreakable block, such as bedrock, stands where its other half goes.
	 */
	public static boolean isPossible(LevelReader level, BuildPlan.Step step) {
		return step.isPlaceable() && (!step.isLit() || canLight(level, step)) && !isOtherHalfBlocked(level, step)
			&& (step.isPlaced(level.getBlockState(step.pos())) || !FireSpread.endangers(level::getBlockState, step.pos(), step.state()));
	}

	/** Whether {@code step}'s block is still to place and an unbreakable block stands where its other half goes. */
	private static boolean isOtherHalfBlocked(LevelReader level, BuildPlan.Step step) {
		return !step.isPlaced(level.getBlockState(step.pos())) && step.otherHalf()
			.filter(half -> half.needsClearing(level.getBlockState(half.pos())) && isUnbreakable(level, half.pos())).isPresent();
	}

	/**
	 * Whether flint and steel lights {@code step}'s fire where it goes and it stays lit without
	 * burning anything: soul fire on soul soil or sand, which never goes out, and fire only on a
	 * block that burns forever in this dimension, such as netherrack, since fire on stone goes out
	 * and would be lit again until the tool breaks. Neither is lit while it would spread to a block
	 * that burns (see {@link FireSpread}; soul fire never spreads, so it is lit whatever stands beside it), so a fireplace walled in by stone is lit in a wooden house.
	 */
	private static boolean canLight(LevelReader level, BuildPlan.Step step) {
		BlockPos pos = step.pos();
		BlockState fire = BaseFireBlock.getState(level, pos);
		return fire.is(step.state().getBlock()) && fire.canSurvive(level, pos)
			&& (!fire.is(Blocks.FIRE) || level.getBlockState(pos.below()).is(level.dimensionType().infiniburn()))
			&& (fire.is(Blocks.SOUL_FIRE) || !FireSpread.reachesFlammable(level::getBlockState, pos));
	}

	private static boolean isUnbreakable(LevelReader level, BlockPos pos) {
		return level.getBlockState(pos).getDestroySpeed(level, pos) < 0;
	}

	/**
	 * Nothing in the batch can be worked on now. With nothing left the batch is done, and the plan with its last batch; with only
	 * blocks or fluids it cannot clear left in the way those steps are reported, and the plan waits
	 * for the colony to find them workable again. While it waits for materials, which the colony
	 * orders, for a fluid to settle or for a tree in a dig batch to be felled, the job is freed for later; only
	 * while it waits for nothing but materials may the batches that need this one start meanwhile.
	 */
	private void putAside(ServerLevel level, Colony colony, @Nullable Container core, Job job, Optional<BuildPlan> plan, boolean dig) {
		if (plan.isPresent() && this.isWaiting(level, colony, core, plan.get(), dig)) {
			Colony.Wait reason = this.settles(level, core, plan.get()) ? Colony.Wait.FLUID
				: dig && Digging.waitsForTrees(level, colony, plan.get()) ? Colony.Wait.TREES : Colony.Wait.ITEMS;
			colony.batchWaiting(job.id(), reason);
			// Steps out of reach now may not be once it is back, such as leaves above a tree felled meanwhile.
			this.forgetUnreachable();
			this.endJob(level, WorkerGoal.Outcome.RELEASE);
		} else {
			plan.ifPresent(p -> {
				List<BuildPlan.Step> blocked = p.steps().stream().filter(step -> !step.isDone(level.getBlockState(step.pos()))).toList();
				if (blocked.isEmpty()) {
					colony.finishBatch(job.id());
				} else {
					// Those it found no way to are not retried every survey, only now and then (see Colony#recheckBlocked).
					Map<BlockPos, Colony.BlockReason> reasons = new LinkedHashMap<>();
					blocked.forEach(step -> reasons.put(step.pos(), this.blockReason(level, colony, core, p, step)));
					colony.reportBlocked(job.id(), reasons);
				}
			});
			// Once the colony finds the steps workable again, say a player opened the way, they are tried afresh.
			this.forgetUnreachable();
			this.endJob(level, WorkerGoal.Outcome.COMPLETE);
		}
	}

	/**
	 * Why {@code step} of {@code plan} is left undone: unreachable if this bot found no way to it, no lava or water found
	 * for a fluid to pour with no full bucket at hand and no source in reach to fill one at, or else for some other reason.
	 */
	private Colony.BlockReason blockReason(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, BuildPlan.Step step) {
		if (this.isGivenUp(step)) {
			return Colony.BlockReason.UNREACHABLE;
		}
		BlockState current = level.getBlockState(step.pos());
		Item item = step.item(current);
		if (step.needsClearing(current) || !isPoured(item) || plan.waitsForWalls(level::getBlockState, step)
				|| this.has(core, stack -> stack.is(item)) || this.source(level, colony, item) != null) {
			return Colony.BlockReason.OTHER;
		}
		return item == Items.LAVA_BUCKET ? Colony.BlockReason.NO_LAVA
			: item == Items.WATER_BUCKET ? Colony.BlockReason.NO_WATER
			: Colony.BlockReason.OTHER;
	}

	/**
	 * Places {@code step}'s block; a waterlogged one dry, to be filled from a bucket next. A door or
	 * bed goes in whole from its one item, as a player places it, since either half alone breaks.
	 */
	private void place(ServerLevel level, Colony colony, BuildPlan.Step step) {
		step.otherHalf().ifPresent(half -> {
			level.setBlockAndUpdate(step.pos(), step.dry());
			level.setBlockAndUpdate(half.pos(), half.state());
			this.protect(colony, half.pos(), half.state().getBlock());
		});
		this.put(level, step.pos(), step.dry(), step.item());
		this.protect(colony, step.pos(), step.state().getBlock());
	}

	/** Protects a plan block just placed; it is no scaffold, even where scaffold of a plan added later stood. */
	private void protect(Colony colony, BlockPos pos, Block block) {
		colony.protect(pos, block);
		this.bot.getScaffold().remove(pos);
	}

	/** Lights {@code step}'s fire with the flint and steel the bot carries, as a player would, wearing the tool a little. */
	private void light(ServerLevel level, Colony colony, BuildPlan.Step step) {
		BlockPos pos = step.pos();
		if (!BaseFireBlock.canBePlacedAt(level, pos, this.bot.getDirection())) {
			return;
		}
		level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);
		level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL_IMMEDIATE);
		this.protect(colony, pos, step.state().getBlock());
		level.gameEvent(this.bot, GameEvent.BLOCK_PLACE, pos);
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		ItemStack tool = this.bot.getInventory().getItem(findSlot(this.bot.getInventory(), stack -> stack.is(Items.FLINT_AND_STEEL)));
		tool.hurtAndBreak(1, level, null, broken -> { });
	}

	/** Pours the water bucket {@code bucket} into the block {@code current} at {@code pos}, as a player would, leaving the bucket empty. */
	private void pour(ServerLevel level, BlockPos pos, BlockState current, Item bucket) {
		if (current.getBlock() instanceof LiquidBlockContainer container && container.placeLiquid(level, pos, current, Fluids.WATER.getSource(false))) {
			level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
			level.gameEvent(this.bot, GameEvent.FLUID_PLACE, pos);
			this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			this.bot.getInventory().setItem(findSlot(this.bot.getInventory(), stack -> stack.is(bucket)), new ItemStack(Items.BUCKET));
		}
	}

	/** Places {@code state} at {@code pos}, using up one {@code item} unless that is air; a fluid's bucket is left empty. */
	private void put(ServerLevel level, BlockPos pos, BlockState state, Item item) {
		state = Block.updateFromNeighbourShapes(state, level, pos);
		level.setBlockAndUpdate(pos, state);
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(this.bot, GameEvent.BLOCK_PLACE, pos);
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		if (isPoured(item)) {
			this.bot.getInventory().setItem(findSlot(this.bot.getInventory(), stack -> stack.is(item)), new ItemStack(Items.BUCKET));
		} else if (item != Items.AIR) {
			Storage.take(this.bot.getInventory(), item, 1);
		}
	}

	/**
	 * Takes as many of {@code item} from the core as the plan still needs, and on the same trip what the plan needs of
	 * every other item, as far as its free slots go (see {@link BuildPlan#fetchList}), leaving there whatever
	 * the bot carries that the plan does not need, such as sand when only steps that must hold are
	 * left to fill (see {@link BuildPlan#wantedFillBlocks}). The colony survey orders what the core lacks.
	 * While the plan has steps left to pillar up to, it keeps its scaffold blocks and tops them up
	 * to {@link #SCAFFOLD_STOCK} on the same visit, besides any the plan itself places.
	 */
	private void fetch(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, Item item) {
		Map<Item, Integer> needed = plan.materials(level::getBlockState);
		// Steps any solid block will do take whichever filler there is that they can use.
		Set<Item> fills = new HashSet<>(plan.wantedFillBlocks(level::getBlockState));
		int fillers = plan.steps().stream().filter(step -> step.anySolid() && !step.isDone(level.getBlockState(step.pos()))
			&& (step.mayFall() || BuildPlan.HOLDING_FILL_BLOCKS.contains(item))).mapToInt(step -> 1).sum();
		// Likewise steps any stone stairs will do take whichever stone stairs there are.
		int stairs = plan.stoneStairMaterials(level::getBlockState).values().stream().mapToInt(Integer::intValue).sum();
		if (stairs > 0) {
			fills.addAll(BuildPlan.STONE_STAIRS);
		}
		// And a hatch any wooden trapdoor will do, so the bot keeps the one it takes.
		plan.steps().stream().filter(step -> step.anyWoodenTrapdoor() && !step.isDone(level.getBlockState(step.pos())))
			.findAny().ifPresent(step -> fills.addAll(step.standIns()));
		int count = BuildPlan.STONE_STAIRS.contains(item) && stairs > 0 ? Math.max(stairs, needed.getOrDefault(item, 1))
			: fills.contains(item) ? Math.max(fillers, needed.getOrDefault(item, 1)) : needed.getOrDefault(item, 1);
		if (core == null) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.reach(colony.core())) {
			SimpleContainer inventory = this.bot.getInventory();
			boolean pillaring = this.hasHighSteps(level, plan);
			Predicate<ItemStack> spare = stack -> !needed.containsKey(stack.getItem()) && !stack.is(ItemTags.PICKAXES) && !fills.contains(stack.getItem());
			Storage.moveItems(inventory, core, stack -> spare.test(stack) && !(pillaring && stack.is(SCAFFOLD)));
			// The rest of what the batch needs comes along on the same trip, such as the torches and the chest of a shaft
			// level fetched with its ladders, leaving room for what it digs.
			BuildPlan.fetchList(item, count, needed, Storage.stock(inventory), Storage.stock(core), freeSlots(inventory) - FETCH_FREE_SLOTS)
				.forEach((kind, taken) -> Storage.moveItems(core, inventory, stack -> stack.is(kind), taken));
			if (pillaring) {
				int stock = SCAFFOLD_STOCK + needed.entrySet().stream()
					.filter(entry -> entry.getKey().getDefaultInstance().is(SCAFFOLD)).mapToInt(Map.Entry::getValue).sum();
				int carried = PathFollower.scaffoldCount(inventory, SCAFFOLD);
				if (carried > stock) {
					Storage.moveItems(inventory, core, stack -> spare.test(stack) && stack.is(SCAFFOLD), carried - stock);
				} else {
					Storage.moveItems(core, inventory, stack -> stack.is(SCAFFOLD), stock - carried);
				}
			}
		}
	}

	/** Whether {@code plan} still has a step too high to reach from where the bot stands, which it pillars up to (see {@link #restockScaffold}). */
	private boolean hasHighSteps(ServerLevel level, BuildPlan plan) {
		return plan.steps().stream().anyMatch(step -> step.pos().getY() - this.bot.getBlockY() >= SCAFFOLD_HEIGHT
			&& !step.isDone(level.getBlockState(step.pos())));
	}
}
