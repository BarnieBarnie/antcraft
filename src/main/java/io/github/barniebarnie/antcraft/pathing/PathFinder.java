package io.github.barniebarnie.antcraft.pathing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * A* over block positions for a 1x2 bot. Positions are feet positions. Costs are
 * roughly in game ticks, so digging and placing compete fairly with walking around.
 */
public final class PathFinder {
	public static final double WALK_COST = 5.0;
	public static final double JUMP_COST = 2.0;
	public static final double FALL_COST_PER_BLOCK = 1.0;
	public static final double BREAK_OVERHEAD = 5.0;
	public static final double PLACE_COST = 20.0;
	/** Opening a door or fence gate on the way (see {@link WorldView#canOpen}). */
	public static final double OPEN_COST = 2.0;
	/**
	 * Swimming a block at the surface: slow, so routes keep to dry land unless water saves a long way round.
	 * Measured at about 33 ticks a block (see {@code PathingGameTest.swimCostMatchesTheTimeASwimTakes}).
	 */
	public static final double SWIM_COST = 33.0;
	/**
	 * How far a drop into water may fall, past {@link Options#maxDrop()}: water breaks the fall. Game-tested for the
	 * full drop into water a block deep ({@code PathingGameTest.dropsFarIntoWaterUnhurt}); the limit keeps bots from
	 * leaping off cliffs they cannot swim back up.
	 */
	public static final int MAX_WATER_DROP = 8;
	/**
	 * Swimming one block up a water column, such as a waterfall. Measured at about 6 ticks a block (see
	 * {@code PathingGameTest.swimUpCostMatchesTheTimeARiseTakes}), so a climb up a long waterfall is not mistaken for
	 * a shortcut.
	 */
	public static final double SWIM_UP_COST = 6.0;

	/** Climbing a block up or down a ladder: vanilla climbs at 0.2 blocks a tick and slides down at 0.15. */
	public static final double CLIMB_COST = 6.0;

	private static final double BLOCKED = Double.POSITIVE_INFINITY;

	public record Options(boolean canBreak, boolean canPlace, int maxNodes, int maxDrop) {
	}

	private record Node(BlockPos pos, double cost, Node parent, Step step) {
	}

	private record Entry(Node node, double priority) {
	}

	private PathFinder() {
	}

	/**
	 * Plans a path from {@code start} to {@code goal}. A path never ends hanging on a ladder, where a bot does not
	 * stand to work, nor in water: a bot swims only to get somewhere dry, and a partial path stops on the nearest shore
	 * rather than out in the water.
	 * Only a goal that {@link Goal#mayEndAfloat may end afloat} is reached swimming at the surface too, and
	 * a path to one breaks a block only where there is no way without: the bot swims out rather than dig down
	 * into the bank, but digs in to water with none at the surface to swim in, such as a pond under a lid.
	 * {@code live} must not change during the search (see {@link CachedWorldView}).
	 */
	public static Path find(WorldView live, BlockPos start, Goal goal, Options options) {
		Search search = search(live, start, goal, options);
		while (search.path().isEmpty()) {
			search.run(Integer.MAX_VALUE);
		}
		return search.path().get();
	}

	/**
	 * The search {@link #find} runs, to run a slice of nodes at a time (see {@link Search#run}), such as when bots share
	 * each tick's nodes (see {@link PathBudget}). {@code live} must not change while it runs, as {@link CachedWorldView}
	 * remembers its answers: between ticks it may, and the path then holds what the world was as the search started.
	 */
	public static Search search(WorldView live, BlockPos start, Goal goal, Options options) {
		return new Search(new CachedWorldView(live), start, goal, options);
	}

	/**
	 * A search for a path, run a slice of nodes at a time until it has found one (see {@link #find}). For a goal that may
	 * end afloat and a bot that may break blocks, a search without breaking first, and only where it finds no way, one
	 * with.
	 */
	// ponytail: a second search where the first finds no way; one search with a cost per break if it shows in profiles.
	public static final class Search {
		private final WorldView world;
		private final BlockPos start;
		private final Goal goal;
		private final Options options;
		private Phase phase;
		/** Whether {@link #phase} is the search without breaking, to be followed by one with. */
		private boolean swimFirst;
		/** The nodes the searches before {@link #phase} expanded. */
		private int expandedBefore;
		private @Nullable Path path;

