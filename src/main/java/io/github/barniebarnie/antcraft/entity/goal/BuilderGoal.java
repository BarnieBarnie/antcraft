package io.github.barniebarnie.antcraft.entity.goal;

import com.google.common.base.Suppliers;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.Terraform;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * Builder work loop: claim a build job, a batch of a plan (see {@link BuildPlan.Batch}), and work through it step by step. For each block
 * to place it fetches what the batch still needs of it from the core; steps whose item the core
 * lacks wait while the rest goes on. It places blocks only where no bot or mob stands, and blocks
 * it places are protected from other bots. It digs with the best pickaxe in the core, such as
 * down a mineshaft, and orders one if it has none and a plan has such digging to do. Fluids in the
 * way it scoops up with a bucket or fills with a cheap block from the core, and fluid beside a block
 * it digs that the plan does not clear it walls off with such a block; those it cannot clear are
 * reported as blocked. It pours water and lava from a bucket, as a player would, filling an
 * empty one at a source in the territory when the core has no full one; a waterlogged block it
 * places dry and fills from a water bucket the same way, and one standing waterlogged where the plan
 * wants it dry it empties with an empty bucket. A door, bed or tall plant it places whole
 * from one item, clearing the spot for its other half first; one whose other half would go into
 * an unbreakable block is reported. Fire it lights on the block below with flint and steel from
 * the core, which the colony orders when it has none; fire that cannot stay there, blocks a
 * player cannot place, such as a portal, and a fluid without a bucket or source
 * to get it from are reported too. To reach a high step it pillars up with scaffold blocks from the
 * core, never where a plan puts a block, and takes the scaffolding down before it puts a plan aside.
 * While the colony has no miner it also digs out dig jobs (see {@link Digging}), and with one it
 * takes those its miners leave for water. A step any solid block will do, such as a terraform's
 * fill, takes whichever fill block there is, sand and gravel too. In a terraform it leaves lava and
 * the blocks holding it back alone, to be reported as blocked.
 */
public class BuilderGoal extends ColonyWorkGoal {
	/** Cheap blocks a builder fills a fluid source with to clear it without a bucket. */
	private static final Set<Item> FILLERS = Set.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
	private static final Set<BuildPlan.Clearing> WORKABLE = EnumSet.of(BuildPlan.Clearing.BREAK, BuildPlan.Clearing.SCOOP, BuildPlan.Clearing.FILL);
	/** How often the builder looks again for fluid sources to fill a bucket at. */
	private static final int SOURCE_SCAN_TICKS = 100;
	/** The scaffold blocks builders pillar with: ones they take down again quickly without a tool. */
	private static final TagKey<Item> SCAFFOLD = PathFollower.FAST_SCAFFOLD_BLOCKS;
	/** Takes scaffold blocks from the core for a high step when carrying fewer than this. */
	private static final int RESTOCK_BELOW = 8;
	private static final int SCAFFOLD_STOCK = 16;
	/** A step this many blocks above the bot's feet or more is out of reach from where it stands, so it takes scaffold first. */
	private static final int SCAFFOLD_HEIGHT = 4;

	/** Fluid sources found per full bucket they fill, cleared every {@link #SOURCE_SCAN_TICKS}. */
	private final Map<Item, Optional<BlockPos>> sources = new HashMap<>();
	private long nextSourceScan;
	private final ScaffoldCleanup scaffoldCleanup;
	/** Taking its scaffolding down, before putting a plan aside or once it has no job. */
	private boolean cleaning;

	public BuilderGoal(AntBot bot) {
		super(bot, BotRole.BUILDER);
		this.follower.setScaffold(SCAFFOLD);
		// Every block placed on the way is scaffold, taken down once the plan is done; plans never get scaffold where their own blocks go.
		this.follower.setPlaceListener(pos -> this.bot.addScaffold(pos, BotRole.BUILDER));
		this.scaffoldCleanup = new ScaffoldCleanup(bot, this.follower, this.breaker, this::travel, this::stopTravel);
	}

