package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import io.github.barniebarnie.antcraft.pathing.LoadedWorldView;
import io.github.barniebarnie.antcraft.pathing.MoveType;
import io.github.barniebarnie.antcraft.pathing.Path;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import io.github.barniebarnie.antcraft.pathing.Step;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Plans paths with {@link PathFinder} and carries them out step by step: breaking
 * blocks in the way, placing scaffold blocks, then moving.
 */
public class PathFollower {
	public static final TagKey<Item> SCAFFOLD_BLOCKS = TagKey.create(Registries.ITEM, AntCraft.id("scaffold_blocks"));
	/**
	 * Scaffold blocks that break quickly by hand (item tag {@code antcraft:lumberjack_scaffold_blocks}), for bots
	 * that take their scaffolding down again without a tool for it. Keep it a subset of {@link #SCAFFOLD_BLOCKS}.
	 */
	public static final TagKey<Item> FAST_SCAFFOLD_BLOCKS = TagKey.create(Registries.ITEM, AntCraft.id("lumberjack_scaffold_blocks"));

	public enum Status { RUNNING, ARRIVED, FAILED }

	private static final int MAX_NODES = 4000;
	/** How far a path may drop the bot, and so how far it may fall unhurt. */
	public static final int MAX_DROP = 3;
	private static final int MAX_REPLANS_WITHOUT_PROGRESS = 5;
	private static final int STEP_BASE_BUDGET = 60;
	/** Extra ticks for a swim step: bots swim slowly, and bob up and down at the surface. */
	private static final int SWIM_BUDGET = 60;
	private static final double ARRIVED_DIST_SQR = 0.35 * 0.35;
	private static final double SPEED = 1.0;
	/** How far away other bots' scaffolding is looked for, to keep paths from digging through it. */
	private static final double KEEP_SCAN = 48;

	private final AntBot bot;
	private @Nullable Goal goal;
	private @Nullable Path path;
	/** The search for the next path, while it waits for nodes (see {@link PathBudget}). */
	private PathFinder.@Nullable Search search;
	/** The world {@link #search} plans in. */
	private @Nullable LevelWorldView searchWorld;
	private int stepIndex;
	private int stepTicks;
	private int stepBudget;
	private int replansWithoutProgress;

	private final BlockBreaker breaker;
	private boolean mayPlace = true;
	private boolean mayBreak = true;
	private int maxNodes = MAX_NODES;
	private TagKey<Item> scaffold = SCAFFOLD_BLOCKS;
	private Predicate<BlockPos> mayPlaceAt = pos -> true;

	public PathFollower(AntBot bot) {
		this.bot = bot;
		this.breaker = new BlockBreaker(bot);
	}

	/** Whether paths may place scaffold blocks; applies from the next path planned. */
	public void setMayPlace(boolean mayPlace) {
		this.mayPlace = mayPlace;
	}

	/** Whether paths may break blocks in the way; applies from the next path planned. */
	public void setMayBreak(boolean mayBreak) {
		this.mayBreak = mayBreak;
	}

	/** How many nodes a search for a path may expand, {@link #MAX_NODES} unless set; applies from the next path planned. */
	public void setMaxNodes(int maxNodes) {
		this.maxNodes = maxNodes;
	}

	/** Which blocks paths may place as scaffolding; {@link #SCAFFOLD_BLOCKS} unless set. */
	public void setScaffold(TagKey<Item> scaffold) {
		this.scaffold = scaffold;
	}

	/** Where paths may place scaffold blocks, anywhere unless set; applies from the next path planned. */
	public void setMayPlaceAt(Predicate<BlockPos> mayPlaceAt) {
		this.mayPlaceAt = mayPlaceAt;
	}

	/** Which blocks paths and {@link #placeBlock} may place as scaffolding. */
	public TagKey<Item> scaffold() {
		return this.scaffold;
	}

	public void start(Goal goal) {
		this.stop();
		this.goal = goal;
		this.replansWithoutProgress = 0;
		this.replan();
	}

	public void stop() {
		this.breaker.reset();
		this.goal = null;
		this.path = null;
		this.search = null;
		this.searchWorld = null;
		this.bot.setShiftKeyDown(false);
	}

