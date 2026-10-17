package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * What to build, as an ordered list of steps: clear a block, or place a block state. Plain data,
 * so it is saved with the colony and can be made from blueprints. The world itself records the
 * progress: a step is done once the world matches it, so a plan resumes wherever it was left.
 */
public record BuildPlan(List<Step> steps) {
	public static final Codec<BuildPlan> CODEC = Step.CODEC.listOf().xmap(BuildPlan::new, BuildPlan::steps);

	/**
	 * Property types set by how a block is placed. Others, such as a stair's shape or a fence's
	 * connections, follow the neighbours and are left out when checking a step is done.
	 */
	// ponytail: matched by value type; rotation (signs, banners) is an int like age, so it is not checked yet.
	private static final Set<Class<?>> PLACEMENT = Set.of(Direction.class, Direction.Axis.class, Half.class, SlabType.class,
		DoubleBlockHalf.class, AttachFace.class, DoorHingeSide.class);

	public BuildPlan {
		steps = List.copyOf(steps);
	}

	/** Clear the block at {@code pos} when {@code state} is air, otherwise place {@code state} there. */
	public record Step(BlockPos pos, BlockState state) {
		public static final Codec<Step> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(Step::pos),
			BlockState.CODEC.optionalFieldOf("state", Blocks.AIR.defaultBlockState()).forGetter(Step::state)
		).apply(i, Step::new));

		public Step {
			pos = pos.immutable();
		}

		public static Step clear(BlockPos pos) {
			return new Step(pos, Blocks.AIR.defaultBlockState());
		}

		public boolean isClear() {
			return this.state.isAir();
		}

		/**
		 * The item placed, as a player would: a fluid source is poured from its bucket, which is
		 * left empty. Air for a clear step or a block a player cannot place, such as fire.
		 */
		public Item item() {
			if (this.state.getBlock() instanceof LiquidBlock) {
				return this.state.getFluidState().isSource() ? this.state.getFluidState().getType().getBucket() : Items.AIR;
			}
			return this.state.getBlock().asItem();
		}

		/** Whether a builder can do this step at all; not when it places a block without an item, such as fire or a portal. */
		public boolean isPlaceable() {
			return this.isClear() || this.item() != Items.AIR;
		}

		public boolean isDone(BlockState world) {
			if (this.isClear()) {
				return world.isAir();
			}
			if (!world.is(this.state.getBlock())) {
				return false;
			}
			for (Property<?> property : this.state.getProperties()) {
				if (PLACEMENT.contains(property.getValueClass()) && !world.getValue(property).equals(this.state.getValue(property))) {
					return false;
				}
			}
			return true;
		}

		/** Whether {@code world} has to be broken first, including the same block placed the wrong way round. */
		public boolean needsClearing(BlockState world) {
			return !world.isAir() && !this.isDone(world) && (this.isClear() || !world.canBeReplaced());
		}

		/**
		 * How a clear step gets its block out of the way, as a player would. A fluid source is
		 * scooped up with an empty bucket, or else filled with a block that is broken next. Flowing
		 * fluid is left to dry up while it is {@code settling}; fed from outside the plan, it cannot
		 * be cleared. A block holding back a fluid beside or above it is left standing until that
		 * fluid is gone, so the fluid does not flow back in.
		 */
		public Clearing clearing(Function<BlockPos, BlockState> world, boolean settling, boolean hasBucket, boolean hasFiller) {
			BlockState here = world.apply(this.pos);
			if (here.getBlock() instanceof LiquidBlock) {
				if (!here.getFluidState().isSource()) {
					return settling ? Clearing.SETTLE : Clearing.BLOCKED;
				}
				return hasBucket ? Clearing.SCOOP : hasFiller ? Clearing.FILL : Clearing.BLOCKED;
			}
			for (Direction side : new Direction[] {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
				if (!world.apply(this.pos.relative(side)).getFluidState().isEmpty()) {
					return Clearing.BLOCKED;
				}
			}
			return Clearing.BREAK;
		}
	}

	/** See {@link Step#clearing}. */
	public enum Clearing { BREAK, SCOOP, FILL, SETTLE, BLOCKED }

	/** The position a build is known by, such as on the job board. */
	public BlockPos target() {
		return this.steps.getFirst().pos();
	}

	/** The first step the world does not match yet. */
	public Optional<Step> nextStep(Function<BlockPos, BlockState> world) {
		return this.nextStep(world, step -> true);
	}

	/** The first step the world does not match yet among those that {@code workable} allows, so a blocked step does not hold up the rest. */
	public Optional<Step> nextStep(Function<BlockPos, BlockState> world, Predicate<Step> workable) {
		return this.steps.stream().filter(step -> !step.isDone(world.apply(step.pos())) && workable.test(step)).findFirst();
	}

	/** Every item the plan places. */
	public Map<Item, Integer> materials() {
		return this.materials(pos -> Blocks.AIR.defaultBlockState());
	}

	/** The items still to place: those of the steps the world does not match yet. */
	public Map<Item, Integer> materials(Function<BlockPos, BlockState> world) {
		Map<Item, Integer> materials = new LinkedHashMap<>();
		for (Step step : this.steps) {
			if (step.item() != Items.AIR && !step.isDone(world.apply(step.pos()))) {
				materials.merge(step.item(), 1, Integer::sum);
			}
		}
		return materials;
	}
}
