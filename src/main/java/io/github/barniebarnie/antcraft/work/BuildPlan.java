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
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
public record BuildPlan(List<Step> steps, List<Batch> batches) {
	/** Saves from before batches hold only the steps, as one batch. */
	public static final Codec<BuildPlan> CODEC = Codec.withAlternative(
		RecordCodecBuilder.create(i -> i.group(
			Step.CODEC.listOf().fieldOf("steps").forGetter(BuildPlan::steps),
			Batch.CODEC.listOf().fieldOf("batches").forGetter(BuildPlan::batches)
		).apply(i, BuildPlan::new)),
		Step.CODEC.listOf().xmap(BuildPlan::new, BuildPlan::steps));

	/**
	 * Property types set by how a block is placed. Others, such as a stair's shape or a fence's
	 * connections, follow the neighbours and are left out when checking a step is done.
	 */
	// ponytail: matched by value type; rotation (signs, banners) is an int like age, so it is not checked yet.
	private static final Set<Class<?>> PLACEMENT = Set.of(Direction.class, Direction.Axis.class, Half.class, SlabType.class,
		DoubleBlockHalf.class, AttachFace.class, DoorHingeSide.class);

	/** Batches must cover the steps in order, each needing only batches before it. None means one batch of every step. */
	public BuildPlan {
		steps = List.copyOf(steps);
		batches = batches.isEmpty() ? List.of(new Batch(0, steps.size(), List.of())) : List.copyOf(batches);
		int from = 0;
		for (int i = 0; i < batches.size(); i++) {
			Batch batch = batches.get(i);
			int index = i;
			if (batch.from() != from || batch.to() < batch.from() || batch.needs().stream().anyMatch(need -> need < 0 || need >= index)) {
				throw new IllegalArgumentException("batch " + i + " out of order: " + batch);
			}
			from = batch.to();
		}
		if (from != steps.size()) {
			throw new IllegalArgumentException("batches cover " + from + " of " + steps.size() + " steps");
		}
	}

	/** A plan of one batch. */
	public BuildPlan(List<Step> steps) {
		this(steps, List.of());
	}

