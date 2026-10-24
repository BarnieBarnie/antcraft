package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseCoralWallFanBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.SporeBlossomBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * What to build, as an ordered list of steps: clear a block, or place a block state. Plain data,
 * so it is saved with the colony and can be made from blueprints. The world itself records the
 * progress: a step is done once the world matches it, so a plan resumes wherever it was left.
 * A terraform's {@code ground} is the ground it levels, up to the height, which paths leave standing
 * while the plan has work left, as no step of the plan would fill a hole in ground already right.
 */
public record BuildPlan(List<Step> steps, List<Batch> batches, Kind kind, Optional<BoundingBox> ground) {
	/** Saves from before batches hold only the steps, as one batch. */
	public static final Codec<BuildPlan> CODEC = Codec.withAlternative(
		RecordCodecBuilder.create(i -> i.group(
			Step.CODEC.listOf().fieldOf("steps").forGetter(BuildPlan::steps),
			Batch.CODEC.listOf().fieldOf("batches").forGetter(BuildPlan::batches),
			Kind.CODEC.optionalFieldOf("kind", Kind.BUILD).forGetter(BuildPlan::kind),
			BoundingBox.CODEC.optionalFieldOf("ground").forGetter(BuildPlan::ground)
		).apply(i, BuildPlan::new)),
		Step.CODEC.listOf().xmap(BuildPlan::new, BuildPlan::steps));

	/**
	 * Property types set by how a block is placed. Others, such as a stair's shape or a fence's
	 * connections, follow the neighbours and are left out when checking a step is done.
	 */
	// ponytail: matched by value type; rotation (signs, banners) is an int like age, so it is not checked yet.
	private static final Set<Class<?>> PLACEMENT = Set.of(Direction.class, Direction.Axis.class, Half.class, SlabType.class,
		DoubleBlockHalf.class, AttachFace.class, DoorHingeSide.class);

	/**
	 * Batches must cover the steps in order, each needing only batches before it. None means one
	 * batch of every step. Steps for a second half (see {@link #isSecondHalf}), such as from an old
	 * save, are dropped, as placing the first half sets them; each batch keeps the rest of its steps.
	 */
	public BuildPlan {
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
		if (steps.stream().anyMatch(step -> isSecondHalf(step.state()))) {
			// kept[i] is how many steps before step i remain, so a batch's bounds move down past the dropped ones.
			int[] kept = new int[steps.size() + 1];
			for (int i = 0; i < steps.size(); i++) {
				kept[i + 1] = kept[i] + (isSecondHalf(steps.get(i).state()) ? 0 : 1);
			}
			batches = batches.stream().map(batch -> new Batch(kept[batch.from()], kept[batch.to()], batch.needs(), batch.dig())).toList();
			steps = steps.stream().filter(step -> !isSecondHalf(step.state())).toList();
		}
		steps = List.copyOf(steps);
	}

	public BuildPlan(List<Step> steps, List<Batch> batches) {
		this(steps, batches, Kind.BUILD);
	}

	public BuildPlan(List<Step> steps, List<Batch> batches, Kind kind) {
		this(steps, batches, kind, Optional.empty());
	}

	/** A plan of one batch. */
	public BuildPlan(List<Step> steps) {
		this(steps, List.of());
	}

	/** What a plan is for, as the colony shows it. */
	public enum Kind implements StringRepresentable {
		/** A structure to build, such as one copied from a region. */
		BUILD,
		/** An area to level to a height (see {@link Terraform}). */
		TERRAFORM;

		public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * Steps {@code from} (inclusive) to {@code to} of a plan, which one builder works through in
	 * order. Several builders can work on different batches of a plan at once, each batch once the
	 * batches it {@code needs}, by index, are done, such as a mineshaft hall once the stairs reach it.
	 * A {@code dig} batch only clears blocks, such as the ground above a terraform's height, so
	 * miners can take it too.
	 */
	public record Batch(int from, int to, List<Integer> needs, boolean dig) {
		public static final Codec<Batch> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("from").forGetter(Batch::from),
			Codec.INT.fieldOf("to").forGetter(Batch::to),
			Codec.INT.listOf().optionalFieldOf("needs", List.of()).forGetter(Batch::needs),
			Codec.BOOL.optionalFieldOf("dig", false).forGetter(Batch::dig)
		).apply(i, Batch::new));

