package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colony mine: a 1-wide staircase that spirals down a 2x2 column from {@code top} (the feet
 * height of the first stair, at ground level), with a landing at each mining level. From each
 * landing a hall leads away from the column, and branches run off both sides of the hall every
 * {@link #BRANCH_SPACING} blocks. Builders dig the stairs and halls; miners dig the branches.
 * Once every branch at a level is dug, the next round of branches carries each one further out,
 * up to {@code rounds} rounds, so a level keeps yielding ore without the hall growing.
 * Every stair has three blocks of headroom, so any bot can walk up and down without scaffold.
 */
public record Mineshaft(BlockPos top, List<Integer> levels, int branchesPerSide, int branchLength, int torchSpacing, int rounds) {
	public static final int BRANCH_SPACING = 3;
	public static final int DEFAULT_BRANCHES_PER_SIDE = 4;
	public static final int DEFAULT_BRANCH_LENGTH = 16;
	public static final int DEFAULT_TORCH_SPACING = 8;
	public static final int DEFAULT_ROUNDS = 4;
	/** The stair cells of the column, in the order the staircase winds down. */
	private static final List<BlockPos> CYCLE = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(1, 0, 1), new BlockPos(0, 0, 1));

	public static final Codec<Mineshaft> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.fieldOf("top").forGetter(Mineshaft::top),
		Codec.INT.listOf().fieldOf("levels").forGetter(Mineshaft::levels),
		Codec.INT.optionalFieldOf("branches_per_side", DEFAULT_BRANCHES_PER_SIDE).forGetter(Mineshaft::branchesPerSide),
		Codec.INT.optionalFieldOf("branch_length", DEFAULT_BRANCH_LENGTH).forGetter(Mineshaft::branchLength),
		Codec.INT.optionalFieldOf("torch_spacing", DEFAULT_TORCH_SPACING).forGetter(Mineshaft::torchSpacing),
		Codec.INT.optionalFieldOf("rounds", DEFAULT_ROUNDS).forGetter(Mineshaft::rounds)
	).apply(i, Mineshaft::new));

	/** A tunnel a miner digs, 2 high and 1 wide; {@code cells} run from the hall outwards. */
	public record Branch(BlockPos start, List<BlockPos> cells, List<BlockPos> torches) {
		/** The first torch spot that is dug out but has no torch yet. */
		public Optional<BlockPos> darkTorch(LevelReader level) {
			BlockState torch = Blocks.TORCH.defaultBlockState();
			return this.torches.stream().filter(pos -> level.getBlockState(pos).isAir() && torch.canSurvive(level, pos)).findFirst();
		}
	}

	/** Only levels below the top, deepest last. */
	public Mineshaft {
		top = top.immutable();
		int topY = top.getY();
		levels = levels.stream().filter(y -> y < topY).distinct().sorted(Comparator.reverseOrder()).toList();
	}

	public Mineshaft(BlockPos top, List<Integer> levels, int branchesPerSide, int branchLength, int torchSpacing) {
		this(top, levels, branchesPerSide, branchLength, torchSpacing, DEFAULT_ROUNDS);
	}

	public Mineshaft(BlockPos top, List<Integer> levels) {
		this(top, levels, DEFAULT_BRANCHES_PER_SIDE, DEFAULT_BRANCH_LENGTH, DEFAULT_TORCH_SPACING);
	}

	/** The site the colony picks by itself: on a side of the core clear of the {@code avoid} areas (field, storage). */
	public static Mineshaft nextTo(BlockPos core, List<Integer> levels, List<BoundingBox> avoid) {
		// ponytail: four fixed spots, no terrain check; move it with /antcraft colony mineshaft.
		return Stream.of(core.offset(2, 0, 2), core.offset(-3, 0, -3), core.offset(2, 0, -3), core.offset(-3, 0, 2))
			.map(top -> new Mineshaft(top, levels))
			.filter(shaft -> avoid.stream().noneMatch(shaft.area()::intersects))
			.findFirst().orElseGet(() -> new Mineshaft(core.offset(2, 0, 2), levels));
	}

	/** The column the stairs wind down, from the bottom floor to the top of the entrance. */
	public BoundingBox area() {
		int bottom = this.levels.isEmpty() ? this.top.getY() : this.levels.getLast();
		return new BoundingBox(this.top.getX(), bottom - 1, this.top.getZ(), this.top.getX() + 1, this.top.getY() + 2, this.top.getZ() + 1);
	}

	/** Feet positions of the stairs, top first. */
	public List<BlockPos> stairs() {
		List<BlockPos> stairs = new ArrayList<>();
		int depth = this.levels.isEmpty() ? 0 : this.top.getY() - this.levels.getLast();
		for (int i = 0; i <= depth; i++) {
			stairs.add(this.top.offset(CYCLE.get(i % CYCLE.size())).below(i));
		}
		return stairs;
	}

	/** The stair at a mining level. */
	public BlockPos landing(int level) {
		return this.stairs().get(this.top.getY() - level);
	}

	/**
	 * Where the landing chest of a mining level goes: beside the landing, out of the column on the
	 * side opposite the hall's, so it is clear of the stairs, the hall and the branches.
	 */
	public BlockPos landingChest(int level) {
		return this.landing(level).relative(this.chestSide(level));
	}

	private Direction chestSide(int level) {
		return this.landing(level).getZ() == this.top.getZ() ? Direction.NORTH : Direction.SOUTH;
	}

	/** Away from the column, on the side the landing's stair is on. */
	private Direction out(int level) {
		return this.landing(level).getX() == this.top.getX() ? Direction.WEST : Direction.EAST;
	}

	/** Feet positions of the hall at a mining level, from the landing outwards. */
	public List<BlockPos> hall(int level) {
		List<BlockPos> hall = new ArrayList<>();
		for (int n = 1; n <= this.branchesPerSide * BRANCH_SPACING; n++) {
			hall.add(this.landing(level).relative(this.out(level), n));
		}
		return hall;
	}

	/** Where the builder puts torches: every few stairs, at each landing and every few blocks of each hall. */
	public List<BlockPos> torches() {
		Set<BlockPos> torches = new LinkedHashSet<>();
		List<BlockPos> stairs = this.stairs();
		for (int i = 0; i < stairs.size(); i++) {
			if (i % this.torchSpacing == 0) {
				torches.add(stairs.get(i));
			}
			int y = stairs.get(i).getY();
			if (i > 0 && this.levels.contains(y)) {
				torches.add(stairs.get(i));
				List<BlockPos> hall = this.hall(y);
				for (int n = 1; n <= hall.size(); n++) {
					if (n % this.torchSpacing == 0) {
						torches.add(hall.get(n - 1));
					}
				}
			}
		}
		return List.copyOf(torches);
	}

	/** Digs the stairs from the top down, each landing's hall as it is reached, with torches. */
	public BuildPlan plan() {
		Map<BlockPos, BuildPlan.Step> steps = new LinkedHashMap<>();
		Set<BlockPos> torches = Set.copyOf(this.torches());
		List<BlockPos> stairs = this.stairs();
		for (int i = 0; i < stairs.size(); i++) {
			BlockPos stair = stairs.get(i);
			// From the top, so the builder can reach each block from the stair above.
			for (int up = 2; up >= 0; up--) {
				this.dig(steps, stair.above(up), torches);
			}
			if (i > 0 && this.levels.contains(stair.getY())) {
				for (BlockPos cell : this.hall(stair.getY())) {
					this.dig(steps, cell.above(), torches);
					this.dig(steps, cell, torches);
				}
				// Facing the landing, where miners and haulers use it.
				BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, this.chestSide(stair.getY()).getOpposite());
				steps.put(this.landingChest(stair.getY()), new BuildPlan.Step(this.landingChest(stair.getY()), chest));
			}
		}
		return new BuildPlan(List.copyOf(steps.values()));
	}

	private void dig(Map<BlockPos, BuildPlan.Step> steps, BlockPos pos, Set<BlockPos> torches) {
		steps.put(pos, torches.contains(pos) ? new BuildPlan.Step(pos, Blocks.TORCH.defaultBlockState()) : BuildPlan.Step.clear(pos));
	}

	/** What no bot may break: the floors of the stairs and halls, and the builder's torches. */
	public Set<BlockPos> protectedBlocks() {
		Set<BlockPos> blocks = new HashSet<>(this.torches());
		this.stairs().forEach(stair -> blocks.add(stair.below()));
		for (int level : this.levels) {
			this.hall(level).forEach(cell -> blocks.add(cell.below()));
		}
		return blocks;
	}

	/** The first round of branches off the hall at a mining level. */
	public List<Branch> branches(int level) {
		return this.branches(level, 0);
	}

	/** A round of branches at a mining level: round {@code n} carries on where round {@code n - 1} ends. */
	public List<Branch> branches(int level, int round) {
		List<Branch> branches = new ArrayList<>();
		List<BlockPos> hall = this.hall(level);
		Direction out = this.out(level);
		for (int k = 1; k <= this.branchesPerSide; k++) {
			BlockPos from = hall.get(k * BRANCH_SPACING - 1);
			for (Direction side : List.of(out.getClockWise(), out.getCounterClockWise())) {
				List<BlockPos> cells = new ArrayList<>();
				List<BlockPos> torches = new ArrayList<>();
				for (int m = round * this.branchLength + 1; m <= (round + 1) * this.branchLength; m++) {
					BlockPos cell = from.relative(side, m);
					cells.add(cell);
					cells.add(cell.above());
					// Halfway between torches, so the start is lit from the hall.
					if (m % this.torchSpacing == this.torchSpacing / 2) {
						torches.add(cell);
					}
				}
				branches.add(new Branch(cells.getFirst(), List.copyOf(cells), List.copyOf(torches)));
			}
		}
		return branches;
	}

	/** The branch a job points at, by its start. */
	public Optional<Branch> branch(BlockPos start) {
		return this.levels.stream().flatMap(level -> IntStream.range(0, this.rounds).boxed().flatMap(round -> this.branches(level, round).stream()))
			.filter(b -> b.start().equals(start)).findFirst();
	}

	/** The ores worth digging for at a height. */
	public static List<Item> ores(int y) {
		if (y >= 0) {
			return List.of(Items.RAW_IRON, Items.RAW_COPPER, Items.COAL);
		}
		return y >= -32 ? List.of(Items.RAW_GOLD, Items.LAPIS_LAZULI, Items.RAW_IRON) : List.of(Items.DIAMOND, Items.REDSTONE);
	}

	/** The deepest of {@code levels} whose ores are {@code wanted}, else the deepest. */
	public static Optional<Integer> chooseLevel(List<Integer> levels, Predicate<Item> wanted) {
		Comparator<Integer> deepest = Comparator.reverseOrder();
		return levels.stream().filter(y -> ores(y).stream().anyMatch(wanted)).max(deepest)
			.or(() -> levels.stream().max(deepest));
	}
}
