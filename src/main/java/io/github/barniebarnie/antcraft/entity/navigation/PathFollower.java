package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.MoveType;
import io.github.barniebarnie.antcraft.pathing.Path;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import io.github.barniebarnie.antcraft.pathing.Step;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
	private int stepIndex;
	private int stepTicks;
	private int stepBudget;
	private int replansWithoutProgress;

	private final BlockBreaker breaker;
	private boolean mayPlace = true;
	private TagKey<Item> scaffold = SCAFFOLD_BLOCKS;

	public PathFollower(AntBot bot) {
		this.bot = bot;
		this.breaker = new BlockBreaker(bot);
	}

	/** Whether paths may place scaffold blocks; applies from the next path planned. */
	public void setMayPlace(boolean mayPlace) {
		this.mayPlace = mayPlace;
	}

	/** Which blocks paths may place as scaffolding; {@link #SCAFFOLD_BLOCKS} unless set. */
	public void setScaffold(TagKey<Item> scaffold) {
		this.scaffold = scaffold;
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
		if (this.path == null || this.stepIndex >= this.path.steps().size() || ++this.stepTicks > this.stepBudget) {
			if (!this.replan()) {
				this.stop();
				return Status.FAILED;
			}
			return Status.RUNNING;
		}

		Step step = this.path.steps().get(this.stepIndex);
		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), Set.of()).from(this.bot.blockPosition());

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
					if (!this.placeBlock(level, pos)) {
						this.path = null; // out of scaffold blocks: plan again without placing
					}
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
			} else {
				this.moveTo(step.to());
			}
			if (step.type() != MoveType.SWIM && this.bot.isInWater()) {
				this.bot.getJumpControl().jump(); // rise and climb out onto the bank
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

	private boolean replan() {
		if (this.replansWithoutProgress++ >= MAX_REPLANS_WITHOUT_PROGRESS) {
			return false;
		}
		this.breaker.reset();
		ServerLevel level = (ServerLevel) this.bot.level();
		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem(), this.othersScaffold(level))
			.from(this.bot.blockPosition());
		PathFinder.Options options = new PathFinder.Options(true, this.mayPlace && scaffoldCount(this.bot.getInventory(), this.scaffold) > 0, MAX_NODES, MAX_DROP);
		this.path = PathFinder.find(world, this.bot.blockPosition(), this.goal, options);
		this.stepIndex = 0;
		this.stepTicks = 0;
		this.stepBudget = this.budgetFor(world);
		return !this.path.steps().isEmpty() || this.goal.isReached(this.bot.blockPosition());
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
		if (this.bot.onGround()) {
			this.bot.getJumpControl().jump();
		} else if (this.bot.getY() >= below.getY() + 1.0) {
			if (!this.placeBlock(level, below)) {
				this.path = null;
			}
		}
	}

	/** Places one of the bot's scaffold blocks at {@code pos}, recorded as its scaffold. Returns false when it carries none. */
	public boolean placeBlock(ServerLevel level, BlockPos pos) {
		SimpleContainer inventory = this.bot.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(this.scaffold) && stack.getItem() instanceof BlockItem blockItem) {
				BlockState state = blockItem.getBlock().defaultBlockState();
				Vec3 center = Vec3.atCenterOf(pos);
				this.bot.getLookControl().setLookAt(center.x, center.y, center.z);
				level.setBlockAndUpdate(pos, state);
				level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
				level.gameEvent(this.bot, GameEvent.BLOCK_PLACE, pos);
				this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
				stack.shrink(1);
				// Placed only to get somewhere, whatever the job: the bot takes it down again once done (see BuilderGoal).
				this.bot.addPathScaffold(pos);
				this.bot.addScaffold(pos);
				return true;
			}
		}
		return false;
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
