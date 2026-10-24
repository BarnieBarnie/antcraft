package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.barniebarnie.antcraft.pathing.WorldView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * The colony mine, from {@code top} at ground level down to a landing at each mining level, in one of
 * three {@link Layout}s. A new shaft is a {@link Layout#LADDER} shaft: a 1x1 column under {@code top}
 * with a ladder on its south wall at every height, entered through a hatch in the ground, with a small
 * chamber beside the column at each level that holds the landing chest and a torch. Colonies that dug
 * a {@link Layout#SPIRAL} keep it: a 2-wide staircase that spirals round a solid 2x2 column, 6x6 in
 * all, {@code top} its north-west corner, where each height is a flat 2x2 corner with the rows either
 * side of it and each side has one 2-wide step down, onto a stair block. Its corners are its landings,
 * each with a chest. An old {@link Layout#STAIRCASE} shaft is a 1-wide staircase round a 2x2 column, a
 * stair per height. From each landing a hall leads away from the column, and branches run off both
 * sides of the hall every {@link #BRANCH_SPACING} blocks. Builders dig the column or stairs (and an old
 * shaft's halls); miners dig the other layouts' halls and the branches. Once every branch at a level is
 * dug, the next round of branches carries each one further out, up to {@code rounds} rounds, so a level
 * keeps yielding ore without the hall growing. Every step of the stairs has three blocks of headroom,
 * so any bot can walk up and down without scaffold; bots climb the ladder shaft, and pass each other
 * on it, as they do not push each other.
 */
public record Mineshaft(BlockPos top, List<Integer> levels, int branchesPerSide, int branchLength, int torchSpacing, int rounds, Layout layout) {
	public static final int BRANCH_SPACING = 3;
	public static final int DEFAULT_BRANCHES_PER_SIDE = 4;
	public static final int DEFAULT_BRANCH_LENGTH = 16;
	public static final int DEFAULT_TORCH_SPACING = 8;
	public static final int DEFAULT_ROUNDS = 4;
	/** How many blocks of a branch or hall one job digs (see {@link Branch#segments}). */
	public static final int SEGMENT_LENGTH = 4;
	/**
	 * How far apart mining levels must be. A branch digs its level and the block above, so a level
	 * 1 below or above another would have branches cutting into that level's hall.
	 */
	public static final int MIN_LEVEL_GAP = 2;
	/** How far from the core, at most, the colony looks for a site, and how far apart the spots it tries are. */
	private static final int SITE_RANGE = 18;
	private static final int SITE_STEP = 4;
	/** The stair cells of an old shaft's column, in the order the staircase winds down. */
	private static final List<BlockPos> CYCLE = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(1, 0, 1), new BlockPos(0, 0, 1));
	/** How wide a spiral shaft is: a 2-wide staircase either side of its 2x2 column. */
	private static final int SPIRAL_SIZE = 6;
	/** The way the ladders of a ladder shaft face, and its hatch: away from the south wall they hang on. */
	private static final Direction LADDER_FACING = Direction.NORTH;

	/** How a shaft gets down to its levels (see {@link Mineshaft}). */
	public enum Layout implements StringRepresentable {
		/** The old 1-wide staircase round a 2x2 column, which colonies that dug one keep. */
		STAIRCASE,
		/** The 2-wide staircase round a 2x2 column, 6x6 in all, which colonies that dug one keep. */
		SPIRAL,
		/** A 1x1 column of ladders with a landing chamber at each level: what new shafts are. */
		LADDER;

		public static final Codec<Layout> CODEC = StringRepresentable.fromEnum(Layout::values);

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * A height of the spiral, one corner further round than the height above it: the {@code cells}
	 * there, from the row the stairs come in by, through the corner, to the row they leave by. That
	 * last row is the step up from the next height down, so its floor is a stair block facing
	 * {@code up}. The corner is the landing, its {@code landing} cell on the outer face the hall
	 * leaves by, and its torch goes on the {@code light} cell, by the centre column.
	 */
	private record Turn(List<BlockPos> cells, List<BlockPos> stairs, Direction up, BlockPos landing, BlockPos light) {
	}

	private static final List<Turn> TURNS = List.of(
		new Turn(cells(0, 2, 1, 2, 0, 0, 1, 0, 0, 1, 1, 1, 2, 0, 2, 1), cells(2, 0, 2, 1), Direction.WEST, new BlockPos(0, 0, 0), new BlockPos(1, 0, 1)),
		new Turn(cells(3, 0, 3, 1, 4, 0, 5, 0, 4, 1, 5, 1, 4, 2, 5, 2), cells(4, 2, 5, 2), Direction.NORTH, new BlockPos(5, 0, 0), new BlockPos(4, 0, 1)),
		new Turn(cells(4, 3, 5, 3, 4, 4, 5, 4, 4, 5, 5, 5, 3, 4, 3, 5), cells(3, 4, 3, 5), Direction.EAST, new BlockPos(5, 0, 5), new BlockPos(4, 0, 4)),
		new Turn(cells(2, 4, 2, 5, 0, 4, 1, 4, 0, 5, 1, 5, 0, 3, 1, 3), cells(0, 3, 1, 3), Direction.SOUTH, new BlockPos(0, 0, 5), new BlockPos(1, 0, 4)));

	private static List<BlockPos> cells(int... xz) {
		return IntStream.range(0, xz.length / 2).mapToObj(i -> new BlockPos(xz[2 * i], 0, xz[2 * i + 1])).toList();
	}

	/**
	 * Saves from before the ladder shaft have no layout but say whether they are a spiral; those from before the
	 * spiral are of old shafts. Either keeps its layout.
	 */
	public static final Codec<Mineshaft> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.fieldOf("top").forGetter(Mineshaft::top),
		Codec.INT.listOf().fieldOf("levels").forGetter(Mineshaft::levels),
		Codec.INT.optionalFieldOf("branches_per_side", DEFAULT_BRANCHES_PER_SIDE).forGetter(Mineshaft::branchesPerSide),
		Codec.INT.optionalFieldOf("branch_length", DEFAULT_BRANCH_LENGTH).forGetter(Mineshaft::branchLength),
		Codec.INT.optionalFieldOf("torch_spacing", DEFAULT_TORCH_SPACING).forGetter(Mineshaft::torchSpacing),
		Codec.INT.optionalFieldOf("rounds", DEFAULT_ROUNDS).forGetter(Mineshaft::rounds),
		// Only a ladder shaft says, so the older layouts save as they did and older versions of the mod still read them.
		Layout.CODEC.optionalFieldOf("layout").forGetter(shaft -> Optional.of(shaft.layout()).filter(layout -> layout == Layout.LADDER)),
		Codec.BOOL.optionalFieldOf("spiral", false).forGetter(shaft -> shaft.layout() == Layout.SPIRAL)
	).apply(i, (top, levels, perSide, length, spacing, rounds, layout, spiral) ->
		new Mineshaft(top, levels, perSide, length, spacing, rounds, layout.orElse(spiral ? Layout.SPIRAL : Layout.STAIRCASE))));

	/** A tunnel a miner digs, 2 high and 1 wide; {@code cells} run from the hall outwards. */
	public record Branch(BlockPos start, List<BlockPos> cells, List<BlockPos> torches) {
		/** The first torch spot that is dug out but has no torch yet. */
		public Optional<BlockPos> darkTorch(LevelReader level) {
			BlockState torch = Blocks.TORCH.defaultBlockState();
			return this.torches.stream().filter(pos -> level.getBlockState(pos).isAir() && torch.canSurvive(level, pos)).findFirst();
		}

		/**
		 * The walls and ceiling of what is dug of it, where a miner takes ores from: the blocks beside
		 * and above its cells that are air, from the hall outwards. Not below the floor, which would
		 * leave holes to trip over.
		 */
		public Set<BlockPos> walls(Function<BlockPos, BlockState> world) {
			Set<BlockPos> walls = new LinkedHashSet<>();
			for (BlockPos cell : this.cells) {
				if (!world.apply(cell).isAir()) {
					continue;
				}
				for (Direction dir : Direction.values()) {
					BlockPos side = cell.relative(dir);
					if (dir != Direction.DOWN && !this.cells.contains(side)) {
						walls.add(side);
					}
				}
			}
			return walls;
		}

		/**
		 * This branch in stretches of {@link #SEGMENT_LENGTH} blocks from the hall outwards, each with its torches:
		 * a job each, so several bots dig a long hall one stretch after the other.
		 */
		public List<Branch> segments() {
			// Each block of the branch is a cell and the one above it.
			int size = 2 * SEGMENT_LENGTH;
			List<Branch> segments = new ArrayList<>();
			for (int i = 0; i < this.cells.size(); i += size) {
				List<BlockPos> cells = List.copyOf(this.cells.subList(i, Math.min(i + size, this.cells.size())));
				segments.add(new Branch(cells.getFirst(), cells, this.torches.stream().filter(cells::contains).toList()));
			}
			return segments;
		}
	}

	/**
	 * Only levels below the top, deepest last, each at least {@link #MIN_LEVEL_GAP} below the one above it.
	 * At least one branch a side, each at least one block long: a shaft without them has no hall and nothing to mine.
	 */
	public Mineshaft {
		top = top.immutable();
		branchesPerSide = Math.max(1, branchesPerSide);
		branchLength = Math.max(1, branchLength);
		int topY = top.getY();
		levels = spaced(levels.stream().filter(y -> y < topY).toList());
	}

	/**
	 * {@code levels} deepest last, without those closer than {@link #MIN_LEVEL_GAP} below a level
	 * kept above them.
	 */
	public static List<Integer> spaced(List<Integer> levels) {
		List<Integer> spaced = new ArrayList<>();
		for (int y : levels.stream().distinct().sorted(Comparator.reverseOrder()).toList()) {
			if (spaced.isEmpty() || spaced.getLast() - y >= MIN_LEVEL_GAP) {
				spaced.add(y);
			}
		}
		return List.copyOf(spaced);
	}

	/** A new shaft: the ladder shaft. */
	public Mineshaft(BlockPos top, List<Integer> levels, int branchesPerSide, int branchLength, int torchSpacing, int rounds) {
		this(top, levels, branchesPerSide, branchLength, torchSpacing, rounds, Layout.LADDER);
	}

	public Mineshaft(BlockPos top, List<Integer> levels, int branchesPerSide, int branchLength, int torchSpacing) {
		this(top, levels, branchesPerSide, branchLength, torchSpacing, DEFAULT_ROUNDS);
	}

	public Mineshaft(BlockPos top, List<Integer> levels) {
		this(top, levels, DEFAULT_BRANCHES_PER_SIDE, DEFAULT_BRANCH_LENGTH, DEFAULT_TORCH_SPACING);
	}

	/**
	 * The site the colony picks by itself, for a new shaft: on a side of the core clear of the
	 * {@code avoid} areas (field, storage), where the column runs into no fluid or bedrock down
	 * to the first level, and no hall does. Deeper down the builder walls fluid off. Failing such a
	 * spot, the first one clear of the areas. Levels the column cannot be dug down to for bedrock are
	 * left out. Other blocks that cannot be broken, such as barriers, do not move the site.
	 */
	public static Mineshaft nextTo(BlockPos core, List<Integer> levels, List<BoundingBox> avoid, WorldView world) {
		List<Mineshaft> spots = candidates(core).stream()
			.map(top -> new Mineshaft(top, levels))
			.filter(shaft -> avoid.stream().noneMatch(area -> Sites.sharesColumns(shaft.area(), area)))
			.toList();
		Mineshaft site = spots.stream().filter(shaft -> shaft.isDiggable(world)).findFirst()
			.orElseGet(() -> spots.isEmpty() ? new Mineshaft(core.offset(2, 0, 2), levels) : spots.getFirst());
		return site.aboveBedrock(world);
	}

	/**
	 * Where the colony may put the shaft's top, nearest first: rings of spots around the core, the
	 * four corners of each ring before the spots along its sides. The innermost corners leave a block
	 * between the column and the core.
	 */
	static List<BlockPos> candidates(BlockPos core) {
		List<BlockPos> spots = new ArrayList<>();
		for (int r = 2; r <= SITE_RANGE; r += SITE_STEP) {
			// A new shaft's area reaches a block past its top each way, so the top of a spot is a block further out than the ring.
			int far = r + 1;
			int near = -r - 1;
			spots.addAll(List.of(core.offset(far, 0, far), core.offset(near, 0, near), core.offset(far, 0, near), core.offset(near, 0, far)));
			for (int t = near + SITE_STEP; t <= far - SITE_STEP; t += SITE_STEP) {
				spots.addAll(List.of(core.offset(far, 0, t), core.offset(near, 0, t), core.offset(t, 0, far), core.offset(t, 0, near)));
			}
		}
		return spots;
	}

	/**
	 * Whether the column runs into no fluid or bedrock from the ground down to the first level, and
	 * no hall does: miners dig those without walling fluid off.
	 */
	private boolean isDiggable(WorldView world) {
		int bottom = this.levels.isEmpty() ? this.top.getY() : this.levels.getFirst();
		Stream<BlockPos> halls = this.levels.stream().flatMap(y -> this.hall(y).stream()).flatMap(cell -> Stream.of(cell, cell.above()));
		return Stream.concat(this.column(bottom), halls).noneMatch(pos -> world.isHazard(pos) || world.isBedrock(pos));
	}

	/** This shaft without the levels at or below bedrock in its column. */
	private Mineshaft aboveBedrock(WorldView world) {
		int bottom = this.levels.isEmpty() ? this.top.getY() : this.levels.getLast();
		int bedrock = this.column(bottom).filter(world::isBedrock).mapToInt(BlockPos::getY).max().orElse(Integer.MIN_VALUE);
		return this.withLevels(this.levels.stream().filter(y -> y > bedrock).toList());
	}

	/**
	 * The blocks of the column the stairs are dug from, from just below the ground down to {@code bottom}; of a ladder
	 * shaft, its column with the walls round it.
	 */
	private Stream<BlockPos> column(int bottom) {
		int size = this.size();
		BlockPos corner = this.corner();
		return IntStream.rangeClosed(bottom, this.top.getY() - 1).boxed()
			.flatMap(y -> IntStream.range(0, size * size).mapToObj(i -> corner.offset(i % size, 0, i / size).atY(y)));
	}

	/** How wide the column is, with a ladder shaft's walls. */
	private int size() {
		return switch (this.layout) {
			case STAIRCASE -> 2;
			case SPIRAL -> SPIRAL_SIZE;
			case LADDER -> 3;
		};
	}

	/** The north-west corner of the column: the top, but for a ladder shaft, whose walls are round it. */
	private BlockPos corner() {
		return this.layout == Layout.LADDER ? this.top.offset(-1, 0, -1) : this.top;
	}

	private Mineshaft withLevels(List<Integer> levels) {
		return new Mineshaft(this.top, levels, this.branchesPerSide, this.branchLength, this.torchSpacing, this.rounds, this.layout);
	}

	/**
	 * This shaft without the levels a builder cannot reach for the {@code blocked} steps of its plan:
	 * a blocked step on the stairs down to a level drops that level and all below it, one in an old
	 * shaft's hall drops only that level.
	 */
	public Mineshaft reachable(Collection<BlockPos> blocked) {
		BuildPlan plan = this.plan();
		Map<BlockPos, Integer> index = new HashMap<>();
		for (int i = 0; i < plan.steps().size(); i++) {
			index.put(plan.steps().get(i).pos(), i);
		}
		int reached = this.levels.size();
		Set<Integer> halls = new HashSet<>();
		for (BlockPos pos : blocked) {
			Integer step = index.get(pos);
			if (step == null) {
				continue;
			}
			// Each level has its stairs batch, then, in an old shaft, its hall batch; see plan().
			int batch = IntStream.range(0, plan.batches().size()).filter(b -> plan.batches().get(b).to() > step).findFirst().orElseThrow();
			int perLevel = this.layout == Layout.STAIRCASE ? 2 : 1;
			int level = batch / perLevel;
			if (level >= this.levels.size()) {
				continue;
			}
			if (batch % perLevel == 0) {
				reached = Math.min(reached, level);
			} else {
				halls.add(level);
			}
		}
		return this.withLevels(IntStream.range(0, reached).filter(k -> !halls.contains(k)).mapToObj(this.levels::get).toList());
	}

	/** The column the stairs wind down, or the ladder shaft with its walls and chambers, from the bottom floor to the top of the entrance. */
	public BoundingBox area() {
		int bottom = this.levels.isEmpty() ? this.top.getY() : this.levels.getLast();
		BlockPos corner = this.corner();
		int far = this.size() - 1;
		return new BoundingBox(corner.getX(), bottom - 1, corner.getZ(), corner.getX() + far, this.top.getY() + 2, corner.getZ() + far);
	}

	/** How many heights the stairs go down below the top. */
	private int depth() {
		return this.levels.isEmpty() ? 0 : this.top.getY() - this.levels.getLast();
	}

	/**
	 * Feet positions of the steps, per height, top first: one stair of an old shaft, or a corner of
	 * the spiral with the rows either side. The deepest height has no row on to a step below it.
	 * Of a ladder shaft, the cells of its column, from the hatch down.
	 */
	private List<List<BlockPos>> heights() {
		List<List<BlockPos>> heights = new ArrayList<>();
		if (this.layout == Layout.LADDER) {
			IntStream.rangeClosed(1, this.depth()).forEach(k -> heights.add(List.of(this.top.below(k))));
			return heights;
		}
		for (int k = 0; k <= this.depth(); k++) {
			if (this.layout == Layout.STAIRCASE) {
				heights.add(List.of(this.top.offset(CYCLE.get(k % CYCLE.size())).below(k)));
				continue;
			}
			Turn turn = TURNS.get(k % TURNS.size());
			boolean deepest = k == this.depth();
			int down = k;
			heights.add(turn.cells().stream().filter(cell -> !deepest || !turn.stairs().contains(cell)).map(cell -> this.top.offset(cell).below(down)).toList());
		}
		return heights;
	}

	/** Feet positions of the steps, top first; of a ladder shaft, the cells bots climb, the hatch first. */
	public List<BlockPos> stairs() {
		return this.heights().stream().flatMap(List::stream).toList();
	}

	/** The stair blocks of the spiral, by position: the floor of each row a step up leads onto, facing up. */
	private Map<BlockPos, BlockState> stairBlocks() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		if (this.layout == Layout.SPIRAL) {
			for (int k = 0; k < this.depth(); k++) {
				Turn turn = TURNS.get(k % TURNS.size());
				BlockState stair = Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, turn.up());
				for (BlockPos cell : turn.stairs()) {
					blocks.put(this.top.offset(cell).below(k + 1), stair);
				}
			}
		}
		return blocks;
	}

	/**
	 * The step at a mining level that its hall leaves from: an old shaft's stair, the spiral's corner cell on the outer
	 * face, or the ladder shaft's chamber beside the column, east of it at the first level and every other one after,
	 * west at the others, so a chamber is never right under the one above.
	 */
	public BlockPos landing(int level) {
		int k = this.top.getY() - level;
		return switch (this.layout) {
			case STAIRCASE -> this.stairs().get(k);
			case SPIRAL -> this.top.offset(TURNS.get(k % TURNS.size()).landing()).below(k);
			case LADDER -> this.top.atY(level).relative(this.levels.indexOf(level) % 2 == 0 ? Direction.EAST : Direction.WEST);
		};
	}

	/**
	 * Where the landing chest of a mining level goes: beside the landing, out of the column on the
	 * side opposite the hall's, so it is clear of the stairs, the hall and the branches.
	 */
	public BlockPos landingChest(int level) {
		return this.landing(level).relative(this.chestSide(level));
	}

	private Direction chestSide(int level) {
		return this.layout == Layout.LADDER || this.landing(level).getZ() == this.top.getZ() ? Direction.NORTH : Direction.SOUTH;
	}

	/** Away from the column, on the side the landing's stair, or the ladder shaft's chamber, is on. */
	private Direction out(int level) {
		if (this.layout == Layout.LADDER) {
			return this.landing(level).getX() > this.top.getX() ? Direction.EAST : Direction.WEST;
		}
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

	/**
	 * Where the builder puts torches: every few stairs and at each landing, and in an old shaft every
	 * few blocks of each hall. In the spiral they go by the centre column of the corners.
	 */
	public List<BlockPos> torches() {
		Set<BlockPos> torches = new LinkedHashSet<>();
		if (this.layout == Layout.LADDER) {
			// One in each landing chamber; the column has no floor for one.
			return this.levels.stream().map(this::landing).toList();
		}
		if (this.layout == Layout.SPIRAL) {
			// A height of the spiral is about 4 stairs of an old shaft long.
			int every = Math.max(1, this.torchSpacing / TURNS.size());
			for (int k = 0; k <= this.depth(); k++) {
				if (k % every == 0 || k > 0 && this.levels.contains(this.top.getY() - k)) {
					torches.add(this.top.offset(TURNS.get(k % TURNS.size()).light()).below(k));
				}
			}
			return List.copyOf(torches);
		}
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

	/**
	 * Digs the stairs from the top down, with torches, and in the spiral puts a stair block on each
	 * step up. Each stretch of stairs down to a landing is a batch, with the landing's chest. An old
	 * shaft's plan digs each landing's hall as it is reached too, a batch of its own with the chest:
	 * once the stairs reach a landing, one builder can dig its hall while another digs on down. In
	 * the spiral, halls are mining work (see {@link #hallWork}). Before a block is dug, the walls,
	 * floor and ceiling around it below ground are made solid where they are not, so a cave is
	 * bridged and walled off and a fluid is held back, also past each old hall's end. Halls,
	 * branches and the landing chest are left alone.
	 */
	// ponytail: one batch per hall; split it per branch pair if a level ever needs more hands.
	public BuildPlan plan() {
		if (this.layout == Layout.LADDER) {
			return this.ladderPlan();
		}
		Map<BlockPos, BuildPlan.Step> steps = new LinkedHashMap<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		// The stairs batch before the one being dug, if any.
		List<Integer> above = List.of();
		Set<BlockPos> torches = Set.copyOf(this.torches());
		Set<BlockPos> open = this.unwalled();
		Map<BlockPos, BlockState> stairBlocks = this.stairBlocks();
		List<List<BlockPos>> heights = this.heights();
		for (int i = 0; i < heights.size(); i++) {
			for (BlockPos stair : heights.get(i)) {
				// From the top, so the builder can reach each block from the stair above.
				for (int up = 2; up >= 0; up--) {
					this.dig(steps, stair.above(up), torches, open);
				}
				// Once the step is dug, so the builder stands beside it; any stone stairs the colony has will do.
				BlockState floor = stairBlocks.get(stair.below());
				if (floor != null) {
					this.place(steps, BuildPlan.Step.stoneStairs(stair.below(), floor), open);
				}
			}
			int y = heights.get(i).getFirst().getY();
			if (i == 0 || !this.levels.contains(y)) {
				continue;
			}
			if (this.layout == Layout.SPIRAL) {
				steps.put(this.landingChest(y), new BuildPlan.Step(this.landingChest(y), this.chest(y)));
				above = this.batch(batches, steps.size(), above);
				continue;
			}
			above = this.batch(batches, steps.size(), above);
			for (BlockPos cell : this.hall(y)) {
				this.dig(steps, cell.above(), torches, open);
				this.dig(steps, cell, torches, open);
			}
			steps.put(this.landingChest(y), new BuildPlan.Step(this.landingChest(y), this.chest(y)));
			this.batch(batches, steps.size(), above);
		}
		if (batches.isEmpty() || batches.getLast().to() < steps.size()) {
			this.batch(batches, steps.size(), above);
		}
		return new BuildPlan(List.copyOf(steps.values()), batches);
	}

	/**
	 * Digs the ladder shaft's column from the top down, a ladder at every height, and at each level its chamber with a
	 * torch and the landing chest; the walls round each cell are made solid first, as in {@link #plan()}, so each
	 * ladder has a block behind it and no fluid comes in. Each stretch down to a level is a batch. The first one clears
	 * the entrance, and puts the hatch in once the top ladder is up: the builder digs its way down through the ground
	 * the hatch goes in, as a shut hatch over no ladder is no way in, and climbs out through the hatch, as a bot cannot
	 * climb off the top of a ladder into the open.
	 */
	private BuildPlan ladderPlan() {
		Map<BlockPos, BuildPlan.Step> steps = new LinkedHashMap<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		List<Integer> above = List.of();
		Set<BlockPos> torches = Set.copyOf(this.torches());
		Set<BlockPos> open = this.unwalled();
		this.dig(steps, this.top.above(), torches, open);
		this.dig(steps, this.top, torches, open);
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, LADDER_FACING);
		int y = this.hatch().getY() - 1;
		for (int level : this.levels) {
			for (; y >= level; y--) {
				this.place(steps, this.top.atY(y), ladder, open);
				if (y == this.hatch().getY() - 1) {
					this.place(steps, this.hatch(), Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, LADDER_FACING)
						.setValue(TrapDoorBlock.HALF, Half.TOP), open);
				}
			}
			BlockPos landing = this.landing(level);
			this.dig(steps, landing.above(), torches, open);
			this.dig(steps, landing, torches, open);
			steps.put(this.landingChest(level), new BuildPlan.Step(this.landingChest(level), this.chest(level)));
			above = this.batch(batches, steps.size(), above);
		}
		if (batches.isEmpty()) {
			this.batch(batches, steps.size(), above);
		}
		return new BuildPlan(List.copyOf(steps.values()), batches);
	}

	/** Where a ladder shaft's hatch goes: in the ground, over the top ladder. */
	private BlockPos hatch() {
		return this.top.below();
	}

	/**
	 * Whether {@code plan} is this shaft's {@link #plan()} as an older version made it, such as one
	 * saved before the walls: batch by batch the same steps to dig, light and place the chest, with
	 * or without the steps any solid block will do, and with stairs of cobblestone only or of any stone.
	 */
	public boolean isPlan(BuildPlan plan) {
		BuildPlan current = this.plan();
		return plan.equals(current) || plan.batches().size() == current.batches().size()
			&& IntStream.range(0, current.batches().size()).allMatch(i -> withoutWalls(plan.batch(i)).equals(withoutWalls(current.batch(i))));
	}

	private static List<BuildPlan.Step> withoutWalls(BuildPlan plan) {
		return plan.steps().stream().filter(step -> !step.anySolid()).map(step -> new BuildPlan.Step(step.pos(), step.state())).toList();
	}

	/** Ends a batch at step {@code to}, needing the batches {@code needs}; returns it as what the next batch may need. */
	private List<Integer> batch(List<BuildPlan.Batch> batches, int to, List<Integer> needs) {
		batches.add(new BuildPlan.Batch(batches.isEmpty() ? 0 : batches.getLast().to(), to, needs));
		return List.of(batches.size() - 1);
	}

	/** The landing chest at a level, facing the landing, where miners and haulers use it. */
	private BlockState chest(int level) {
		return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, this.chestSide(level).getOpposite());
	}

	/** Keeps the blocks around {@code pos} that are not {@code open} solid, below ground, then digs it out or puts its torch there. */
	private void dig(Map<BlockPos, BuildPlan.Step> steps, BlockPos pos, Set<BlockPos> torches, Set<BlockPos> open) {
		this.place(steps, pos, torches.contains(pos) ? Blocks.TORCH.defaultBlockState() : Blocks.AIR.defaultBlockState(), open);
	}

	/** Keeps the blocks around {@code pos} that are not {@code open} solid, below ground, then puts {@code state} there, or digs it out for air. */
	private void place(Map<BlockPos, BuildPlan.Step> steps, BlockPos pos, BlockState state, Set<BlockPos> open) {
		this.place(steps, new BuildPlan.Step(pos, state), open);
	}

	private void place(Map<BlockPos, BuildPlan.Step> steps, BuildPlan.Step step, Set<BlockPos> open) {
		for (Direction side : Direction.values()) {
			BlockPos wall = step.pos().relative(side);
			if (wall.getY() < this.top.getY() && !open.contains(wall) && !steps.containsKey(wall)) {
				steps.put(wall, BuildPlan.Step.solid(wall));
			}
		}
		steps.put(step.pos(), step);
	}

	/**
	 * What the plan never walls in: what it digs out or puts stair blocks in, the ladder shaft's column, hatch and
	 * chambers, the halls, the landing chests and the branches.
	 */
	private Set<BlockPos> unwalled() {
		Set<BlockPos> open = new HashSet<>(this.stairBlocks().keySet());
		if (this.layout == Layout.LADDER) {
			open.addAll(this.stairs());
			open.addAll(List.of(this.hatch(), this.top, this.top.above()));
			this.levels.forEach(level -> open.addAll(List.of(this.landing(level), this.landing(level).above())));
		} else {
			this.stairs().forEach(stair -> IntStream.range(0, 3).forEach(up -> open.add(stair.above(up))));
		}
		for (int level : this.levels) {
			List<BlockPos> hall = this.hall(level);
			hall.forEach(cell -> open.addAll(List.of(cell, cell.above())));
			open.add(this.landingChest(level));
			this.branches(level).forEach(branch -> open.addAll(branch.cells()));
		}
		return open;
	}

	/** What no path may put a scaffold block in: what the plan never walls in, and every round of branches still to dig. */
	public Set<BlockPos> keepOpen() {
		Set<BlockPos> open = this.unwalled();
		for (int level : this.levels) {
			IntStream.range(1, this.rounds).forEach(round -> this.branches(level, round).forEach(branch -> open.addAll(branch.cells())));
		}
		return open;
	}

	/**
	 * What no bot may break: the floors of the stairs and halls, and the builder's torches; of a ladder shaft, the
	 * ladders, the walls behind them, the column's floor and the chamber floors, but not the ground the hatch goes in,
	 * which the builder digs through first, and which is protected once it is placed, like any block a plan places.
	 */
	public Set<BlockPos> protectedBlocks() {
		Set<BlockPos> blocks = new HashSet<>(this.torches());
		if (this.layout == Layout.LADDER) {
			List<BlockPos> column = this.stairs().stream().filter(cell -> !cell.equals(this.hatch())).toList();
			column.forEach(cell -> blocks.addAll(List.of(cell, cell.relative(LADDER_FACING.getOpposite()))));
			if (!column.isEmpty()) {
				blocks.add(column.getLast().below());
			}
			this.levels.forEach(level -> blocks.add(this.landing(level).below()));
		} else {
			this.stairs().forEach(stair -> blocks.add(stair.below()));
		}
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

	/**
	 * The hall at a mining level of the spiral or ladder shaft as mining work, like a branch, dug
	 * before the branches off it; none in an old shaft, whose plan digs its halls.
	 */
	public Optional<Branch> hallWork(int level) {
		if (this.layout == Layout.STAIRCASE) {
			return Optional.empty();
		}
		List<BlockPos> hall = this.hall(level);
		List<BlockPos> cells = hall.stream().flatMap(cell -> Stream.of(cell, cell.above())).toList();
		List<BlockPos> torches = IntStream.rangeClosed(1, hall.size()).filter(n -> n % this.torchSpacing == 0).mapToObj(n -> hall.get(n - 1)).toList();
		return Optional.of(new Branch(hall.getFirst(), cells, torches));
	}

	/** The branch, or hall, that starts at {@code start}. */
	public Optional<Branch> branch(BlockPos start) {
		return this.allBranches().filter(b -> b.start().equals(start)).findFirst();
	}

	/** The segment of a branch or hall a job points at, by its start (see {@link Branch#segments}). */
	public Optional<Branch> segment(BlockPos start) {
		return this.allBranches().flatMap(b -> b.segments().stream()).filter(s -> s.start().equals(start)).findFirst();
	}

	/** Whether the segment that starts at {@code start} can be dug: it is the first of its branch, or the one before it is {@code dug}. */
	public boolean segmentReady(BlockPos start, Predicate<BlockPos> dug) {
		return this.allBranches().map(Branch::segments).flatMap(segments -> IntStream.range(0, segments.size())
				.filter(i -> segments.get(i).start().equals(start))
				.mapToObj(i -> i == 0 || segments.get(i - 1).cells().stream().allMatch(dug)))
			.findFirst().orElse(false);
	}

	/** Every hall that is mining work and every round of branches, at every level. */
	private Stream<Branch> allBranches() {
		return this.levels.stream().flatMap(level -> Stream.concat(this.hallWork(level).stream(),
			IntStream.range(0, this.rounds).boxed().flatMap(round -> this.branches(level, round).stream())));
	}

	/** The ores worth digging for at a height. */
	public static List<Item> ores(int y) {
		if (y >= 0) {
			return List.of(Items.RAW_IRON, Items.RAW_COPPER, Items.COAL);
		}
		return y >= -32 ? List.of(Items.RAW_GOLD, Items.LAPIS_LAZULI, Items.RAW_IRON) : List.of(Items.DIAMOND, Items.REDSTONE);
	}

	/** What digging a branch yields: the stone it is dug through and the ores of every level. */
	private static final Set<Item> YIELDS = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
		Items.TUFF, Items.COAL, Items.RAW_COPPER, Items.RAW_IRON, Items.LAPIS_LAZULI, Items.RAW_GOLD, Items.REDSTONE, Items.DIAMOND, Items.EMERALD);

	/** Whether digging a branch or vein yields {@code item}. */
	public static boolean yields(Item item) {
		return YIELDS.contains(item);
	}

	/** Whether the colony wants anything mining yields, so the mine is worked at all. */
	public static boolean yieldsWanted(Predicate<Item> wanted) {
		return YIELDS.stream().anyMatch(wanted);
	}

	/**
	 * The branches to post of the levels with branches left to dig ({@code work}): those of the level
	 * {@link #chooseLevel} picks, or none while the colony wants nothing mining yields.
	 */
	public static List<Branch> branchesToPost(Map<Integer, List<Branch>> work, Predicate<Item> wanted) {
		if (!yieldsWanted(wanted)) {
			return List.of();
		}
		return chooseLevel(List.copyOf(work.keySet()), wanted).map(work::get).orElse(List.of());
	}

	/** The deepest of {@code levels} whose ores are {@code wanted}, else the deepest. */
	public static Optional<Integer> chooseLevel(List<Integer> levels, Predicate<Item> wanted) {
		Comparator<Integer> deepest = Comparator.reverseOrder();
		return levels.stream().filter(y -> ores(y).stream().anyMatch(wanted)).max(deepest)
			.or(() -> levels.stream().max(deepest));
	}
}
