package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Builder work loop: claim a build job and work through its plan step by step. For each block
 * to place it fetches what the plan still needs of it from the core; steps whose item the core
 * lacks wait while the rest goes on. It places blocks only where no bot or mob stands, and blocks
 * it places are protected from other bots. It digs with the best pickaxe in the core, such as
 * down a mineshaft, and orders one if it has none and a plan has such digging to do. Fluids in the
 * way it scoops up with a bucket or fills with a cheap block from the core; those it cannot clear
 * are reported as blocked. It pours water and lava from a bucket, as a player would, filling an
 * empty one at a source in the territory when the core has no full one. Blocks a player cannot
 * place, such as fire, or a fluid without a bucket or source to get it from, are reported too.
 */
public class BuilderGoal extends ColonyWorkGoal {
	/** Cheap blocks a builder fills a fluid source with to clear it without a bucket. */
	private static final Set<Item> FILLERS = Set.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
	private static final Set<BuildPlan.Clearing> WORKABLE = EnumSet.of(BuildPlan.Clearing.BREAK, BuildPlan.Clearing.SCOOP, BuildPlan.Clearing.FILL);
	/** How often the builder looks again for fluid sources to fill a bucket at. */
	private static final int SOURCE_SCAN_TICKS = 100;

	/** Fluid sources found per full bucket they fill, cleared every {@link #SOURCE_SCAN_TICKS}. */
	private final Map<Item, Optional<BlockPos>> sources = new HashMap<>();
	private long nextSourceScan;

	public BuilderGoal(AntBot bot) {
		super(bot, BotRole.BUILDER);
		// ponytail: no scaffolding, so plans are built within reach of the ground; add it with cleanup for tall builds.
		this.follower.setMayPlace(false);
	}

	@Override
	protected void work(ServerLevel level, Colony colony) {
		if (this.upgradeTool(level, colony, ToolKind.PICKAXE, () -> needsPickaxe(level, colony))) {
			return;
		}
		Container core = Storage.containerAt(level, colony.core());
		Optional<Job> job = colony.board().claim(this.bot.getUUID(), Set.of(JobType.BUILD), this.bot.blockPosition(), level.getGameTime(),
			// A plan left only with steps it cannot clear is claimed too, to be finished and reported.
			open -> colony.build(open.id()).filter(plan -> this.nextStep(level, colony, core, plan).isPresent() || !this.isWaiting(level, colony, core, plan)).isPresent());
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		Optional<BuildPlan> plan = colony.build(job.get().id());
		Optional<BuildPlan.Step> next = plan.flatMap(p -> this.nextStep(level, colony, core, p));
		if (next.isEmpty()) {
			this.putAside(level, colony, core, job.get(), plan);
			return;
		}
		BuildPlan.Step step = next.get();
		// Whatever stands in the way goes first.
		if (step.needsClearing(level.getBlockState(step.pos()))) {
			this.clear(level, colony, core, plan.get(), step);
			return;
		}
		Item item = step.item();
		if (item != Items.AIR && this.bot.getInventory().countItem(item) == 0) {
			if (isPoured(step) && (core == null || core.countItem(item) == 0)) {
				this.fill(level, colony, core, plan.get(), item);
			} else {
				this.fetch(level, colony, core, plan.get(), item);
			}
			return;
		}
		if (!this.reach(step.pos())) {
			return;
		}
		if (this.isFreeToPlace(level, step.pos(), step.state())) {
			this.place(level, colony, step);
		}
	}