	public Status tick() {
		if (this.goal == null || !(this.bot.level() instanceof ServerLevel level)) {
			return Status.FAILED;
		}
		BlockPos feet = this.bot.blockPosition();
		if (this.goal.isReached(feet) && (this.bot.onGround() || this.goal.mayEndAfloat() && this.bot.isInWater())) {
			this.stop();
			return Status.ARRIVED;
		}
		if (this.search != null) {
			if (!this.search(level)) {
				this.stop();
				return Status.FAILED;
			}
			return Status.RUNNING;
		}
		if (this.path != null && this.path.complete() && this.stepIndex >= this.path.steps().size() && this.goal.isReached(feet)
				&& ++this.stepTicks <= this.stepBudget) {
			// At the end of the path but not on its feet yet, such as still sliding down the last block of a ladder: wait to land.
			return Status.RUNNING;
		}
		if (this.path == null || this.stepIndex >= this.path.steps().size() || ++this.stepTicks > this.stepBudget) {
			if (!this.replan()) {
				this.stop();
				return Status.FAILED;
			}
			return Status.RUNNING;
		}

		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), Set.of());
		this.skipRisenPast(feet, world);
		Step step = this.path.steps().get(this.stepIndex);

		for (BlockPos pos : step.breaks()) {
			if (!world.isPassable(pos)) {
				if (this.othersScaffold(level).contains(pos)) {
					this.path = null; // another bot placed scaffolding here since: plan around it
					return Status.RUNNING;
				}
				this.breaker.tick(level, pos);
				return Status.RUNNING;
			}
		}
		if (step.type() != MoveType.PILLAR) {
			for (BlockPos pos : step.places()) {
				if (!world.canStandOn(pos)) {
					if (this.placeBlock(level, pos) == Placed.NO_SCAFFOLD) {
						this.path = null; // out of scaffold blocks: plan again without placing
					} // in someone's way: wait for them to leave, or plan again once the step's time is up
					return Status.RUNNING;
				}
			}
		}

		if (this.bot.isInWater()) {
			this.bot.holdAgainstCurrent();
		}
		this.bot.setShiftKeyDown(step.type() == MoveType.CLIMB_DOWN);
		if (step.type() == MoveType.PILLAR) {
			this.pillar(level, world, step);
		} else {
			// The door or hatch ahead, and the one it stands in: shut, the panel may still be in its way out.
			for (BlockPos pos : List.of(feet, feet.above(), step.to(), step.to().above())) {
				this.open(level, pos);
			}
			if (step.type() == MoveType.CLIMB_UP || step.type() == MoveType.CLIMB_DOWN) {
				this.climb(step);
			} else if (step.type() == MoveType.WALK && this.bot.onClimbable()) {
				// Off a ladder, steered like a climb: turning to walk away, the move control would bump into the wall, and
				// climb up it. Not before it has slid down to the floor it walks on, or its head bumps into the top of the way out.
				this.climb(this.bot.getY() > step.to().getY() + 0.2 ? new Step(MoveType.CLIMB_DOWN, feet, List.of(), List.of()) : step);
			} else {
				this.moveTo(step.to());
			}
			if (step.type() != MoveType.SWIM && this.bot.isInWater()) {
				this.bot.getJumpControl().jump(); // rise and climb out onto the bank
			} else if (step.type() == MoveType.SWIM && step.to().getY() > this.stepFrom().getY()) {
				// Stroke up every tick, also the one it reaches the block: left to FloatGoal, which strokes on four ticks
				// in five, a rise up a water column would take a different time each run (see PathFinder.SWIM_UP_COST).
				this.bot.getJumpControl().jump();
			}
		}

		// On a ladder counts as landed: a bot walking into one climbs as it bumps into the wall.
		boolean landed = this.bot.onGround() || this.bot.onClimbable() || step.type() == MoveType.SWIM && this.bot.isInWater();
		if (this.bot.blockPosition().equals(step.to()) && landed && this.centered(step.to())) {
			this.stepIndex++;
			this.stepTicks = 0;
			this.stepBudget = this.budgetFor(world);
			this.replansWithoutProgress = 0;
		}
		return Status.RUNNING;
	}

	/**
	 * Plans a new path, unless the bot made no progress on the last {@link #MAX_REPLANS_WITHOUT_PROGRESS}. Returns false
	 * when it gives up or finds no way at all; the search may go on over the next ticks (see {@link #search}).
	 */
	private boolean replan() {
		if (this.replansWithoutProgress++ >= MAX_REPLANS_WITHOUT_PROGRESS) {
			return false;
		}
		this.breaker.reset();
		ServerLevel level = (ServerLevel) this.bot.level();
		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), this.othersScaffold(level)).placingOnly(this.mayPlaceAt);
		PathFinder.Options options = this.options();
		this.path = null;
		this.search = PathFinder.search(world, this.bot.blockPosition(), this.goal, options);
		this.searchWorld = world;
		return this.search(level);
	}

	private PathFinder.Options options() {
		return new PathFinder.Options(this.mayBreak, this.mayPlace && scaffoldCount(this.bot.getInventory(), this.scaffold) > 0, this.maxNodes, MAX_DROP);
	}

	/**
	 * A search for the path {@link #start} would plan to {@code goal} from where the bot stands, but over loaded blocks
	 * only (see {@link LoadedWorldView}), to run without following it, such as for a script's {@code reachable}.
	 */
	public PathFinder.Search plan(Goal goal) {
		ServerLevel level = (ServerLevel) this.bot.level();
		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), this.othersScaffold(level)).placingOnly(this.mayPlaceAt);
		LoadedBlocks loaded = new LoadedBlocks(level);
		return PathFinder.search(new LoadedWorldView(world, pos -> loaded.loaded(pos) != null), this.bot.blockPosition(), goal, this.options());
	}

	/**
	 * Runs the search begun for as many nodes as this tick has left for paths (see {@link PathBudget}): with many bots
	 * planning at once, the bot waits where it stands for the next tick's. Once found, follows the path. Returns false
	 * when the search found no way at all.
	 */
	private boolean search(ServerLevel level) {
		PathFinder.Search search = this.search;
		LevelWorldView world = this.searchWorld;
		long tick = level.getServer().getTickCount();
		int nodes = PathBudget.SERVER.available(tick);
		if (search == null || world == null || nodes <= 0) {
			return true;
		}
		PathBudget.SERVER.spend(tick, TickProfiler.time("path.find", () -> search.run(nodes)));
		Optional<Path> found = search.path();
		if (found.isEmpty()) {
			return true;
		}
		this.search = null;
		this.path = found.get();
		this.stepIndex = 0;
		this.stepTicks = 0;
		this.stepBudget = this.budgetFor(world);
		return !this.path.steps().isEmpty() || this.goal == null || this.goal.isReached(this.bot.blockPosition());
	}

	/**
	 * Scaffolding nearby bots placed and still need or will take down themselves, which this bot must not break.
	 * ponytail: only bots within {@link #KEEP_SCAN} blocks count; widen it if paths get much longer.
	 */
	private Set<BlockPos> othersScaffold(ServerLevel level) {
		Set<BlockPos> keep = new HashSet<>();
		for (AntBot other : level.getEntitiesOfClass(AntBot.class, this.bot.getBoundingBox().inflate(KEEP_SCAN), other -> other != this.bot)) {
			keep.addAll(other.getProtectedScaffold());
		}
		return keep;
	}

	private int budgetFor(LevelWorldView world) {
		if (this.path == null || this.stepIndex >= this.path.steps().size()) {
			return STEP_BASE_BUDGET;
		}
		Step step = this.path.steps().get(this.stepIndex);
		int budget = step.type() == MoveType.SWIM ? STEP_BASE_BUDGET + SWIM_BUDGET : STEP_BASE_BUDGET;
		for (BlockPos pos : step.breaks()) {
			budget += Math.max(0, (int) world.breakTicks(pos)) + 10;
		}
		return budget;
	}

	/**
	 * Opens a shut wooden door, fence gate or trapdoor the bot is walking or climbing through, as a player would, and
	 * keeps one it opened from being shut behind it until it has passed.
	 */
	private void open(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!LevelWorldView.isOpenable(state)) {
			return;
		}
		if (!state.getValue(BlockStateProperties.OPEN)) {
			setOpen(this.bot, level, pos, state, true);
		} else if (!this.bot.getOpened().containsKey(pos)) {
			return; // left open by someone else: theirs to shut
		}
		this.bot.getOpened().put(pos.immutable(), level.getGameTime());
	}

	/**
	 * Shuts the doors and gates the bot opened once no path step walked through them last tick and, shut, they would
	 * not hit it. Forgets one that is gone or someone else shut. The bot runs this every tick before its goals, so a
	 * door it stopped in shuts once it leaves, whatever takes it away, and one it is on its way through stays open.
	 */
	public static void closeBehind(AntBot bot, ServerLevel level) {
		bot.getOpened().entrySet().removeIf(entry -> {
			BlockPos pos = entry.getKey();
			BlockState state = level.getBlockState(pos);
			if (!state.hasProperty(BlockStateProperties.OPEN) || !state.getValue(BlockStateProperties.OPEN)) {
				return true;
			}
			if (entry.getValue() >= level.getGameTime() - 1) {
				return false;
			}
			VoxelShape shut = state.setValue(BlockStateProperties.OPEN, false).getCollisionShape(level, pos).move(pos);
			if (Shapes.joinIsNotEmpty(shut, Shapes.create(bot.getBoundingBox()), BooleanOp.AND)) {
				return false;
			}
			setOpen(bot, level, pos, state, false);
			return true;
		});
	}

	private static void setOpen(AntBot bot, ServerLevel level, BlockPos pos, BlockState state, boolean open) {
		if (state.getBlock() instanceof DoorBlock door) {
			door.setOpen(bot, level, state, pos, open);
			return;
		}
		level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), Block.UPDATE_CLIENTS);
		SoundEvent sound = state.getBlock() instanceof FenceGateBlock
			? open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE
			: open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE;
		level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(bot, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
	}

	private void moveTo(BlockPos to) {
		this.bot.getMoveControl().setWantedPosition(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, SPEED);
	}

	/**
	 * Climbs up or down a ladder: steers to the middle of it without the move control, which overshoots, as a bot on a
	 * ladder that bumps into a wall climbs up it. Jumps to climb up; slides down by itself, sneaking so scaffolding
	 * lets it through.
	 */
	private void climb(Step step) {
		BlockPos to = step.to();
		Vec3 v = this.bot.getDeltaMovement();
		double dx = to.getX() + 0.5 - this.bot.getX(), dz = to.getZ() + 0.5 - this.bot.getZ();
		this.bot.setDeltaMovement(Mth.clamp(dx * 0.5, -0.15, 0.15), v.y, Mth.clamp(dz * 0.5, -0.15, 0.15));
		if (step.type() == MoveType.CLIMB_UP) {
			this.bot.getJumpControl().jump(); // a mob on a ladder climbs while it jumps
		}
	}

	/**
	 * Skips the swim steps up a water column that the bot has already risen past. Afloat, it bobs above the block it
	 * swims into at the foot of the column, so it never stands in that block to finish the step; it must not wait out
	 * the step's budget while it drifts up.
	 */
	private void skipRisenPast(BlockPos feet, LevelWorldView world) {
		List<Step> steps = this.path.steps();
		int index = this.stepIndex;
		while (index + 1 < steps.size()) {
			BlockPos to = steps.get(index).to();
			Step next = steps.get(index + 1);
			if (steps.get(index).type() != MoveType.SWIM || next.type() != MoveType.SWIM || !next.to().equals(to.above())
					|| feet.getX() != to.getX() || feet.getZ() != to.getZ() || feet.getY() <= to.getY()) {
				break;
			}
			index++;
		}
		if (index != this.stepIndex) {
			this.stepIndex = index;
			this.stepTicks = 0;
			this.stepBudget = this.budgetFor(world);
			this.replansWithoutProgress = 0;
		}
	}

	/** Where the current step starts: the end of the step before it, or the start of the path. */
	private BlockPos stepFrom() {
		return this.stepIndex == 0 ? this.path.start() : this.path.steps().get(this.stepIndex - 1).to();
	}

	private boolean centered(BlockPos pos) {
		double dx = this.bot.getX() - (pos.getX() + 0.5);
		double dz = this.bot.getZ() - (pos.getZ() + 0.5);
		return dx * dx + dz * dz <= ARRIVED_DIST_SQR;
	}

	private void pillar(ServerLevel level, LevelWorldView world, Step step) {
		BlockPos below = step.places().getFirst();
		this.moveTo(below);
		if (world.canStandOn(below)) {
			return; // placed; wait to land on it
		}
		if (this.bot.onGround() || this.bot.isInWater()) {
			this.bot.getJumpControl().jump(); // afloat, it swims up until high enough to place in the water under it
		}
		if (this.bot.getY() >= below.getY() + 1.0) {
			if (this.placeBlock(level, below) == Placed.NO_SCAFFOLD) {
				this.path = null;
			}
		}
	}

	/** What {@link #placeBlock} did. */
	public enum Placed {
		PLACED,
		/** The bot carries no scaffold block. */
		NO_SCAFFOLD,
		/** An entity, such as another bot, is in the way: placed there, the block would shut it in and suffocate it. */
		OBSTRUCTED
	}

	/**
	 * Places one of the bot's scaffold blocks at {@code pos}, recorded as its scaffold, unless it carries none or an
	 * entity that blocks building, such as another bot, overlaps the block, as for a player.
	 */
	public Placed placeBlock(ServerLevel level, BlockPos pos) {
		SimpleContainer inventory = this.bot.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(this.scaffold) && stack.getItem() instanceof BlockItem blockItem) {
				BlockState state = blockItem.getBlock().defaultBlockState();
				if (!level.isUnobstructed(state, pos, CollisionContext.empty())) {
					return Placed.OBSTRUCTED;
				}
				Vec3 center = Vec3.atCenterOf(pos);
				this.bot.getLookControl().setLookAt(center.x, center.y, center.z);
				level.setBlockAndUpdate(pos, state);
				level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
				level.gameEvent(this.bot, GameEvent.BLOCK_PLACE, pos);
				this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
				stack.shrink(1);
				// Placed only to get somewhere: other bots leave it alone while this one is near.
				this.bot.addPathScaffold(pos);
				return Placed.PLACED;
			}
		}
		return Placed.NO_SCAFFOLD;
	}

	public static int scaffoldCount(Container inventory) {
		return scaffoldCount(inventory, SCAFFOLD_BLOCKS);
	}

	/** How many blocks in {@code scaffold} the container holds. */
	public static int scaffoldCount(Container inventory, TagKey<Item> scaffold) {
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(scaffold)) {
				count += stack.getCount();
			}
		}
		return count;
	}
}
