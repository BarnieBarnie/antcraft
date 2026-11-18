package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotFood;
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
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
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
	/** The way in of each pen door or gate opened by hand ({@link #usedOpen}), by its place. */
	private static final Map<GlobalPos, Direction> INWARD = new ConcurrentHashMap<>();
	/** A fill round more blocks than this is not a pen ({@link #usedOpen}). */
	private static final int PEN_CELLS = 2048;
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
	private static final double ARRIVED_DIST = 0.35;
	/** How near the middle of the goal's block a walk ends beside a hazard that hurts (#873). */
	private static final double CENTRED_DIST = 0.2;
	/** How long a walk that reached its goal beside a hazard tries for the middle before it ends anyway. */
	private static final int CENTRING_TICKS = 20;
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
	/** Ticks spent stepping to the middle of the goal's block after reaching it (see {@link #CENTRED_DIST}). */
	private int centring;

	private final BlockBreaker breaker;
	private boolean mayPlace = true;
	private boolean mayBreak = true;
	private int maxNodes = MAX_NODES;
	private TagKey<Item> scaffold = SCAFFOLD_BLOCKS;
	private Predicate<BlockPos> mayPlaceAt = pos -> true;
	private Predicate<BlockPos> keep = pos -> false;
	private Predicate<ItemStack> keepItems = stack -> false;

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

	/** Blocks paths never break, besides others' scaffold, none unless set; applies from the next path planned. */
	public void setKeep(Predicate<BlockPos> keep) {
		this.keep = keep;
	}

	/** Scaffold blocks paths may not place, such as what a script's {@code goto(keep=)} keeps; none unless set. */
	public void setKeepItems(Predicate<ItemStack> keepItems) {
		this.keepItems = keepItems;
	}

	/** Which blocks paths and {@link #placeBlock} may place as scaffolding. */
	public TagKey<Item> scaffold() {
		return this.scaffold;
	}

	public void start(Goal goal) {
		this.stop();
		this.goal = goal;
		this.replansWithoutProgress = 0;
		this.centring = 0;
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
		BlockPos feet = feet(this.bot);
		if (this.goal.isReached(feet) && (this.bot.onGround() || this.goal.mayEndAfloat() && this.bot.isInWater())) {
			if (!this.within(feet, CENTRED_DIST) && this.centring++ < CENTRING_TICKS
					&& hazardBeside(new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), Set.of()), feet)) {
				// Entered the goal's block at its edge, its body over the side toward a hazard beside, such as a sweet berry
				// bush (#873, #854): on to the middle first. Only there, as it makes the walk a few ticks longer.
				this.moveTo(feet);
				return Status.RUNNING;
			}
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
		BlockPos start = this.stepFrom();
		boolean sideways = step.type() == MoveType.WALK || step.type() == MoveType.STEP_UP || step.type() == MoveType.DROP;
		if (sideways && feet.getY() == start.getY() && feet.distManhattan(start) == 1
				&& (feet.getX() != step.to().getX() || feet.getZ() != step.to().getZ()) && this.bot.onGround() && !this.bot.onClimbable()) {
			// Slid on along the ground into the block beside where a walk, step up or drop starts, such as from a walk that
			// ended mid-step: heading straight from there for the step's block, it cut across the corner between them, into a
			// sweet berry bush there (#848, #860). Plan again from where it is. On the way a step's feet are only ever in the
			// column it starts at (a step up rises in it) or the one it heads for (a drop falls in it), so only a block
			// beside the start that is in neither counts as slid off.
			if (!this.replan()) {
				this.stop();
				return Status.FAILED;
			}
			return Status.RUNNING;
		}

		for (BlockPos pos : step.breaks()) {
			if (!world.isPassable(pos)) {
				if (this.othersScaffold(level).contains(pos)) {
					this.path = null; // another bot placed scaffolding here since: plan around it
					return Status.RUNNING;
				}
				this.holdOn();
				this.breaker.tick(level, pos, true); // into the inventory, as a build needs what its walk broke back at once
				return Status.RUNNING;
			}
		}
		if (step.type() != MoveType.PILLAR) {
			for (BlockPos pos : step.places()) {
				if (!world.canStandOn(pos)) {
					this.holdOn();
					Placed placed = this.placeBlock(level, pos);
					if (placed == Placed.NO_SCAFFOLD) {
						this.path = null; // out of scaffold blocks: plan again without placing
					} else if (placed == Placed.OBSTRUCTED && this.bot.getBoundingBox().intersects(new AABB(pos))) {
						this.moveTo(feet); // its own body over the edge into the block: back to the middle of its own (#656)
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
			} else if ((step.type() == MoveType.WALK || step.type() == MoveType.DROP) && this.bot.onClimbable()) {
				// Off a ladder, steered like a climb: turning to walk away, the move control would bump into the wall, and
				// climb up it. Not before it has slid down to the floor it walks on, or its head bumps into the top of the way out
				// and it climbs that: off vines, it rose, slid back and tried again until goto timed out (#776). Not off a low
				// floor with no top to bump, though: on vines over a carpet, nearly a block under the one it walks onto, it
				// jumped up past the level it walks off from and slid down to the carpet again, over and over (#784).
				BlockPos from = this.stepFrom();
				boolean roofed = !world.isPassable(new BlockPos(step.to().getX(), from.getY() + 2, step.to().getZ()));
				boolean slide = this.bot.getY() > from.getY() + 0.2 && (roofed || world.floorTop(from.below()) >= 1);
				this.climb(slide ? new Step(MoveType.CLIMB_DOWN, feet, List.of(), List.of()) : step);
			} else if (feet.equals(this.stepFrom()) && !this.inRow(feet, step.to()) && hazardBeside(world, feet)) {
				// Off the middle of its block across the way it heads, its body over the edge, it would cut into a hazard beside
				// heading straight for the next block, such as a sweet berry bush at the inside of a turn: to the middle first (#845).
				this.moveTo(feet);
			} else {
				if (sideways && feet.equals(this.stepFrom()) && hazardBeside(world, feet)) {
					// Its way on from the step before carries it on across the turn, such as into a bush at the outside of a sharp
					// turn: it came within 0.08 of one (#860). Stop that way at the turn.
					Vec3 v = this.bot.getDeltaMovement();
					boolean alongX = step.to().getX() != feet.getX();
					this.bot.setDeltaMovement(alongX ? v.x : 0, v.y, alongX ? 0 : v.z);
				}
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
		if (feet(this.bot).equals(step.to()) && landed && this.centered(step.to())) {
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
		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), this.othersScaffold(level)).placingOnly(this.mayPlaceAt).keeping(this.keep).scaffold(this::isScaffold).jumpBoost(this.bot.getJumpBoostPower());
		PathFinder.Options options = this.options();
		this.path = null;
		this.search = PathFinder.search(world, feet(this.bot), this.goal, options);
		this.searchWorld = world;
		return this.search(level);
	}

	private PathFinder.Options options() {
		int scaffold = this.scaffoldCarried();
		// With none, it may still place what it digs, as broken blocks that drop one count back (#778).
		return new PathFinder.Options(this.mayBreak, this.mayPlace && (scaffold > 0 || this.mayBreak), this.maxNodes, MAX_DROP, this.death(), scaffold);
	}

	/**
	 * What a death costs the bot, so a path may drop farther than {@link #MAX_DROP} at that cost (#682); none where it
	 * would not come back. A home in another dimension is a death with no home here (#688). A bot heals what its food
	 * heals (#708), unless botHunger or the naturalHealthRegeneration game rule is off, and on peaceful
	 * regenerates (#738).
	 */
	private PathFinder.@Nullable Death death() {
		BlockPos home = this.bot.getHome();
		if (!AntCraftConfig.botRespawn() || home == null) {
			return null;
		}
		boolean here = this.bot.getHomeDimension() == this.bot.level().dimension();
		boolean heals = AntCraftConfig.botHunger() && ((ServerLevel) this.bot.level()).getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
		BotFood food = this.bot.getFood();
		return new PathFinder.Death(this.bot.getHealth(), here ? home : null, AntCraftConfig.botRespawnTicks(),
			heals ? food.quickHeal() : 0, heals ? food.slowHeal() : 0, heals && this.bot.level().getDifficulty() == Difficulty.PEACEFUL);
	}

	/**
	 * A search for the path {@link #start} would plan to {@code goal} from where the bot stands, but over loaded blocks
	 * only (see {@link LoadedWorldView}), to run without following it, such as for a script's {@code reachable}.
	 */
	public PathFinder.Search plan(Goal goal) {
		ServerLevel level = (ServerLevel) this.bot.level();
		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), this.othersScaffold(level)).placingOnly(this.mayPlaceAt).keeping(this.keep).scaffold(this::isScaffold).jumpBoost(this.bot.getJumpBoostPower());
		LoadedBlocks loaded = new LoadedBlocks(level);
		return PathFinder.search(new LoadedWorldView(world, pos -> loaded.loaded(pos) != null), feet(this.bot), goal, this.options());
	}

	/**
	 * Runs the search begun for the bot's share of the nodes and time this tick has left for paths (see {@link PathBudget#run}):
	 * with many bots planning at once, the bot waits where it stands for the next tick's. Once found, follows the path.
	 * Returns false when the search found no way at all.
	 */
	private boolean search(ServerLevel level) {
		PathFinder.Search search = this.search;
		LevelWorldView world = this.searchWorld;
		long tick = level.getServer().getTickCount();
		if (search == null || world == null) {
			return true;
		}
		TickProfiler.time("path.find", () -> PathBudget.SERVER.run(tick, this.bot, search));
		Optional<Path> found = search.path();
		if (found.isEmpty()) {
			PathBudget.SERVER.pause(tick, this.bot);
			return true;
		}
		this.search = null;
		this.path = found.get();
		this.stepIndex = 0;
		this.stepTicks = 0;
		this.stepBudget = this.budgetFor(world);
		BlockPos feet = feet(this.bot);
		if (this.path.steps().isEmpty() && !this.path.start().equals(feet)) {
			// Planned from where it no longer is, such as from the air beside a block before landing on it, the search
			// ending ticks later for want of nodes (#680): no way from there says nothing of here.
			return this.replan();
		}
		return !this.path.steps().isEmpty() || this.goal == null || this.goal.isReached(feet);
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

	/**
	 * Whether a bot opened the door or gate at pos to walk through and has not shut it yet (see {@link #closeBehind});
	 * such a gate or door stays shut to animals (FenceGateBlockMixin, BlockStateBaseMixin, #863, #878). A bot that opened one is beside it, as it shuts it
	 * once it has passed.
	 */
	public static boolean openedByBot(Level level, BlockPos pos) {
		return level instanceof ServerLevel && !level.getEntitiesOfClass(AntBot.class, new AABB(pos).inflate(2),
			bot -> bot.getOpened().containsKey(pos)).isEmpty();
	}

	/**
	 * The shape an open gate or door at pos has for an animal it is shut to (see {@link #openedByBot}): its shut panel,
	 * reaching on to the far side of its block, away from the animal, as high. A chicken held only by the thin panel
	 * stood in it once the gate shut, and so slipped through it, as an entity moves freely out of a shape it is in. Its
	 * near face is the shut panel's, so the gate or door shuts clear of any animal against it.
	 */
	public static VoxelShape shutTo(Entity animal, BlockState state, BlockGetter getter, BlockPos pos) {
		VoxelShape shut = state.setValue(BlockStateProperties.OPEN, false).getCollisionShape(getter, pos);
		double top = shut.max(Direction.Axis.Y);
		if (state.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() == Direction.Axis.Z) {
			return animal.getZ() > pos.getZ() + 0.5
				? Shapes.box(0, 0, 0, 1, top, shut.max(Direction.Axis.Z))
				: Shapes.box(0, 0, shut.min(Direction.Axis.Z), 1, top, 1);
		}
		return animal.getX() > pos.getX() + 0.5
			? Shapes.box(0, 0, 0, shut.max(Direction.Axis.X), top, 1)
			: Shapes.box(shut.min(Direction.Axis.X), 0, 0, 1, top, 1);
	}

	/**
	 * Notes which side of the door or gate at pos, just opened or shut by hand (a player's or a bot's {@code use};
	 * FenceGateBlockMixin, DoorBlockMixin, #905), is a pen's inside: the side where a fill along the ground at its height,
	 * round blocks with collision, reaches fewer blocks, up to {@link #PEN_CELLS} (where both reach that many, neither).
	 * Shut, it forgets the side. An animal following a bot that holds its food goes through it only inward
	 * ({@link #inward}; FenceGateBlockMixin, BlockStateBaseMixin, #884): a lured animal comes in, a penned one does not
	 * follow the food out, whichever side it was opened from.
	 */
	public static void usedOpen(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof DoorBlock)) {
			return;
		}
		BlockPos lower = state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
		GlobalPos key = GlobalPos.of(level.dimension(), lower);
		if (!state.getValue(BlockStateProperties.OPEN)) {
			INWARD.remove(key);
			return;
		}
		Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		int front = fill(level, lower.relative(facing), lower);
		int back = fill(level, lower.relative(facing.getOpposite()), lower);
		if (front != back) {
			INWARD.put(key, front < back ? facing : facing.getOpposite());
		} else {
			INWARD.remove(key);
		}
	}

	/**
	 * The way into the pen through the door or gate at pos (the lower half of a door), as {@link #usedOpen} found it when
	 * it was last opened by hand; null where it found no pen.
	 */
	// ponytail: kept until the next use by hand of that gate, so one opened by redstone, or in a pen torn down since,
	// keeps the side its last use found; hook setBlock/onRemove if that ever matters.
	public static @Nullable Direction inward(Level level, BlockPos pos) {
		return level instanceof ServerLevel ? INWARD.get(GlobalPos.of(level.dimension(), pos)) : null;
	}

	/** Whether the animal is on the pen's side of the middle of the door or gate at pos, whose way in is inward. */
	public static boolean inside(Entity animal, BlockPos pos, Direction inward) {
		return (animal.getX() - pos.getX() - 0.5) * inward.getStepX() + (animal.getZ() - pos.getZ() - 0.5) * inward.getStepZ() > 0;
	}

	/** How many blocks a fill from start reaches, not through gate, up to {@link #PEN_CELLS}; that many at an unloaded one. */
	private static int fill(ServerLevel level, BlockPos start, BlockPos gate) {
		Set<BlockPos> seen = new HashSet<>(List.of(gate, start));
		ArrayDeque<BlockPos> next = new ArrayDeque<>(List.of(start));
		while (!next.isEmpty()) {
			BlockPos at = next.poll();
			if (seen.size() > PEN_CELLS || !level.isLoaded(at)) {
				return PEN_CELLS;
			}
			if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos beside = at.relative(d);
					if (seen.add(beside)) {
						next.add(beside);
					}
				}
			}
		}
		return seen.size();
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
		// Aimed at the top of the floor under it: the move control jumps for a wanted height more than a step above the
		// bot, so on snow a quarter of a block high it would jump at every block (#642).
		BlockPos below = to.below();
		VoxelShape floor = this.bot.level().getBlockState(below).getCollisionShape(this.bot.level(), below);
		double y = floor.isEmpty() || floor.max(Direction.Axis.Y) > 1 ? to.getY() : below.getY() + floor.max(Direction.Axis.Y);
		if (y > this.bot.getY() + this.bot.maxUpStep()) {
			// The move control jumps at once, keeping in the air the way it faces, but turns at most 90 degrees a tick: still
			// turning, such as just after it stepped back to place the block, it would jump sideways off its target (#670).
			this.bot.setYRot((float) (Mth.atan2(to.getZ() + 0.5 - this.bot.getZ(), to.getX() + 0.5 - this.bot.getX()) * Mth.RAD_TO_DEG) - 90.0F);
		}
		this.bot.getMoveControl().setWantedPosition(to.getX() + 0.5, y, to.getZ() + 0.5, SPEED);
	}

	/**
	 * Keeps the bot from sliding down the ladder or vines it hangs on while it breaks or places a block for its next step,
	 * as a player sneaking holds on: a mob cannot, so it let go and slid off the vines while it dug (#736).
	 */
	private void holdOn() {
		if (this.bot.onClimbable()) {
			Vec3 v = this.bot.getDeltaMovement();
			this.bot.setDeltaMovement(v.x, Math.max(0, v.y), v.z);
		}
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

	/**
	 * The block the bot's feet are in, as paths count it: the one over the block it stands on. On a block a little lower
	 * than a full one, such as farmland, snow or a bottom slab, its block position is that block itself, which a path
	 * never steps to (#634, #642); so too in the air over one, just spawned or mid-jump (#659), but not while climbing
	 * inside scaffolding, whose shape out of context is a floor. On the ground over the edge of a block with a drop that
	 * hurts under the block it is in, the one over the block it rests on: landed so after overshooting a drop, a bot planned from
	 * the air beside it and moved back to the middle of that, and fell (#673).
	 */
	public static BlockPos feet(AntBot bot) {
		BlockPos at = bot.blockPosition();
		BlockPos feet;
		if (bot.getY() - at.getY() <= 1e-3) {
			feet = at;
		} else {
			feet = bot.onGround() || !bot.onClimbable() && LevelWorldView.isLowFloor(bot.level().getBlockState(at).getCollisionShape(bot.level(), at))
				? at.above() : at;
		}
		return bot.onGround() ? restingOver(bot, feet) : feet;
	}

	/**
	 * {@code feet}, or if it would drop more than {@link #MAX_DROP} from there and not into water, the block over the one
	 * under the bot's box nearest its middle it rests on. A drop a path takes, such as down a step, leaves it as it is.
	 */
	private static BlockPos restingOver(AntBot bot, BlockPos feet) {
		Level level = bot.level();
		for (int drop = 1; drop <= MAX_DROP + 1; drop++) {
			BlockPos below = feet.below(drop);
			BlockState state = level.getBlockState(below);
			if (!state.getCollisionShape(level, below).isEmpty() || !state.getFluidState().isEmpty()) {
				return feet;
			}
		}
		AABB box = bot.getBoundingBox();
		AABB under = new AABB(box.minX, box.minY - 0.05, box.minZ, box.maxX, box.minY, box.maxZ);
		BlockPos best = feet;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos floor : BlockPos.betweenClosed(BlockPos.containing(box.minX, feet.getY() - 1, box.minZ),
				BlockPos.containing(box.maxX, feet.getY() - 1, box.maxZ))) {
			VoxelShape shape = level.getBlockState(floor).getCollisionShape(level, floor);
			double dist = Vec3.atBottomCenterOf(floor).distanceToSqr(bot.getX(), floor.getY(), bot.getZ());
			if (!shape.isEmpty() && shape.move(floor).bounds().intersects(under) && dist < bestDist) {
				best = floor.above();
				bestDist = dist;
			}
		}
		return best;
	}

	/** Where the current step starts: the end of the step before it, or the start of the path. */
	private BlockPos stepFrom() {
		return this.stepIndex == 0 ? this.path.start() : this.path.steps().get(this.stepIndex - 1).to();
	}

	/**
	 * Whether the bot's body, heading from {@code from} to the middle of {@code to}, stays in their row: across the way it
	 * heads, not over the edge of {@code from}.
	 */
	private boolean inRow(BlockPos from, BlockPos to) {
		double most = 0.5 - this.bot.getBbWidth() / 2 - 1.0E-3; // inside, as the game counts a body touching a block in it
		return (to.getX() == from.getX() || Math.abs(this.bot.getZ() - (from.getZ() + 0.5)) <= most)
			&& (to.getZ() == from.getZ() || Math.abs(this.bot.getX() - (from.getX() + 0.5)) <= most);
	}

	/** Whether a hazard that hurts, not water, is in one of the eight blocks round {@code feet}, at its feet or head. */
	private static boolean hazardBeside(LevelWorldView world, BlockPos feet) {
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-1, 0, -1), feet.offset(1, 1, 1))) {
			if ((pos.getX() != feet.getX() || pos.getZ() != feet.getZ()) && world.isHazard(pos) && !world.isWater(pos)) {
				return true;
			}
		}
		return false;
	}

	private boolean centered(BlockPos pos) {
		return this.within(pos, ARRIVED_DIST);
	}

	/** Whether the bot is within {@code dist} of the middle of {@code pos}, across. */
	private boolean within(BlockPos pos, double dist) {
		double dx = this.bot.getX() - (pos.getX() + 0.5);
		double dz = this.bot.getZ() - (pos.getZ() + 0.5);
		return dx * dx + dz * dz <= dist * dist;
	}

	private void pillar(ServerLevel level, LevelWorldView world, Step step) {
		BlockPos below = step.places().getFirst();
		this.moveTo(below);
		if (world.canStandOn(below)) {
			return; // placed; wait to land on it
		}
		// Afloat, it swims up until high enough to place in the water under it; in a vine grown since the path was planned,
		// a jump only climbs, so it climbs on until it is (#736).
		if (this.bot.onGround() || this.bot.isInWater() || this.bot.onClimbable()) {
			this.bot.getJumpControl().jump();
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
	 * entity that blocks building, such as another bot, overlaps the block, as for a player. A block other than air or a
	 * fluid there, such as a torch, is broken with its drops first.
	 */
	public Placed placeBlock(ServerLevel level, BlockPos pos) {
		SimpleContainer inventory = this.bot.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (this.isScaffold(stack) && stack.getItem() instanceof BlockItem blockItem) {
				BlockState state = blockItem.getBlock().defaultBlockState();
				if (!level.isUnobstructed(state, pos, CollisionContext.empty())) {
					return Placed.OBSTRUCTED;
				}
				Vec3 center = Vec3.atCenterOf(pos);
				this.bot.getLookControl().setLookAt(center.x, center.y, center.z);
				BlockState old = level.getBlockState(pos);
				if (!old.isAir() && !(old.getBlock() instanceof LiquidBlock)) {
					level.destroyBlock(pos, true, this.bot); // a torch, sapling or grass in the way drops, not vanishes (#608)
				}
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

	private boolean isScaffold(ItemStack stack) {
		return stack.is(this.scaffold) && !this.keepItems.test(stack);
	}

	/** The scaffold blocks the bot carries, the most a path places (#749). */
	private int scaffoldCarried() {
		SimpleContainer inventory = this.bot.getInventory();
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (this.isScaffold(stack)) {
				count += stack.getCount();
			}
		}
		return count;
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
