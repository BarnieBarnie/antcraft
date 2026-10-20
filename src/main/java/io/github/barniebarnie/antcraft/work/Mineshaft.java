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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
	/**
	 * How far apart mining levels must be. A branch digs its level and the block above, so a level
	 * 1 below or above another would have branches cutting into that level's hall.
	 */
	public static final int MIN_LEVEL_GAP = 2;
	/** How far from the core, at most, the colony looks for a site, and how far apart the spots it tries are. */
	private static final int SITE_RANGE = 18;
	private static final int SITE_STEP = 4;
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
	}

	/** Only levels below the top, deepest last, each at least {@link #MIN_LEVEL_GAP} below the one above it. */
	public Mineshaft {
		top = top.immutable();
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

	public Mineshaft(BlockPos top, List<Integer> levels, int branchesPerSide, int branchLength, int torchSpacing) {
		this(top, levels, branchesPerSide, branchLength, torchSpacing, DEFAULT_ROUNDS);
	}

	public Mineshaft(BlockPos top, List<Integer> levels) {
		this(top, levels, DEFAULT_BRANCHES_PER_SIDE, DEFAULT_BRANCH_LENGTH, DEFAULT_TORCH_SPACING);
	}

	/**
	 * The site the colony picks by itself: on a side of the core clear of the {@code avoid} areas
	 * (field, storage), where the column runs into no fluid or unbreakable block such as bedrock down
	 * to the first level. Deeper down the builder walls fluid off. Failing such a spot, the first one
	 * clear of the areas. Levels the column cannot be dug down to for bedrock are left out.
	 */
	public static Mineshaft nextTo(BlockPos core, List<Integer> levels, List<BoundingBox> avoid, WorldView world) {
		List<Mineshaft> spots = candidates(core).stream()
			.map(top -> new Mineshaft(top, levels))
			.filter(shaft -> avoid.stream().noneMatch(shaft.area()::intersects))
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
			// The column is 2 wide from its top's corner, so a ring's west and north sides are a block further out.
			int near = -r - 1;
			spots.addAll(List.of(core.offset(r, 0, r), core.offset(near, 0, near), core.offset(r, 0, near), core.offset(near, 0, r)));
			for (int t = near + SITE_STEP; t <= r - SITE_STEP; t += SITE_STEP) {
				spots.addAll(List.of(core.offset(r, 0, t), core.offset(near, 0, t), core.offset(t, 0, r), core.offset(t, 0, near)));
			}
		}
		return spots;
	}

	/** Whether the column runs into no fluid or unbreakable block from the ground down to the first level. */
	private boolean isDiggable(WorldView world) {
		int bottom = this.levels.isEmpty() ? this.top.getY() : this.levels.getFirst();
		return this.column(bottom).noneMatch(pos -> world.isHazard(pos) || world.breakTicks(pos) < 0);
	}

	/** This shaft without the levels at or below an unbreakable block in its column. */
	private Mineshaft aboveBedrock(WorldView world) {
		int bottom = this.levels.isEmpty() ? this.top.getY() : this.levels.getLast();
		int bedrock = this.column(bottom).filter(pos -> world.breakTicks(pos) < 0).mapToInt(BlockPos::getY).max().orElse(Integer.MIN_VALUE);
		return this.withLevels(this.levels.stream().filter(y -> y > bedrock).toList());
	}

	/** The blocks of the column the stairs are dug from, from just below the ground down to {@code bottom}. */
	private Stream<BlockPos> column(int bottom) {
		return IntStream.rangeClosed(bottom, this.top.getY() - 1).boxed()
			.flatMap(y -> CYCLE.stream().map(cell -> this.top.offset(cell).atY(y)));
	}

	private Mineshaft withLevels(List<Integer> levels) {
		return new Mineshaft(this.top, levels, this.branchesPerSide, this.branchLength, this.torchSpacing, this.rounds);
	}

	/**
	 * This shaft without the levels a builder cannot reach for the {@code blocked} steps of its plan:
	 * a blocked step on the stairs down to a level drops that level and all below it, one in a
	 * level's hall drops only that level.
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
			// Each level has its stairs batch, then its hall batch; see plan().
			int batch = IntStream.range(0, plan.batches().size()).filter(b -> plan.batches().get(b).to() > step).findFirst().orElseThrow();
			int level = batch / 2;
			if (level >= this.levels.size()) {
				continue;
			}
			if (batch % 2 == 0) {
				reached = Math.min(reached, level);
			} else {
				halls.add(level);
			}
		}
		return this.withLevels(IntStream.range(0, reached).filter(k -> !halls.contains(k)).mapToObj(this.levels::get).toList());
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

	/**
	 * Digs the stairs from the top down, each landing's hall as it is reached, with torches. Each
	 * stretch of stairs down to a landing is a batch, and so is each hall with its chest: once the
	 * stairs reach a landing, one builder can dig its hall while another digs on down. Before a block
	 * is dug, the walls, floor and ceiling around it below ground are made solid where they are not,
	 * so a cave is bridged and walled off and a fluid is held back, also past each hall's end.
	 * Branches and the landing chest are left alone.
	 */
	// ponytail: one batch per hall; split it per branch pair if a level ever needs more hands.
	public BuildPlan plan() {
		Map<BlockPos, BuildPlan.Step> steps = new LinkedHashMap<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		// The stairs batch before the one being dug, if any.
		List<Integer> above = List.of();
		Set<BlockPos> torches = Set.copyOf(this.torches());
		Set<BlockPos> open = this.unwalled();
		List<BlockPos> stairs = this.stairs();
		for (int i = 0; i < stairs.size(); i++) {
			BlockPos stair = stairs.get(i);
			// From the top, so the builder can reach each block from the stair above.
			for (int up = 2; up >= 0; up--) {
				this.dig(steps, stair.above(up), torches, open);
			}
			if (i > 0 && this.levels.contains(stair.getY())) {
				above = this.batch(batches, steps.size(), above);
				for (BlockPos cell : this.hall(stair.getY())) {
					this.dig(steps, cell.above(), torches, open);
					this.dig(steps, cell, torches, open);
				}
				// Facing the landing, where miners and haulers use it.
				BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, this.chestSide(stair.getY()).getOpposite());
				steps.put(this.landingChest(stair.getY()), new BuildPlan.Step(this.landingChest(stair.getY()), chest));
				this.batch(batches, steps.size(), above);
			}
		}
		if (batches.isEmpty() || batches.getLast().to() < steps.size()) {
			this.batch(batches, steps.size(), above);
		}
		return new BuildPlan(List.copyOf(steps.values()), batches);
	}

	/**
	 * Whether {@code plan} is this shaft's {@link #plan()} as an older version made it, such as one
	 * saved before the walls: batch by batch the same steps to dig, light and place the chest, with
	 * or without the steps any solid block will do.
	 */
	public boolean isPlan(BuildPlan plan) {
		BuildPlan current = this.plan();
		return plan.equals(current) || plan.batches().size() == current.batches().size()
			&& IntStream.range(0, current.batches().size()).allMatch(i -> withoutWalls(plan.batch(i)).equals(withoutWalls(current.batch(i))));
	}

	private static List<BuildPlan.Step> withoutWalls(BuildPlan plan) {
		return plan.steps().stream().filter(step -> !step.anySolid()).toList();
	}

	/** Ends a batch at step {@code to}, needing the batches {@code needs}; returns it as what the next batch may need. */
	private List<Integer> batch(List<BuildPlan.Batch> batches, int to, List<Integer> needs) {
		batches.add(new BuildPlan.Batch(batches.isEmpty() ? 0 : batches.getLast().to(), to, needs));
		return List.of(batches.size() - 1);
	}

	/** Keeps the blocks around {@code pos} that are not {@code open} solid, below ground, then digs it out or puts its torch there. */
	private void dig(Map<BlockPos, BuildPlan.Step> steps, BlockPos pos, Set<BlockPos> torches, Set<BlockPos> open) {
		for (Direction side : Direction.values()) {
			BlockPos wall = pos.relative(side);
			if (wall.getY() < this.top.getY() && !open.contains(wall) && !steps.containsKey(wall)) {
				steps.put(wall, BuildPlan.Step.solid(wall));
			}
		}
		steps.put(pos, torches.contains(pos) ? new BuildPlan.Step(pos, Blocks.TORCH.defaultBlockState()) : BuildPlan.Step.clear(pos));
	}

	/** What the plan never walls in: what it digs out, the landing chests and the branches. */
	private Set<BlockPos> unwalled() {
		Set<BlockPos> open = new HashSet<>();
		this.stairs().forEach(stair -> IntStream.range(0, 3).forEach(up -> open.add(stair.above(up))));
		for (int level : this.levels) {
			List<BlockPos> hall = this.hall(level);
			hall.forEach(cell -> open.addAll(List.of(cell, cell.above())));
			open.add(this.landingChest(level));
			this.branches(level).forEach(branch -> open.addAll(branch.cells()));
		}
		return open;
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

	/** What digging a branch yields: the stone it is dug through and the ores of every level. */
	private static final Set<Item> YIELDS = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
		Items.TUFF, Items.COAL, Items.RAW_COPPER, Items.RAW_IRON, Items.LAPIS_LAZULI, Items.RAW_GOLD, Items.REDSTONE, Items.DIAMOND, Items.EMERALD);

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