	/**
	 * The step to work on: the first one not done that is neither blocked by an unbreakable block
	 * nor waiting for an item that neither the bot nor the core has. Those are left for later, so
	 * the rest of the plan goes on, such as digging a mineshaft before its torches are made. A
	 * fluid also goes when a bucket can be filled at a source; a block without an item never does.
	 */
	private Optional<BuildPlan.Step> nextStep(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan) {
		return plan.nextStep(level::getBlockState, step -> {
			BlockState current = level.getBlockState(step.pos());
			if (step.needsClearing(current)) {
				return WORKABLE.contains(this.clearing(level, core, step));
			}
			Item item = step.item();
			if (item == Items.AIR) {
				return false;
			}
			return this.has(core, stack -> stack.is(item))
				|| isPoured(step) && this.has(core, BuilderGoal::isBucket) && this.source(level, colony, item) != null;
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
		Set<BlockPos> planned = colony.builds().stream().flatMap(plan -> plan.steps().stream()).map(BuildPlan.Step::pos).collect(Collectors.toSet());
		return nearest(level, colony, from, pos -> {
			FluidState fluid = level.getFluidState(pos);
			return fluid.isSource() && fluid.getType().getBucket() == bucket && level.getBlockState(pos).getBlock() instanceof LiquidBlock && !planned.contains(pos);
		});
	}

	/** Whether {@code step} pours a fluid from a bucket. */
	private static boolean isPoured(BuildPlan.Step step) {
		return step.state().getBlock() instanceof LiquidBlock && step.item() != Items.AIR;
	}

	/**
	 * Breaks what is in the way of {@code step}, or gets a fluid out of the way: it scoops a source
	 * up with an empty bucket, or else fills it with a block such as dirt, which it breaks next.
	 */
	private void clear(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, BuildPlan.Step step) {
		BlockPos pos = step.pos();
		SimpleContainer inventory = this.bot.getInventory();
		switch (this.clearing(level, core, step)) {
			case SCOOP -> {
				int bucket = findSlot(inventory, BuilderGoal::isBucket);
				if (bucket < 0) {
					this.fetch(level, colony, core, plan, Items.BUCKET);
				} else if (this.reach(pos)) {
					this.scoop(level, pos, bucket);
				}
			}
			case FILL -> {
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
			default -> {
				if (this.reach(pos) && this.breaker.tick(level, pos, true)) {
					colony.unprotect(pos);
				}
			}
		}
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
	public static Predicate<BuildPlan.Step> workable(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		// ponytail: looks for a source once per fluid and recheck, from the core rather than per step.
		Map<Item, Boolean> canPour = new HashMap<>();
		return step -> step.isPlaceable() && clearing(level, step, true, true) != BuildPlan.Clearing.BLOCKED
			&& (!isPoured(step) || canPour.computeIfAbsent(step.item(),
				bucket -> core != null && core.countItem(bucket) > 0 || source(level, colony, colony.core(), bucket) != null));
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

	/** Whether a plan still has a block in the way that is dug with a pickaxe, such as stone. */
	private static boolean needsPickaxe(ServerLevel level, Colony colony) {
		return colony.builds().stream().flatMap(plan -> plan.steps().stream()).anyMatch(step -> {
			BlockState state = level.getBlockState(step.pos());
			return state.is(BlockTags.MINEABLE_WITH_PICKAXE) && step.needsClearing(state);
		});
	}

	/**
	 * Whether {@code plan} waits for materials or for a fluid to settle, rather than being left only
	 * with steps it cannot do. A fluid waits for an empty bucket only while there is a source to fill it at.
	 */
	private boolean isWaiting(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan) {
		return plan.nextStep(level::getBlockState, step -> step.needsClearing(level.getBlockState(step.pos()))
			? this.clearing(level, core, step) == BuildPlan.Clearing.SETTLE
			: step.isPlaceable() && (!isPoured(step) || this.source(level, colony, step.item()) != null)).isPresent();
	}

	private static boolean isUnbreakable(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getDestroySpeed(level, pos) < 0;
	}

	/**
	 * Nothing in the plan can be worked on now. With nothing left the plan is finished; with only
	 * blocks or fluids it cannot clear left in the way those steps are reported, and the plan waits
	 * for the colony to find them workable again. While it waits for materials, which the colony
	 * orders, the job is freed for later.
	 */
	private void putAside(ServerLevel level, Colony colony, @Nullable Container core, Job job, Optional<BuildPlan> plan) {
		if (plan.isPresent() && this.isWaiting(level, colony, core, plan.get())) {
			colony.board().release(this.bot.getUUID());
			this.cooldown = IDLE_TICKS;
		} else {
			plan.ifPresent(p -> {
				List<BlockPos> blocked = p.steps().stream().filter(step -> !step.isDone(level.getBlockState(step.pos()))).map(BuildPlan.Step::pos).toList();
				if (blocked.isEmpty()) {
					colony.removeBuild(p);
				} else {
					colony.reportBlocked(job.id(), blocked);
				}
			});
			colony.board().complete(job.id());
		}
		this.hasJob = false;
		this.resetMovement();
	}

	private void place(ServerLevel level, Colony colony, BuildPlan.Step step) {
		this.put(level, step.pos(), step.state(), step.item());
		colony.protect(step.pos(), step.state().getBlock());
	}

	/** Places {@code state} at {@code pos}, using up one {@code item} unless that is air; a fluid's bucket is left empty. */
	private void put(ServerLevel level, BlockPos pos, BlockState state, Item item) {
		state = Block.updateFromNeighbourShapes(state, level, pos);
		level.setBlockAndUpdate(pos, state);
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(this.bot, GameEvent.BLOCK_PLACE, pos);
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		if (state.getBlock() instanceof LiquidBlock) {
			this.bot.getInventory().setItem(findSlot(this.bot.getInventory(), stack -> stack.is(item)), new ItemStack(Items.BUCKET));
		} else if (item != Items.AIR) {
			Storage.take(this.bot.getInventory(), item, 1);
		}
	}

	/**
	 * Takes as many of {@code item} from the core as the plan still needs, leaving there whatever
	 * the bot carries that the plan does not need. The colony survey orders what the core lacks.
	 */
	private void fetch(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, Item item) {
		Map<Item, Integer> needed = plan.materials(level::getBlockState);
		int count = needed.getOrDefault(item, 1);
		if (core == null) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.reach(colony.core())) {
			Storage.moveItems(this.bot.getInventory(), core, stack -> !needed.containsKey(stack.getItem()) && !stack.is(ItemTags.PICKAXES));
			Storage.moveItems(core, this.bot.getInventory(), stack -> stack.is(item), count);
		}
	}
}
