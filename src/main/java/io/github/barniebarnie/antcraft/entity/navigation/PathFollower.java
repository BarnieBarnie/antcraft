package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.MoveType;
import io.github.barniebarnie.antcraft.pathing.Path;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import io.github.barniebarnie.antcraft.pathing.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Plans paths with {@link PathFinder} and carries them out step by step: breaking
 * blocks in the way, placing scaffold blocks, then moving.
 */
public class PathFollower {
	public static final TagKey<Item> SCAFFOLD_BLOCKS = TagKey.create(Registries.ITEM, AntCraft.id("scaffold_blocks"));

	public enum Status { RUNNING, ARRIVED, FAILED }

	private static final int MAX_NODES = 4000;
	private static final int MAX_DROP = 3;
	private static final int MAX_REPLANS_WITHOUT_PROGRESS = 5;
	private static final int STEP_BASE_BUDGET = 60;
	private static final double ARRIVED_DIST_SQR = 0.35 * 0.35;
	private static final double SPEED = 1.0;

	private final AntBot bot;
	private @Nullable Goal goal;
	private @Nullable Path path;
	private int stepIndex;
	private int stepTicks;
	private int stepBudget;
	private int replansWithoutProgress;

	private final BlockBreaker breaker;

	public PathFollower(AntBot bot) {
		this.bot = bot;
		this.breaker = new BlockBreaker(bot);
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
	}

	public Status tick() {
		if (this.goal == null || !(this.bot.level() instanceof ServerLevel level)) {
			return Status.FAILED;
		}
		BlockPos feet = this.bot.blockPosition();
		if (this.goal.isReached(feet) && this.bot.onGround()) {
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
		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem());

		for (BlockPos pos : step.breaks()) {
			if (!world.isPassable(pos)) {
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

		if (step.type() == MoveType.PILLAR) {
			this.pillar(level, world, step);
		} else {
			this.moveTo(step.to());
		}

		if (this.bot.blockPosition().equals(step.to()) && this.bot.onGround() && this.centered(step.to())) {
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
		LevelWorldView world = new LevelWorldView(level, this.bot.getInventory(), this.bot.getMainHandItem());
		PathFinder.Options options = new PathFinder.Options(true, scaffoldCount(this.bot.getInventory()) > 0, MAX_NODES, MAX_DROP);
		this.path = PathFinder.find(world, this.bot.blockPosition(), this.goal, options);
		this.stepIndex = 0;
		this.stepTicks = 0;
		this.stepBudget = this.budgetFor(world);
		return !this.path.steps().isEmpty() || this.goal.isReached(this.bot.blockPosition());
	}

	private int budgetFor(LevelWorldView world) {
		if (this.path == null || this.stepIndex >= this.path.steps().size()) {
			return STEP_BASE_BUDGET;
		}
		int budget = STEP_BASE_BUDGET;
		for (BlockPos pos : this.path.steps().get(this.stepIndex).breaks()) {
			budget += Math.max(0, (int) world.breakTicks(pos)) + 10;
		}
		return budget;
	}

	private void moveTo(BlockPos to) {
		this.bot.getMoveControl().setWantedPosition(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, SPEED);
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

	private boolean placeBlock(ServerLevel level, BlockPos pos) {
		SimpleContainer inventory = this.bot.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(SCAFFOLD_BLOCKS) && stack.getItem() instanceof BlockItem blockItem) {
				BlockState state = blockItem.getBlock().defaultBlockState();
				Vec3 center = Vec3.atCenterOf(pos);
				this.bot.getLookControl().setLookAt(center.x, center.y, center.z);
				level.setBlockAndUpdate(pos, state);
				level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
				level.gameEvent(this.bot, GameEvent.BLOCK_PLACE, pos);
				this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
				stack.shrink(1);
				return true;
			}
		}
		return false;
	}

	public static int scaffoldCount(SimpleContainer inventory) {
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(SCAFFOLD_BLOCKS)) {
				count += stack.getCount();
			}
		}
		return count;
	}
}