	@Override
	public void stop() {
		super.stop();
		if (this.cleaning) {
			this.scaffoldCleanup.cancelRetry();
			this.follower.setMayPlace(false);
		}
	}

	@Override
	protected void work(ServerLevel level, Colony colony) {
		if (this.upgradeTool(level, colony, ToolKind.PICKAXE, () -> needsPickaxe(level, colony))) {
			return;
		}
		Supplier<Set<BlockPos>> planned = Suppliers.memoize(() -> planned(colony));
		this.follower.setMayPlaceAt(pos -> !planned.get().contains(pos));
		if (this.cleaning) {
			this.cleanUp(level, colony);
			return;
		}
		Container core = Storage.containerAt(level, colony.core());
		// Dig jobs are for miners, but for water they leave to builders; a colony without one has its builders dig.
		boolean miner = Digging.hasMiner(level, colony);
		Optional<Job> job = colony.board().claim(this.bot.getUUID(), Set.of(JobType.BUILD, JobType.CLEAR_AREA), this.bot.blockPosition(), level.getGameTime(),
			// A plan left only with steps it cannot clear is claimed too, to be finished and reported.
			open -> colony.build(open.id()).filter(plan -> (!isDig(open) || !miner || Digging.needsBuilder(level, colony, plan))
				&& (this.nextStep(level, colony, core, plan, isDig(open)).isPresent() || !this.isWaiting(level, colony, core, plan, isDig(open)))).isPresent(),
			// A plan's batches in order where it can, so a lone builder digs a mineshaft from the top down.
			open -> colony.batchIndex(open.id()));
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			if (!this.bot.getScaffold().isEmpty()) {
				this.startCleanup();
			} else {
				this.cooldown = IDLE_TICKS;
			}
			return;
		}
		Optional<BuildPlan> plan = colony.build(job.get().id());
		boolean dig = isDig(job.get());
		Optional<BuildPlan.Step> next = plan.flatMap(p -> this.nextStep(level, colony, core, p, dig)).map(s -> this.withFiller(core, s));
		if (next.isEmpty()) {
			// The scaffolding comes down first, while the job is still this builder's.
			if (!this.bot.getScaffold().isEmpty()) {
				this.startCleanup();
			} else {
				this.putAside(level, colony, core, job.get(), plan, dig);
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
		if (this.restockScaffold(colony, core, step) || !this.reach(step.pos())) {
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
		}
	}

	private static boolean isDig(Job job) {
		return job.type() == JobType.CLEAR_AREA;
	}

	/**
	 * {@code step}, or for a step any solid block will do, such as a terraform's fill, the same step
	 * with a fill block such as cobblestone or sand that the bot or the core has, when neither has
	 * the step's own block.
	 */
	private BuildPlan.Step withFiller(@Nullable Container core, BuildPlan.Step step) {
		if (!step.anySolid() || this.has(core, stack -> stack.is(step.item()))) {
			return step;
		}
		SimpleContainer inventory = this.bot.getInventory();
		int slot = findSlot(inventory, BuilderGoal::isFillBlock);
		Item filler = slot >= 0 ? inventory.getItem(slot).getItem() : null;
		if (filler == null && core != null && (slot = findSlot(core, BuilderGoal::isFillBlock)) >= 0) {
			filler = core.getItem(slot).getItem();
		}
		return filler == null ? step : new BuildPlan.Step(step.pos(), Block.byItem(filler).defaultBlockState(), true);
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
	 * A {@code dig} job leaves the logs of trees to lumberjacks (see {@link Digging}).
	 */
	private Optional<BuildPlan.Step> nextStep(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, boolean dig) {
		return plan.nextStep(level::getBlockState, planned -> {
			BlockState current = level.getBlockState(planned.pos());
			if (dig && Digging.isForLumberjacks(level, colony, planned.pos()) || leavesAlone(level, plan, planned)) {
				return false;
			}
			BuildPlan.Step step = this.withFiller(core, planned);
			if (step.needsClearing(current)) {
				return WORKABLE.contains(this.clearing(level, core, step)) || this.fluidToWall(level, core, plan, step) != null;
			}
			Item item = step.item(current);
			if (!isPossible(level, step)) {
				return false;
			}
			return this.has(core, stack -> stack.is(item))
				|| isPoured(item) && this.has(core, BuilderGoal::isBucket) && this.source(level, colony, item) != null;
		});
	}

	/** Fills an empty bucket to pour {@code bucket}'s fluid with at the nearest source, fetching the empty bucket from the core first. */
	private void fill(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, Item bucket) {
		int empty = findSlot(this.bot.getInventory(), BuilderGoal::isBucket);
		BlockPos source = this.source(level, colony, bucket);
		if (empty < 0) {
			this.fetch(level, colony, core, plan, Items.BUCKET);
		} else if (source == null) {
			this.cooldown = IDLE_TICKS;
		} else if (this.reach(source)) {
			this.scoop(level, source, empty);
			this.sources.remove(bucket);
		}
	}

	/** The source nearest the bot to fill an empty bucket into {@code bucket} at, looked up again now and then. */
	private @Nullable BlockPos source(ServerLevel level, Colony colony, Item bucket) {
		if (level.getGameTime() >= this.nextSourceScan) {
			this.sources.clear();
			this.nextSourceScan = level.getGameTime() + SOURCE_SCAN_TICKS;
		}
		return this.sources.computeIfAbsent(bucket, key -> Optional.ofNullable(source(level, colony, this.bot.blockPosition(), key))).orElse(null);
	}

	/** The fluid source in the territory nearest {@code from} that fills an empty bucket into {@code bucket}; never one a plan places, so a builder does not undo its own work. */
	private static @Nullable BlockPos source(ServerLevel level, Colony colony, BlockPos from, Item bucket) {
		Set<BlockPos> planned = planned(colony);
		return nearest(level, colony, from, pos -> {
			FluidState fluid = level.getFluidState(pos);
			return fluid.isSource() && fluid.getType().getBucket() == bucket && level.getBlockState(pos).getBlock() instanceof LiquidBlock && !planned.contains(pos);
		});
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
		BlockPos wall = clearing == BuildPlan.Clearing.BLOCKED ? this.fluidToWall(level, core, plan, step) : null;
		if (wall != null) {
			this.fillIn(level, colony, core, plan, wall);
			return;
		}
		switch (clearing) {
			case SCOOP -> {
				int bucket = findSlot(inventory, BuilderGoal::isBucket);
				if (bucket < 0) {
					this.fetch(level, colony, core, plan, Items.BUCKET);
				} else if (this.reach(pos)) {
					this.scoop(level, pos, bucket);
				}
			}
			case FILL -> this.fillIn(level, colony, core, plan, pos);
			default -> {
				if (this.reach(pos) && this.breaker.tick(level, pos, true)) {
					colony.unprotect(pos);
				}
			}
		}
	}

	/** Fills the fluid at {@code pos} with a filler block such as dirt, fetching one from the core first. */
	private void fillIn(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, BlockPos pos) {
		SimpleContainer inventory = this.bot.getInventory();
		int filler = findSlot(inventory, BuilderGoal::isFiller);
		if (filler < 0) {
			this.fetch(level, colony, core, plan, core.getItem(findSlot(core, BuilderGoal::isFiller)).getItem());
		} else if (this.reach(pos)) {
			Item item = inventory.getItem(filler).getItem();
			BlockState block = Block.byItem(item).defaultBlockState();
			if (this.isFreeToPlace(level, pos, block)) {
				this.put(level, pos, block, item);
			}
		}
	}

	/**
	 * A fluid beside or above the block of clear step {@code step} that holds it standing (see
	 * {@link BuildPlan.Step#clearing}) and that {@code plan} does not clear itself, such as water
	 * in the rock beside a mineshaft or in a hall that is dug later: the builder walls it off with
	 * a filler block, so it cannot flow in. Null if there is none, or no filler at hand.
	 */
	private @Nullable BlockPos fluidToWall(ServerLevel level, @Nullable Container core, BuildPlan plan, BuildPlan.Step step) {
		if (!step.isClear() || !level.getFluidState(step.pos()).isEmpty() || !this.has(core, BuilderGoal::isFiller)) {
			return null;
		}
		Set<BlockPos> cleared = plan.steps().stream().filter(BuildPlan.Step::isClear).map(BuildPlan.Step::pos).collect(Collectors.toSet());
		for (Direction side : new Direction[] {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			BlockPos beside = step.pos().relative(side);
			BlockState state = level.getBlockState(beside);
			if (!state.getFluidState().isEmpty() && state.canBeReplaced() && !cleared.contains(beside)) {
				return beside;
			}
		}
		return null;
	}

	/** How {@code step}'s block is cleared with what the bot and the core hold; see {@link BuildPlan.Step#clearing}. */
	private BuildPlan.Clearing clearing(ServerLevel level, @Nullable Container core, BuildPlan.Step step) {
		return clearing(level, step, this.has(core, BuilderGoal::isBucket), this.has(core, BuilderGoal::isFiller));
	}

	/**
	 * Whether a builder with a bucket and a filler block at hand could do a step now, such as once
	 * a blocking block is gone, or once there is a full bucket in the core or a source to fill one
	 * at for a fluid. Never for a block a player cannot place, so that step stays reported.
	 */
	public static BiPredicate<BuildPlan, BuildPlan.Step> workable(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		// ponytail: looks for a source once per fluid and recheck, from the core rather than per step.
		Map<Item, Boolean> canPour = new HashMap<>();
		return (plan, step) -> {
			Item item = step.item(level.getBlockState(step.pos()));
			return isPossible(level, step) && !leavesAlone(level, plan, step) && clearing(level, step, true, true) != BuildPlan.Clearing.BLOCKED
				&& (!isPoured(item) || canPour.computeIfAbsent(item,
					bucket -> core != null && core.countItem(bucket) > 0 || source(level, colony, colony.core(), bucket) != null));
		};
	}

	private static BuildPlan.Clearing clearing(ServerLevel level, BuildPlan.Step step, boolean hasBucket, boolean hasFiller) {
		BlockPos pos = step.pos();
		BuildPlan.Clearing clearing = BuildPlan.Clearing.BREAK;
		// A wrong block in a place step is simply replaced, fluid or not, so only clear steps mind fluids.
		if (step.isClear()) {
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

	/** Whether {@code stack} is a block a step any solid block will do takes (see {@link BuildPlan#FILL_BLOCKS}); sand and gravel fall, so they never clear a fluid. */
	private static boolean isFillBlock(ItemStack stack) {
		return BuildPlan.FILL_BLOCKS.contains(stack.getItem());
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
	 * lumberjacks to fell a tree, rather than being left only with steps it cannot do. A fluid waits
	 * for an empty bucket only while there is a source to fill it at.
	 */
	private boolean isWaiting(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, boolean dig) {
		return plan.nextStep(level::getBlockState, step -> {
			BlockState current = level.getBlockState(step.pos());
			if (dig && Digging.isForLumberjacks(level, colony, step.pos())) {
				return true;
			}
			if (leavesAlone(level, plan, step)) {
				return false;
			}
			Item item = step.item(current);
			return step.needsClearing(current)
				? this.clearing(level, core, step) == BuildPlan.Clearing.SETTLE
				: isPossible(level, step) && (!isPoured(item) || this.source(level, colony, item) != null);
		}).isPresent();
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
	 * Whether a builder can do {@code step} at all, with what it needs at hand: fire only where it can
	 * stay, as flint and steel lights it, and a door or bed not while an unbreakable block, such as
	 * bedrock, stands where its other half goes.
	 */
	private static boolean isPossible(LevelReader level, BuildPlan.Step step) {
		return step.isPlaceable() && (!step.isLit() || canLight(level, step)) && !isOtherHalfBlocked(level, step);
	}

	/** Whether {@code step}'s block is still to place and an unbreakable block stands where its other half goes. */
	private static boolean isOtherHalfBlocked(LevelReader level, BuildPlan.Step step) {
		return !step.isPlaced(level.getBlockState(step.pos())) && step.otherHalf()
			.filter(half -> half.needsClearing(level.getBlockState(half.pos())) && isUnbreakable(level, half.pos())).isPresent();
	}

	/** Whether flint and steel lights {@code step}'s fire where it goes: on the block below it can stay on, soul fire on soul soil or sand. */
	private static boolean canLight(LevelReader level, BuildPlan.Step step) {
		BlockState fire = BaseFireBlock.getState(level, step.pos());
		return fire.is(step.state().getBlock()) && fire.canSurvive(level, step.pos());
	}

	private static boolean isUnbreakable(LevelReader level, BlockPos pos) {
		return level.getBlockState(pos).getDestroySpeed(level, pos) < 0;
	}

	/**
	 * Nothing in the batch can be worked on now. With nothing left the batch is done, and the plan with its last batch; with only
	 * blocks or fluids it cannot clear left in the way those steps are reported, and the plan waits
	 * for the colony to find them workable again. While it waits for materials, which the colony
	 * orders, the job is freed for later, and the batches that need this one may start meanwhile.
	 */
	private void putAside(ServerLevel level, Colony colony, @Nullable Container core, Job job, Optional<BuildPlan> plan, boolean dig) {
		if (plan.isPresent() && this.isWaiting(level, colony, core, plan.get(), dig)) {
			colony.batchWaiting(job.id());
			colony.board().release(this.bot.getUUID());
			this.cooldown = IDLE_TICKS;
		} else {
			plan.ifPresent(p -> {
				List<BlockPos> blocked = p.steps().stream().filter(step -> !step.isDone(level.getBlockState(step.pos()))).map(BuildPlan.Step::pos).toList();
				if (blocked.isEmpty()) {
					colony.finishBatch(job.id());
				} else {
					colony.reportBlocked(job.id(), blocked);
				}
			});
			colony.board().complete(job.id());
		}
		this.hasJob = false;
		this.resetMovement();
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
	 * Takes as many of {@code item} from the core as the plan still needs, leaving there whatever
	 * the bot carries that the plan does not need. The colony survey orders what the core lacks.
	 * While the plan has steps left to pillar up to, it keeps its scaffold blocks and tops them up
	 * to {@link #SCAFFOLD_STOCK} on the same visit, besides any the plan itself places.
	 */
	private void fetch(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, Item item) {
		Map<Item, Integer> needed = plan.materials(level::getBlockState);
		// Steps any solid block will do take whichever filler there is.
		int fillers = plan.steps().stream().filter(step -> step.anySolid() && !step.isDone(level.getBlockState(step.pos()))).mapToInt(step -> 1).sum();
		int count = isFillBlock(item.getDefaultInstance()) ? Math.max(fillers, needed.getOrDefault(item, 1)) : needed.getOrDefault(item, 1);
		if (core == null) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.reach(colony.core())) {
			SimpleContainer inventory = this.bot.getInventory();
			boolean pillaring = this.hasHighSteps(level, plan);
			Predicate<ItemStack> spare = stack -> !needed.containsKey(stack.getItem()) && !stack.is(ItemTags.PICKAXES) && !(fillers > 0 && isFillBlock(stack));
			Storage.moveItems(inventory, core, stack -> spare.test(stack) && !(pillaring && stack.is(SCAFFOLD)));
			Storage.moveItems(core, inventory, stack -> stack.is(item), count);
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