		private Search(WorldView world, BlockPos start, Goal goal, Options options) {
			this.world = world;
			this.start = start;
			this.goal = goal;
			this.options = options;
			this.swimFirst = goal.mayEndAfloat() && options.canBreak();
			this.phase = new Phase(world, start, goal,
				this.swimFirst ? new Options(false, options.canPlace(), options.maxNodes(), options.maxDrop()) : options);
		}

		/** The path, once the search has found it: a complete one, or one toward the goal once it ran out of nodes. */
		public Optional<Path> path() {
			return Optional.ofNullable(this.path);
		}

		/** Expands up to {@code nodes} more nodes. Returns how many it expanded. */
		public int run(int nodes) {
			int expanded = 0;
			while (this.path == null && expanded < nodes) {
				int before = this.phase.expanded;
				Path found = this.phase.run(nodes - expanded);
				expanded += this.phase.expanded - before;
				if (found == null) {
					continue;
				}
				if (this.swimFirst && !found.complete()) {
					this.swimFirst = false;
					this.expandedBefore = found.expanded();
					this.phase = new Phase(this.world, this.start, this.goal, this.options);
				} else {
					this.path = new Path(found.start(), found.steps(), found.complete(), found.cost(), this.expandedBefore + found.expanded());
				}
			}
			return expanded;
		}
	}