	/**
	 * Steps {@code from} (inclusive) to {@code to} of a plan, which one builder works through in
	 * order. Several builders can work on different batches of a plan at once, each batch once the
	 * batches it {@code needs}, by index, are done, such as a mineshaft hall once the stairs reach it.
	 */
	public record Batch(int from, int to, List<Integer> needs) {
		public static final Codec<Batch> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("from").forGetter(Batch::from),
			Codec.INT.fieldOf("to").forGetter(Batch::to),
			Codec.INT.listOf().optionalFieldOf("needs", List.of()).forGetter(Batch::needs)
		).apply(i, Batch::new));

		public Batch {
			needs = List.copyOf(needs);
		}
	}

	/** Batch {@code index} as a plan of its own, to be worked through by one builder. */
	public BuildPlan batch(int index) {
		Batch batch = this.batches.get(index);
		return new BuildPlan(this.steps.subList(batch.from(), batch.to()));
	}

	/** The batches not {@code done} yet whose needed batches are all done, by index. */
	public List<Integer> ready(Set<Integer> done) {
		return this.ready(done, Set.of());
	}

	/**
	 * The batches not {@code done} yet whose needed batches are each done or {@code waiting}: worked
	 * through as far as they go, they wait for items or a fluid to settle, and are finished later.
	 */
	public List<Integer> ready(Set<Integer> done, Set<Integer> waiting) {
		return IntStream.range(0, this.batches.size())
			.filter(i -> !done.contains(i) && this.batches.get(i).needs().stream().allMatch(need -> done.contains(need) || waiting.contains(need)))
			.boxed().toList();
	}

	/**
	 * Whether {@code state} is the half of a two-block block that placing the other sets: the upper
	 * half of a door or tall plant, or a bed's head. A plan has no step for it.
	 */
	public static boolean isSecondHalf(BlockState state) {
		return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
			|| state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
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
		 * left empty, and fire is lit with flint and steel, which is kept. Air for a clear step or a
		 * block a player cannot place, such as a portal.
		 */
		public Item item() {
			if (this.isLit()) {
				return Items.FLINT_AND_STEEL;
			}
			if (this.state.getBlock() instanceof LiquidBlock) {
				return this.state.getFluidState().isSource() ? this.state.getFluidState().getType().getBucket() : Items.AIR;
			}
			return this.state.getBlock().asItem();
		}

		/**
		 * The other half of a two-block block, set together with this step's as a player's placing
		 * does: the upper half above a door or tall plant, or a bed's head the way it faces.
		 */
		public Optional<Step> otherHalf() {
			if (this.state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && this.state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
				return Optional.of(new Step(this.pos.above(), this.state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
			}
			if (this.state.hasProperty(BlockStateProperties.BED_PART) && this.state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
				Direction facing = this.state.getValue(BlockStateProperties.HORIZONTAL_FACING);
				return Optional.of(new Step(this.pos.relative(facing), this.state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
			}
			return Optional.empty();
		}

		/** Whether this step is fire, lit on the block below with flint and steel rather than placed. */
		public boolean isLit() {
			return this.state.getBlock() instanceof BaseFireBlock;
		}

		/** Whether this step's block holds water, such as a waterlogged stair. */
		public boolean isWaterlogged() {
			return this.state.hasProperty(BlockStateProperties.WATERLOGGED) && this.state.getValue(BlockStateProperties.WATERLOGGED);
		}

		/** The block state placed first: a waterlogged block goes in dry and is then filled from a water bucket, as a player would. */
		public BlockState dry() {
			return this.isWaterlogged() ? this.state.setValue(BlockStateProperties.WATERLOGGED, false) : this.state;
		}

		/** Whether the block is in place, though a waterlogged one may still lack its water. */
		public boolean isPlaced(BlockState world) {
			return this.isClear() ? world.isAir() : matches(this.dry(), world);
		}

		/** The item used next with {@code world} as it is: the block's item, or a water bucket once a waterlogged block stands dry. */
		public Item item(BlockState world) {
			return this.isWaterlogged() && this.isPlaced(world) ? Items.WATER_BUCKET : this.item();
		}

		/** Whether a builder can do this step at all; not when it places a block without an item, such as a portal. */
		public boolean isPlaceable() {
			return this.isClear() || this.item() != Items.AIR;
		}

		public boolean isDone(BlockState world) {
			return this.isPlaced(world) && (!this.isWaterlogged() || world.getValue(BlockStateProperties.WATERLOGGED));
		}

		private static boolean matches(BlockState wanted, BlockState world) {
			if (!world.is(wanted.getBlock())) {
				return false;
			}
			for (Property<?> property : wanted.getProperties()) {
				if (PLACEMENT.contains(property.getValueClass()) && !world.getValue(property).equals(wanted.getValue(property))) {
					return false;
				}
			}
			return true;
		}

		/** Whether {@code world} has to be broken first, including the same block placed the wrong way round. */
		public boolean needsClearing(BlockState world) {
			return !world.isAir() && !this.isPlaced(world) && (this.isClear() || !world.canBeReplaced());
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

	/** Every item the plan places, with a water bucket for each waterlogged block and one flint and steel for all its fire. */
	public Map<Item, Integer> materials() {
		return this.materials(pos -> Blocks.AIR.defaultBlockState());
	}

	/** The items still to place: those of the steps the world does not match yet. */
	public Map<Item, Integer> materials(Function<BlockPos, BlockState> world) {
		Map<Item, Integer> materials = new LinkedHashMap<>();
		for (Step step : this.steps) {
			BlockState current = world.apply(step.pos());
			if (step.isLit() && !step.isPlaced(current)) {
				// A tool, not used up: one lights every fire.
				materials.put(Items.FLINT_AND_STEEL, 1);
			} else if (step.item() != Items.AIR && !step.isPlaced(current)) {
				materials.merge(step.item(), 1, Integer::sum);
			}
			if (step.isWaterlogged() && !step.isDone(current)) {
				materials.merge(Items.WATER_BUCKET, 1, Integer::sum);
			}
		}
		return materials;
	}
}