		public Batch {
			needs = List.copyOf(needs);
		}

		public Batch(int from, int to, List<Integer> needs) {
			this(from, to, needs, false);
		}
	}

	/** Batch {@code index} as a plan of its own, of the same kind, to be worked through by one builder. */
	public BuildPlan batch(int index) {
		Batch batch = this.batches.get(index);
		return new BuildPlan(this.steps.subList(batch.from(), batch.to()), List.of(), this.kind, this.ground);
	}

	/** Columns per side of a cell at most (see {@link #inCells}). */
	public static final int CELL = 4;

	/**
	 * This plan with each batch that spreads over more than a square of {@link #CELL} columns split into cells, so
	 * several bots work it at once: squares of columns of about the same size, each of every height. A cell keeps
	 * the order of its steps, so in each column what a block rests on is still placed, and what is in its way dug,
	 * first. Each cell needs the cells of the batches its batch needs that share a column with it, so one on top of
	 * another waits only for what is under it, and builders can work several layers apart at once. A block rests only
	 * on the block under it, in its own column; one hung on a block of the same batch, such as a wall torch, or a lantern
	 * that {@link RegionPlan#copy} moved up to the layer of the ceiling it hangs from, goes in the cell of the block it hangs
	 * on, after it (see {@link #holder}).
	 * A batch that fits one square stays one batch, so a small plan stays as it is.
	 */
	public BuildPlan inCells() {
		List<Step> steps = new ArrayList<>();
		List<Batch> batches = new ArrayList<>();
		// The cells each batch became, by index, and the columns each cell spans: min x, min z, max x, max z.
		List<List<Integer>> cellsOf = new ArrayList<>();
		List<int[]> spans = new ArrayList<>();
		for (Batch batch : this.batches) {
			List<Step> of = this.steps.subList(batch.from(), batch.to());
			List<Integer> needed = batch.needs().stream().flatMap(need -> cellsOf.get(need).stream()).toList();
			int minX = of.stream().mapToInt(step -> step.pos().getX()).min().orElse(0);
			int minZ = of.stream().mapToInt(step -> step.pos().getZ()).min().orElse(0);
			int width = of.stream().mapToInt(step -> step.pos().getX()).max().orElse(0) - minX + 1;
			int depth = of.stream().mapToInt(step -> step.pos().getZ()).max().orElse(0) - minZ + 1;
			int across = Math.ceilDiv(width, CELL);
			int down = Math.ceilDiv(depth, CELL);
			ToIntFunction<BlockPos> cellOf = pos -> (pos.getZ() - minZ) * down / depth * across + (pos.getX() - minX) * across / width;
			// A block hung on one beside it in this batch goes in its holder's cell, after it, even when it lies over the edge.
			Set<BlockPos> placed = of.stream().filter(step -> !step.isClear()).map(Step::pos).collect(Collectors.toSet());
			Map<Integer, List<Step>> cells = new TreeMap<>();
			// The cell each step went to, so a block hung on a hung block, such as a vine under a vine, follows it.
			Map<BlockPos, Integer> went = new HashMap<>();
			List<Step> hung = new ArrayList<>();
			for (Step step : of) {
				if (holder(step, placed::contains).isPresent()) {
					hung.add(step);
				} else {
					went.put(step.pos(), cellOf.applyAsInt(step.pos()));
					cells.computeIfAbsent(went.get(step.pos()), c -> new ArrayList<>()).add(step);
				}
			}
			for (Step step : hung) {
				BlockPos holder = holder(step, placed::contains).orElseThrow();
				went.put(step.pos(), went.getOrDefault(holder, cellOf.applyAsInt(holder)));
				cells.computeIfAbsent(went.get(step.pos()), c -> new ArrayList<>()).add(step);
			}
			if (cells.isEmpty()) {
				// Kept, empty, so the batches after it keep their place.
				cells.put(0, List.of());
			}
			List<Integer> made = new ArrayList<>();
			for (List<Step> cell : cells.values()) {
				// An empty cell spans every column, so what comes after it still waits for what it needed.
				int[] span = cell.isEmpty() ? new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE} : new int[] {
					cell.stream().mapToInt(step -> step.pos().getX()).min().orElseThrow(), cell.stream().mapToInt(step -> step.pos().getZ()).min().orElseThrow(),
					cell.stream().mapToInt(step -> step.pos().getX()).max().orElseThrow(), cell.stream().mapToInt(step -> step.pos().getZ()).max().orElseThrow()};
				List<Integer> needs = needed.stream().filter(need -> overlaps(spans.get(need), span)).toList();
				made.add(batches.size());
				spans.add(span);
				batches.add(new Batch(steps.size(), steps.size() + cell.size(), needs, batch.dig()));
				steps.addAll(cell);
			}
			cellsOf.add(made);
		}
		return new BuildPlan(steps, batches, this.kind, this.ground);
	}

	/**
	 * Where the block {@code step} places hangs from, such as a wall torch, ladder, cocoa or button on a wall, or a lantern,
	 * bell or button under a ceiling: the block it is held by, which must be placed first, among those {@code placed}. A wall
	 * hanging sign or a bell between two walls is held by either side; the first {@code placed} one is taken. Weeping and cave
	 * vines and dripstone pointing down hang from the block above. Plain vines and multiface blocks such as glow lichen are held by
	 * any block one of their faces is on, and a plain vine also by the block above, where it hangs below another vine. Twisting
	 * vines and dripstone pointing up rest on the block below, which the bottom-up order already places first.
	 */
	// ponytail: a list of vanilla blocks rather than canSurvive against the planned states; modded blocks are not covered.
	static Optional<BlockPos> holder(Step step, Predicate<BlockPos> placed) {
		BlockState state = step.state();
		Block block = state.getBlock();
		BlockPos pos = step.pos();
		boolean fromCeiling = block instanceof CeilingHangingSignBlock || block instanceof SporeBlossomBlock || block instanceof HangingRootsBlock
			|| state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING)
			|| block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock || block instanceof CaveVinesBlock || block instanceof CaveVinesPlantBlock
			|| block instanceof PointedDripstoneBlock && state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN
			|| block instanceof BellBlock && state.getValue(BellBlock.ATTACHMENT) == BellAttachType.CEILING
			|| block instanceof FaceAttachedHorizontalDirectionalBlock && state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.CEILING;
		boolean onWall = block instanceof WallTorchBlock || block instanceof RedstoneWallTorchBlock || block instanceof LadderBlock
			|| block instanceof WallSignBlock || block instanceof WallBannerBlock || block instanceof TripWireHookBlock || block instanceof BaseCoralWallFanBlock
			|| block instanceof FaceAttachedHorizontalDirectionalBlock && state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.WALL;
		List<BlockPos> holders = block instanceof MultifaceBlock ? MultifaceBlock.availableFaces(state).stream().map(pos::relative).toList()
			// A vine hangs on any of its faces, or below another vine.
			: block instanceof VineBlock ? Stream.concat(VineBlock.PROPERTY_BY_DIRECTION.entrySet().stream()
				.filter(face -> state.getValue(face.getValue())).map(face -> pos.relative(face.getKey())), Stream.of(pos.above())).toList()
			: fromCeiling ? List.of(pos.above())
			: !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? List.of()
			: onWall ? List.of(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()))
			: block instanceof BellBlock && state.getValue(BellBlock.ATTACHMENT) == BellAttachType.SINGLE_WALL
				? List.of(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)))
			: block instanceof BellBlock && state.getValue(BellBlock.ATTACHMENT) == BellAttachType.DOUBLE_WALL
				? List.of(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)), pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()))
			: block instanceof CocoaBlock ? List.of(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)))
			: block instanceof WallHangingSignBlock ? List.of(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise()),
				pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getCounterClockWise()))
			: List.of();
		return holders.stream().filter(placed).findFirst();
	}

	/** Whether the columns {@code a} and {@code b} span (min x, min z, max x, max z) share one. */
	private static boolean overlaps(int[] a, int[] b) {
		return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
	}

	/** The batches not {@code done} yet whose needed batches are all done, by index. */
	public List<Integer> ready(Set<Integer> done) {
		return this.ready(done, Set.of());
	}

	/**
	 * The batches not {@code done} yet whose needed batches are each done or {@code waiting}: worked
	 * through as far as they go, they wait for items, and are finished later.
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

	/**
	 * Clear the block at {@code pos} when {@code state} is air, otherwise place {@code state} there.
	 * With {@code anySolid}, any block a bot can stand on will do, such as the rock a mineshaft's
	 * walls and floors are; only air, a fluid or the like is replaced with {@code state}. With
	 * {@code mayFall} as well, a block that falls, such as sand, may stand in where it rests on
	 * what is below (see {@link #fillBlocks}); without, the block must hold on its own, such as a
	 * mineshaft's ceiling, or a wall the shaft is later dug out beside and below. With
	 * {@code anyStoneStairs}, any of the {@link #STONE_STAIRS} the same way round will do, such as
	 * for a mineshaft's steps.
	 */
	public record Step(BlockPos pos, BlockState state, boolean anySolid, boolean mayFall, boolean anyStoneStairs) {
		/** Saves from before {@code may_fall} take only fillers that hold, which is safe for any step. */
		public static final Codec<Step> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(Step::pos),
			BlockState.CODEC.optionalFieldOf("state", Blocks.AIR.defaultBlockState()).forGetter(Step::state),
			Codec.BOOL.optionalFieldOf("any_solid", false).forGetter(Step::anySolid),
			Codec.BOOL.optionalFieldOf("may_fall", false).forGetter(Step::mayFall),
			Codec.BOOL.optionalFieldOf("any_stone_stairs", false).forGetter(Step::anyStoneStairs)
		).apply(i, Step::new));

		public Step {
			pos = pos.immutable();
		}

		public Step(BlockPos pos, BlockState state, boolean anySolid, boolean mayFall) {
			this(pos, state, anySolid, mayFall, false);
		}

		public Step(BlockPos pos, BlockState state, boolean anySolid) {
			this(pos, state, anySolid, false);
		}

		public Step(BlockPos pos, BlockState state) {
			this(pos, state, false);
		}

		/**
		 * The fill blocks that may stand in for this step's block, any solid block will do, with
		 * {@code below} the block under it as it is now: {@link #FILL_BLOCKS} when it may fall and
		 * {@code below} holds it up, otherwise only the {@link #HOLDING_FILL_BLOCKS}.
		 */
		public Set<Item> fillBlocks(BlockState below) {
			return this.mayFall && !FallingBlock.isFree(below) ? FILL_BLOCKS : HOLDING_FILL_BLOCKS;
		}

		/** Places the stair {@code state}, or any other of the {@link #STONE_STAIRS} the same way round. */
		public static Step stoneStairs(BlockPos pos, BlockState state) {
			return new Step(pos, state, false, false, true);
		}

		public static Step clear(BlockPos pos) {
			return new Step(pos, Blocks.AIR.defaultBlockState());
		}

		/** Keeps a solid block at {@code pos}, filling it with cobblestone if it is open, such as a cave or a pocket of water. */
		public static Step solid(BlockPos pos) {
			return new Step(pos, Blocks.COBBLESTONE.defaultBlockState(), true);
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
			if (this.anySolid) {
				return world.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
			}
			return this.isClear() ? world.isAir() : matches(this.dry(), world, this.anyStoneStairs);
		}

		/**
		 * The item used next with {@code world} as it is: the block's item, a water bucket once a
		 * waterlogged block stands dry, or an empty bucket to scoop the water out of a block that
		 * stands waterlogged where the plan wants it dry.
		 */
		public Item item(BlockState world) {
			if (this.isPlaced(world) && !this.anySolid && this.isWaterlogged() != isWaterlogged(world)) {
				return this.isWaterlogged() ? Items.WATER_BUCKET : Items.BUCKET;
			}
			return this.item();
		}

		private static boolean isWaterlogged(BlockState state) {
			return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
		}

		/** Whether a builder can do this step at all; not when it places a block without an item, such as a portal. */
		public boolean isPlaceable() {
			return this.isClear() || this.item() != Items.AIR;
		}

		/** Whether the block is in place, holding water just when this step's does. */
		public boolean isDone(BlockState world) {
			return this.isPlaced(world) && (this.anySolid || this.isWaterlogged() == isWaterlogged(world));
		}

		private static boolean matches(BlockState wanted, BlockState world, boolean anyStoneStairs) {
			if (!world.is(wanted.getBlock()) && !(anyStoneStairs && STONE_STAIRS.contains(world.getBlock().asItem()))) {
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
		 * The lowest pickaxe tier that gets the drops of {@code world}, the block in this step's way,
		 * if it is to be broken and is dug with a pickaxe, such as stone or an ore.
		 */
		public Optional<Item> pickaxe(BlockState world) {
			return this.needsClearing(world) && world.is(BlockTags.MINEABLE_WITH_PICKAXE)
				? Optional.of(PickaxeTier.lowestFor(world.getBlock())) : Optional.empty();
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

	/**
	 * Whether {@code step} pours a fluid while a block this plan places beside or under it is not in {@code world} yet:
	 * poured first, it would flow out where a wall is still missing, as lava flows out of a cobblestone generator, so it
	 * waits. A block placed over it, or a waterlogged block (placed dry, then filled), does not wait.
	 */
	public boolean waitsForWalls(Function<BlockPos, BlockState> world, Step step) {
		if (step.isClear() || !(step.state().getBlock() instanceof LiquidBlock)) {
			return false;
		}
		Set<BlockPos> around = Stream.concat(Direction.Plane.HORIZONTAL.stream().map(step.pos()::relative), Stream.of(step.pos().below()))
			.collect(Collectors.toSet());
		return this.steps.stream().anyMatch(wall -> around.contains(wall.pos()) && !wall.isClear()
			&& !(wall.state().getBlock() instanceof LiquidBlock) && !wall.isPlaced(world.apply(wall.pos())));
	}

	/** The first step the world does not match yet. */
	public Optional<Step> nextStep(Function<BlockPos, BlockState> world) {
		return this.nextStep(world, step -> true);
	}

	/** The first step the world does not match yet among those that {@code workable} allows, so a blocked step does not hold up the rest. */
	public Optional<Step> nextStep(Function<BlockPos, BlockState> world, Predicate<Step> workable) {
		return this.nextStep(world, workable, new BitSet());
	}

	/**
	 * What a builder found done of one plan, so it does not look at those blocks again on every
	 * call, such as the hundreds of dug stairs and walls of a deep mineshaft. Not saved; a builder
	 * keeps one per batch it works on.
	 */
	public static final class Progress {
		/** Every this many calls the steps found done are looked at again, in case one was undone since, such as by a block falling in. */
		public static final int REFRESH = 20;
		private final BitSet done = new BitSet();
		private int calls;
	}

	/**
	 * {@link #nextStep(Function, Predicate)}, skipping the steps {@code progress} found done. A step
	 * undone since is found again within {@link Progress#REFRESH} calls, and always before the plan
	 * counts as finished: a call that finds nothing looks at every step again.
	 */
	public Optional<Step> nextStep(Function<BlockPos, BlockState> world, Predicate<Step> workable, Progress progress) {
		if (++progress.calls % Progress.REFRESH == 0) {
			progress.done.clear();
		}
		Optional<Step> next = this.nextStep(world, workable, progress.done);
		if (next.isEmpty() && !progress.done.isEmpty()) {
			progress.done.clear();
			next = this.nextStep(world, workable, progress.done);
		}
		return next;
	}

	/** The first step not in {@code done} that the world does not match and {@code workable} allows; adds the steps found done. */
	private Optional<Step> nextStep(Function<BlockPos, BlockState> world, Predicate<Step> workable, BitSet done) {
		for (int i = done.nextClearBit(0); i < this.steps.size(); i = done.nextClearBit(i + 1)) {
			Step step = this.steps.get(i);
			if (step.isDone(world.apply(step.pos()))) {
				done.set(i);
			} else if (workable.test(step)) {
				return Optional.of(step);
			}
		}
		return Optional.empty();
	}

	/**
	 * The blocks a step any solid block will do takes when the colony lacks the step's own, such as
	 * for a terraform's fill: cheap fillers, and sand and gravel, which sink to the bottom of water.
	 */
	public static final Set<Item> FILL_BLOCKS = Set.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.SAND, Items.RED_SAND, Items.GRAVEL);

	/** The {@link #FILL_BLOCKS} that hold where they are placed, for a step whose block must not fall (see {@link Step#mayFall}). */
	public static final Set<Item> HOLDING_FILL_BLOCKS = Set.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);

	/**
	 * Which of the {@link #HOLDING_FILL_BLOCKS} the colony {@code has} to fill water with that is broken next, such as
	 * a source above a terraform's height: dirt without a {@code pickaxe}, as cobblestone is slow to break by hand,
	 * else cobblestone; either rather than none.
	 */
	public static Optional<Item> waterFiller(Predicate<Item> has, boolean pickaxe) {
		List<Item> order = pickaxe ? List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT)
			: List.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
		return order.stream().filter(has).findFirst();
	}

	/**
	 * The part of {@link #materials(Function)} for steps any solid block will do, which a fill block
	 * of another kind can stand in for: those that {@code mayFall}, or those that must hold.
	 */
	public Map<Item, Integer> fillMaterials(Function<BlockPos, BlockState> world, boolean mayFall) {
		return this.materialsOf(world, step -> step.anySolid() && step.mayFall() == mayFall);
	}

	/**
	 * The stairs a step of {@link Step#anyStoneStairs} takes: stone ones, never wooden, which burn.
	 * Its own block, cobblestone stairs, is what the colony makes when it holds none of these.
	 */
	// ponytail: a fixed list rather than a tag, so unit tests see it; make it a tag if other mods' stairs should count.
	public static final Set<Item> STONE_STAIRS = Set.of(Items.COBBLESTONE_STAIRS, Items.STONE_STAIRS, Items.STONE_BRICK_STAIRS,
		Items.MOSSY_COBBLESTONE_STAIRS, Items.MOSSY_STONE_BRICK_STAIRS, Items.COBBLED_DEEPSLATE_STAIRS, Items.POLISHED_DEEPSLATE_STAIRS,
		Items.DEEPSLATE_BRICK_STAIRS, Items.DEEPSLATE_TILE_STAIRS, Items.ANDESITE_STAIRS, Items.POLISHED_ANDESITE_STAIRS,
		Items.DIORITE_STAIRS, Items.POLISHED_DIORITE_STAIRS, Items.GRANITE_STAIRS, Items.POLISHED_GRANITE_STAIRS,
		Items.BLACKSTONE_STAIRS, Items.POLISHED_BLACKSTONE_STAIRS, Items.POLISHED_BLACKSTONE_BRICK_STAIRS,
		Items.TUFF_STAIRS, Items.POLISHED_TUFF_STAIRS, Items.TUFF_BRICK_STAIRS);

	/** The part of {@link #materials(Function)} for steps of {@link Step#anyStoneStairs}, which other stone stairs can stand in for. */
	public Map<Item, Integer> stoneStairMaterials(Function<BlockPos, BlockState> world) {
		return this.materialsOf(world, Step::anyStoneStairs);
	}

	private Map<Item, Integer> materialsOf(Function<BlockPos, BlockState> world, Predicate<Step> which) {
		Map<Item, Integer> materials = new LinkedHashMap<>();
		for (Step step : this.steps) {
			if (which.test(step) && !step.isPlaced(world.apply(step.pos()))) {
				materials.merge(step.item(), 1, Integer::sum);
			}
		}
		return materials;
	}

	/**
	 * The fill blocks the steps still open with {@code world} as it is can use: the
	 * {@link #FILL_BLOCKS} while a step that may fall is open, else the {@link #HOLDING_FILL_BLOCKS}
	 * while one that must hold is, else none. A mineshaft's walls never want sand or gravel.
	 */
	public Set<Item> wantedFillBlocks(Function<BlockPos, BlockState> world) {
		if (!this.fillMaterials(world, true).isEmpty()) {
			return FILL_BLOCKS;
		}
		return this.fillMaterials(world, false).isEmpty() ? Set.of() : HOLDING_FILL_BLOCKS;
	}

	/**
	 * What to order for {@code materials} beyond what the colony {@code has}. Of those, the steps any
	 * solid block will do take the fill blocks the colony has to spare: those that must hold
	 * ({@code holding}) only the {@link #HOLDING_FILL_BLOCKS}, and those that may fall
	 * ({@code resting}) any of the {@link #FILL_BLOCKS}, sand and gravel first. Only what the fill
	 * blocks lack is ordered, as the step's own block.
	 */
	public static Map<Item, Integer> shortfall(Map<Item, Integer> materials, Map<Item, Integer> holding, Map<Item, Integer> resting,
			Map<Item, Integer> has) {
		return shortfall(materials, holding, resting, Map.of(), has);
	}

	/**
	 * {@link #shortfall(Map, Map, Map, Map)}, where the {@code stairs} of steps any stone stairs will do
	 * (see {@link #stoneStairMaterials}) take the {@link #STONE_STAIRS} the colony has to spare.
	 */
	public static Map<Item, Integer> shortfall(Map<Item, Integer> materials, Map<Item, Integer> holding, Map<Item, Integer> resting,
			Map<Item, Integer> stairs, Map<Item, Integer> has) {
		return shortfall(materials, holding, resting, stairs, has, new LinkedHashMap<>());
	}

	/**
	 * {@link #shortfall(Map, Map, Map, Map, Map)}, adding the spare fill blocks and stone stairs it takes to {@code used},
	 * so they are not there for anything else.
	 */
	public static Map<Item, Integer> shortfall(Map<Item, Integer> materials, Map<Item, Integer> holding, Map<Item, Integer> resting,
			Map<Item, Integer> stairs, Map<Item, Integer> has, Map<Item, Integer> used) {
		Map<Item, Integer> needed = new LinkedHashMap<>();
		materials.forEach((item, count) -> {
			if (count > has.getOrDefault(item, 0)) {
				needed.put(item, count - has.getOrDefault(item, 0));
			}
		});
		Map<Item, Integer> spareHolding = spare(HOLDING_FILL_BLOCKS::contains, materials, has);
		Map<Item, Integer> spareFalling = spare(item -> FILL_BLOCKS.contains(item) && !HOLDING_FILL_BLOCKS.contains(item), materials, has);
		Map<Item, Integer> rest = new LinkedHashMap<>(resting);
		cover(needed, new LinkedHashMap<>(holding), spareHolding, used);
		cover(needed, rest, spareFalling, used);
		cover(needed, rest, spareHolding, used);
		cover(needed, new LinkedHashMap<>(stairs), spare(STONE_STAIRS::contains, materials, has), used);
		needed.values().removeIf(count -> count <= 0);
		return needed;
	}

	/** Which of {@code blocks} the colony {@code has} beyond what {@code materials} places as such, how many of each, in item id order. */
	private static Map<Item, Integer> spare(Predicate<Item> blocks, Map<Item, Integer> materials, Map<Item, Integer> has) {
		Map<Item, Integer> spare = new TreeMap<>(Comparator.comparingInt(Item::getId));
		has.forEach((item, count) -> {
			if (blocks.test(item) && count > materials.getOrDefault(item, 0)) {
				spare.put(item, count - materials.getOrDefault(item, 0));
			}
		});
		return spare;
	}

	/** Takes what {@code fills} still need off {@code needed} and {@code fills}, using up the {@code spare} fill blocks, which go to {@code used}. */
	private static void cover(Map<Item, Integer> needed, Map<Item, Integer> fills, Map<Item, Integer> spare, Map<Item, Integer> used) {
		for (Map.Entry<Item, Integer> fill : fills.entrySet()) {
			int want = Math.min(fill.getValue(), needed.getOrDefault(fill.getKey(), 0));
			for (Map.Entry<Item, Integer> block : spare.entrySet()) {
				int cover = Math.min(want, block.getValue());
				if (cover > 0) {
					want -= cover;
					block.setValue(block.getValue() - cover);
					used.merge(block.getKey(), cover, Integer::sum);
					fill.setValue(fill.getValue() - cover);
					needed.merge(fill.getKey(), -cover, Integer::sum);
				}
			}
		}
	}

	/**
	 * What a bot needs for the steps left with {@code world} as it is, for the job score: {@code tool} is the
	 * best pickaxe tier a block in the way needs, and {@code item} the one item every step left places, if
	 * that is all there is left to do. While there is anything else, such as blocks to dig, a step any solid
	 * block or any stone stairs will do, or a fluid poured from a bucket filled at a source, a bot can go on without the item.
	 */
	public record Needs(Optional<Item> item, Optional<Item> tool) {
	}

	/** See {@link Needs}. */
	public Needs needs(Function<BlockPos, BlockState> world) {
		return this.needs(world, step -> true);
	}

	/** {@link #needs(Function)} of just the steps that are {@code possible}, so a step no bot can do declares nothing. */
	public Needs needs(Function<BlockPos, BlockState> world, Predicate<Step> possible) {
		Set<Item> items = new HashSet<>();
		boolean other = false;
		Optional<Item> tool = Optional.empty();
		List<Item> tiers = ToolKind.PICKAXE.tiers();
		for (Step step : this.steps) {
			BlockState current = world.apply(step.pos());
			if (step.isDone(current) || !possible.test(step)) {
				continue;
			}
			Item item = step.item(current);
			if (takesAnyItem(step, current, item)) {
				other = true;
			} else {
				items.add(item);
			}
			Optional<Item> pickaxe = step.pickaxe(current);
			if (pickaxe.isPresent() && (tool.isEmpty() || tiers.indexOf(pickaxe.get()) > tiers.indexOf(tool.get()))) {
				tool = pickaxe;
			}
		}
		return new Needs(!other && items.size() == 1 ? items.stream().findFirst() : Optional.empty(), tool);
	}

	/** Whether a step left with {@code world} as it is can be done without one particular item: see {@link Needs}. */
	private static boolean takesAnyItem(Step step, BlockState current, Item item) {
		return step.anySolid() || step.anyStoneStairs() || step.needsClearing(current) || item == Items.AIR || item instanceof BucketItem;
	}

	/**
	 * Whether a bot could start on the plan now with {@code world} as it is: a step left needs no particular item
	 * (see {@link Needs}), or places one the colony {@code has}.
	 */
	public boolean hasStepFor(Function<BlockPos, BlockState> world, Predicate<Item> has) {
		for (Step step : this.steps) {
			BlockState current = world.apply(step.pos());
			if (!step.isDone(current)) {
				Item item = step.item(current);
				if (takesAnyItem(step, current, item) || has.test(item)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Every item the plan places, with a water bucket for each waterlogged block and one flint and steel for all its fire. */
	public Map<Item, Integer> materials() {
		return this.materials(pos -> Blocks.AIR.defaultBlockState());
	}

	/** The items still to place: those of the steps the world does not match yet, and one empty bucket when a block wanted dry holds water. */
	public Map<Item, Integer> materials(Function<BlockPos, BlockState> world) {
		return this.materials(world, step -> true);
	}

	/**
	 * {@link #materials(Function)} for just the steps that are {@code possible}, so a step a builder
	 * cannot do, such as fire that would not stay lit, orders nothing.
	 */
	public Map<Item, Integer> materials(Function<BlockPos, BlockState> world, Predicate<Step> possible) {
		Map<Item, Integer> materials = new LinkedHashMap<>();
		for (Step step : this.steps) {
			if (!possible.test(step)) {
				continue;
			}
			BlockState current = world.apply(step.pos());
			if (step.isLit() && !step.isPlaced(current)) {
				// A tool, not used up: one lights every fire.
				materials.put(Items.FLINT_AND_STEEL, 1);
			} else if (step.item() != Items.AIR && !step.isPlaced(current)) {
				materials.merge(step.item(), 1, Integer::sum);
			}
			if (step.isWaterlogged() && !step.isDone(current)) {
				materials.merge(Items.WATER_BUCKET, 1, Integer::sum);
			} else if (step.item(current) == Items.BUCKET) {
				// A tool, not used up: one bucket scoops the water out of every block.
				materials.put(Items.BUCKET, 1);
			}
		}
		return materials;
	}
}