	/** One A* search, run a slice at a time. */
	private static final class Phase {
		private final WorldView world;
		private final BlockPos start;
		private final Goal goal;
		private final Options options;
		private final Planner planner;
		private final Map<BlockPos, Double> bestCost = new HashMap<>();
		private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.priority, b.priority));
		private Node closest;
		private double closestHeuristic;
		private boolean closestInWater;
		private int expanded;

		Phase(WorldView world, BlockPos start, Goal goal, Options options) {
			this.world = world;
			this.start = start;
			this.goal = goal;
			this.options = options;
			this.planner = new Planner(world, options, goal);
			Node startNode = new Node(start, 0, null, null);
			this.open.add(new Entry(startNode, goal.heuristic(start)));
			this.bestCost.put(start, 0.0);
			this.closest = startNode;
			this.closestHeuristic = goal.heuristic(start);
			this.closestInWater = world.isWater(start);
		}

		/** Expands up to {@code nodes} more nodes; the path once found or out of nodes, else null. */
		@Nullable Path run(int nodes) {
			int limit = this.expanded + Math.min(nodes, this.options.maxNodes() - this.expanded);
			while (!this.open.isEmpty() && this.expanded < limit) {
				Node node = this.open.poll().node;
				if (node.cost > this.bestCost.getOrDefault(node.pos, BLOCKED)) {
					continue; // stale queue entry
				}
				boolean inWater = this.world.isWater(node.pos);
				// Afloat in water whose cover the path broke, such as a pond under a lid it dug into, counts as at the surface.
				boolean afloat = this.planner.swimmable(node.pos) || inWater && brokenOnTheWay(node, node.pos.above());
				if ((!inWater && !this.planner.hanging(node.pos) || this.goal.mayEndAfloat() && afloat)
						&& this.goal.isReached(node.pos, pos -> brokenOnTheWay(node, pos))) {
					return toPath(this.start, node, true, this.expanded);
				}
				this.expanded++;

				double h = this.goal.heuristic(node.pos);
				if (this.closestInWater && !inWater || inWater == this.closestInWater && h < this.closestHeuristic) {
					this.closest = node;
					this.closestHeuristic = h;
					this.closestInWater = inWater;
				}

				for (Candidate move : this.planner.moves(node)) {
					double cost = node.cost + move.cost;
					if (cost < this.bestCost.getOrDefault(move.step.to(), BLOCKED)) {
						this.bestCost.put(move.step.to(), cost);
						Node next = new Node(move.step.to(), cost, node, move.step);
						this.open.add(new Entry(next, cost + this.goal.heuristic(next.pos)));
					}
				}
			}
			return this.open.isEmpty() || this.expanded >= this.options.maxNodes() ? toPath(this.start, this.closest, false, this.expanded) : null;
		}
	}

	/** Whether the path to {@code node} broke {@code pos} and left it broken, so a goal's sight sees through it. */
	// ponytail: walks the path back per block a sight ray crosses, only once a node is within reach; a per-node set if it shows in profiles.
	private static boolean brokenOnTheWay(Node node, BlockPos pos) {
		for (Node n = node; n.step != null; n = n.parent) {
			if (n.step.places().contains(pos)) {
				return false;
			}
			if (n.step.breaks().contains(pos)) {
				return true;
			}
		}
		return false;
	}

	private static Path toPath(BlockPos start, Node end, boolean complete, int expanded) {
		List<Step> steps = new ArrayList<>();
		for (Node n = end; n.step != null; n = n.parent) {
			steps.add(n.step);
		}
		Collections.reverse(steps);
		return new Path(start, List.copyOf(steps), complete, end.cost, expanded);
	}

	private record Candidate(Step step, double cost) {
	}

	/** Generates the possible moves from a position. */
	private static final class Planner {
		private final WorldView world;
		private final Options options;
		/** Whether a path may dig down into water (see {@link #digDown}): only one that may end afloat, so others never wade into a hidden pocket. */
		private final boolean intoWater;
		/** The goal, which may keep the path from breaking some blocks (see {@link Goal#mayBreak}). */
		private final Goal goal;
		/** The node whose moves are being generated; its path so far tells which blocks it placed and broke. */
		private Node node;

		Planner(WorldView world, Options options, Goal goal) {
			this.world = world;
			this.options = options;
			this.intoWater = goal.mayEndAfloat();
			this.goal = goal;
		}

		List<Candidate> moves(Node node) {
			this.node = node;
			BlockPos feet = node.pos;
			List<Candidate> moves = new ArrayList<>();
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				this.add(moves, this.walk(feet, dir));
				this.add(moves, this.stepUp(feet, dir));
				this.add(moves, this.drop(feet, dir));
				this.add(moves, this.swim(feet, dir));
				this.add(moves, this.enterColumn(feet, dir));
			}
			this.add(moves, this.surface(feet));
			this.add(moves, this.climb(feet, MoveType.CLIMB_UP));
			this.add(moves, this.climb(feet, MoveType.CLIMB_DOWN));
			this.add(moves, this.pillar(feet));
			this.add(moves, this.digDown(feet));
			return moves;
		}

		private void add(List<Candidate> moves, Candidate move) {
			if (move != null) {
				moves.add(move);
			}
		}

		private Candidate walk(BlockPos feet, Direction dir) {
			BlockPos to = feet.relative(dir);
			Builder b = new Builder(MoveType.WALK, to, WALK_COST);
			b.pass(to, dir).pass(to.above(), dir).support(to.below());
			return b.build();
		}

		private Candidate stepUp(BlockPos feet, Direction dir) {
			BlockPos to = feet.relative(dir).above();
			// Onto a stair block from its low front a bot walks up without jumping.
			Builder b = new Builder(MoveType.STEP_UP, to, this.world.stairUp(to.below(), dir) ? WALK_COST : WALK_COST + JUMP_COST);
			b.clear(feet.above(2)).pass(to, dir).pass(to.above(), dir).support(to.below());
			return b.build();
		}

		private Candidate drop(BlockPos feet, Direction dir) {
			BlockPos edge = feet.relative(dir);
			if (!this.open(edge, dir) || !this.open(edge.above(), dir) || this.canStand(edge.below())) {
				return null;
			}
			// A door on the way, such as one the bot drops into the doorway of, costs its opening once.
			double opening = this.world.canOpen(edge, dir) || this.world.canOpen(edge.above(), dir) ? OPEN_COST : 0;
			int maxFall = Math.max(this.options.maxDrop(), MAX_WATER_DROP);
			for (int fall = 1; fall <= maxFall; fall++) {
				BlockPos to = edge.below(fall);
				if (this.swimmable(to)) {
					return new Candidate(new Step(MoveType.SWIM, to, List.of(), List.of()), SWIM_COST + fall * FALL_COST_PER_BLOCK + opening);
				}
				if (!this.open(to, dir)) {
					return null;
				}
				if (fall <= this.options.maxDrop() && this.canStand(to.below())) {
					return new Candidate(new Step(MoveType.DROP, to, List.of(), List.of()), WALK_COST + fall * FALL_COST_PER_BLOCK + opening);
				}
			}
			return null;
		}

		/** Swims one block sideways at the surface, from water or from the bank. */
		private Candidate swim(BlockPos feet, Direction dir) {
			BlockPos to = feet.relative(dir);
			return this.swimmable(to) ? new Candidate(new Step(MoveType.SWIM, to, List.of(), List.of()), SWIM_COST) : null;
		}

		/**
		 * Swims into the foot of a waterfall or other water column, to swim up it with {@link #surface}. Only with the
		 * head out of water, on land or at the surface, so bots still never swim sideways under water.
		 */
		private Candidate enterColumn(BlockPos feet, Direction dir) {
			BlockPos to = feet.relative(dir);
			if (this.world.isWater(feet.above()) || !this.world.isWater(to) || !this.world.isWater(to.above())) {
				return null;
			}
			return new Candidate(new Step(MoveType.SWIM, to, List.of(), List.of()), SWIM_COST);
		}

		/** Rises one block from under water, toward the surface. */
		private Candidate surface(BlockPos feet) {
			BlockPos to = feet.above();
			if (!this.world.isWater(feet) || !this.world.isWater(to) || !this.open(to.above()) && !this.world.isWater(to.above())) {
				return null;
			}
			return new Candidate(new Step(MoveType.SWIM, to, List.of(), List.of()), SWIM_UP_COST);
		}

		/** Water with open air above it, where a bot floats with its head out. Bots do not dive, but rise up water columns (see {@link #enterColumn}). */
		private boolean swimmable(BlockPos pos) {
			return this.world.isWater(pos) && this.open(pos.above());
		}

		/**
		 * One block up or down a ladder, from one climbable block to the next, or down into one, or up out of the top of a
		 * climbable block a bot stands on, such as scaffolding. Breaks nothing on the way.
		 */
		private Candidate climb(BlockPos feet, MoveType type) {
			boolean up = type == MoveType.CLIMB_UP;
			BlockPos to = up ? feet.above() : feet.below();
			boolean held = up
				? this.world.isClimbable(feet) && (this.world.isClimbable(to) && this.climbable(to.above()) || this.onTop(feet) && this.open(to) && this.open(to.above()))
				: this.world.isClimbable(to);
			return held ? new Candidate(new Step(type, to, List.of(), List.of()), CLIMB_COST) : null;
		}

		/**
		 * A climbable block a bot stands on once it is out of its top, such as scaffolding on the ground or on more of it.
		 * Not a shut hatch, which stands on a ladder: the bot opens it to climb through, and then nothing holds it up.
		 */
		private boolean onTop(BlockPos climbable) {
			return this.canStand(climbable) && this.canStand(climbable.below());
		}

		/** In a ladder or other climbable block with nothing to stand on under it, where a bot holds on rather than stands. */
		boolean hanging(BlockPos feet) {
			return this.world.isClimbable(feet) && !this.canStand(feet.below());
		}

		/** Open, or a ladder or hatch a bot climbs through. */
		private boolean climbable(BlockPos pos) {
			return this.open(pos) || this.world.isClimbable(pos);
		}

		private Candidate pillar(BlockPos feet) {
			if (this.world.isClimbable(feet) || !this.options.canPlace() || !this.world.mayPlace(feet)) {
				return null;
			}
			BlockPos to = feet.above();
			Builder b = new Builder(MoveType.PILLAR, to, JUMP_COST + PLACE_COST);
			b.clear(to.above());
			if (!this.hasFace(feet, b.breaks)) {
				return null;
			}
			b.places.add(feet);
			return b.build();
		}

		/**
		 * Breaks the block underfoot and lands on the one under it, or for a goal that may end afloat drops into water
		 * under it, such as a pond under a lid, with its head out where the block was.
		 */
		private Candidate digDown(BlockPos feet) {
			BlockPos below = feet.below();
			if (this.world.isPassable(below) || this.world.isClimbable(below)) {
				return null;
			}
			if (this.world.isWater(below.below())) {
				if (!this.intoWater || !this.breakable(below, true)) {
					return null;
				}
				Builder b = new Builder(MoveType.SWIM, below.below(), SWIM_COST + this.world.breakTicks(below) + BREAK_OVERHEAD);
				b.breaks.add(below);
				return b.build();
			}
			if (!this.canStand(below.below())) {
				return null;
			}
			Builder b = new Builder(MoveType.DIG_DOWN, below, FALL_COST_PER_BLOCK);
			b.clear(below);
			return b.build();
		}

		private boolean open(BlockPos pos) {
			return this.world.isPassable(pos) && (!this.world.isHazard(pos) || this.world.isPortal(pos) && this.entersPortal(pos));
		}

		/**
		 * A path enters a nether portal only at its feet and head standing on a goal of one block, as {@code travel}
		 * walks to the portal's floor block (see MoveApi); any other walk goes round, or finds no way.
		 */
		private boolean entersPortal(BlockPos pos) {
			BlockPos target = switch (this.goal) {
				case Goal.Block(BlockPos at) -> at;
				case Goal.Near(BlockPos at, int range) when range == 0 -> at;
				default -> null;
			};
			return pos.equals(target) || pos.below().equals(target);
		}

		/** Open, or a door or fence gate a bot moving {@code dir} opens by hand. */
		private boolean open(BlockPos pos, Direction dir) {
			return this.open(pos) || this.world.canOpen(pos, dir);
		}

		private boolean canStand(BlockPos pos) {
			return this.world.canStandOn(pos) && !this.world.isHazard(pos);
		}

		/**
		 * A block placed at {@code pos} has a face to be placed against, as in vanilla: a side neighbour that is a block,
		 * not air or fluid. Blocks the path so far placed count; those it broke, or this move breaks first, do not.
		 */
		private boolean hasFace(BlockPos pos, List<BlockPos> breaking) {
			for (Direction dir : Direction.values()) {
				if (this.solid(pos.relative(dir), breaking)) {
					return true;
				}
			}
			return false;
		}

		// ponytail: walks the whole path back per neighbour, only when a block is to be placed; a per-node set if it ever shows in profiles.
		private boolean solid(BlockPos pos, List<BlockPos> breaking) {
			if (breaking.contains(pos)) {
				return false;
			}
			for (Node n = this.node; n.step != null; n = n.parent) {
				if (n.step.places().contains(pos)) {
					return true;
				}
				if (n.step.breaks().contains(pos)) {
					return false;
				}
			}
			return !this.world.isPassable(pos) && !this.world.isHazard(pos);
		}

		/**
		 * Whether a path may break the block at {@code pos}: never one beside a fluid, which would flow in, but for
		 * {@code overWater} one with water only under it, which stays put. Nor one the goal keeps the path from breaking
		 * from where it stands.
		 */
		private boolean breakable(BlockPos pos, boolean overWater) {
			if (!this.options.canBreak() || !this.goal.mayBreak(this.node.pos, pos) || this.world.isHazard(pos) || this.world.breakTicks(pos) < 0) {
				return false;
			}
			for (Direction dir : Direction.values()) {
				if (this.world.isHazard(pos.relative(dir)) && !(overWater && dir == Direction.DOWN && this.world.isWater(pos.below()))) {
					return false;
				}
			}
			return true;
		}

		/** Accumulates the cost, breaks and placements of one move. */
		private final class Builder {
			private final MoveType type;
			private final BlockPos to;
			private double cost;
			private final List<BlockPos> breaks = new ArrayList<>();
			private final List<BlockPos> places = new ArrayList<>();

			Builder(MoveType type, BlockPos to, double cost) {
				this.type = type;
				this.to = to;
				this.cost = cost;
			}

			/** The bot must be able to occupy this block, breaking it if needed. */
			Builder clear(BlockPos pos) {
				if (Planner.this.climbable(pos)) {
					return this;
				}
				if (Planner.this.breakable(pos, false)) {
					this.breaks.add(pos);
					this.cost += Planner.this.world.breakTicks(pos) + BREAK_OVERHEAD;
				} else {
					this.cost = BLOCKED;
				}
				return this;
			}

			/** Like {@link #clear}, but through a door or fence gate the bot opens rather than breaks. */
			Builder pass(BlockPos pos, Direction dir) {
				if (Planner.this.world.canOpen(pos, dir)) {
					this.cost += OPEN_COST;
					return this;
				}
				return this.clear(pos);
			}

			/** The bot must be able to stand on this block, placing one if needed. */
			Builder support(BlockPos pos) {
				if (Planner.this.canStand(pos)) {
					return this;
				}
				if (Planner.this.options.canPlace() && Planner.this.open(pos) && Planner.this.world.mayPlace(pos)
						&& Planner.this.hasFace(pos, this.breaks)) {
					this.places.add(pos);
					this.cost += PLACE_COST;
				} else {
					this.cost = BLOCKED;
				}
				return this;
			}

			Candidate build() {
				if (this.cost == BLOCKED) {
					return null;
				}
				return new Candidate(new Step(this.type, this.to, List.copyOf(this.breaks), List.copyOf(this.places)), this.cost);
			}
		}
	}
}
