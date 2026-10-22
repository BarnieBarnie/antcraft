package io.github.barniebarnie.antcraft.pathing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
	 * Swimming one block up a water column, such as a waterfall. Measured at about 8 ticks a block (see
	 * {@code PathingGameTest.swimUpCostMatchesTheTimeARiseTakes}), so a climb up a long waterfall is not mistaken for
	 * a shortcut.
	 */
	public static final double SWIM_UP_COST = 8.0;

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
	 * Plans a path from {@code start} to {@code goal}. A path never ends in water: a bot swims only to
	 * get somewhere dry, and a partial path stops on the nearest shore rather than out in the water.
	 * Only a goal that {@link Goal#mayEndAfloat may end afloat} is reached swimming at the surface too, and
	 * a path to one breaks a block only where there is no way without: the bot swims out rather than dig down
	 * into the bank, but digs in to water with none at the surface to swim in, such as a pond under a lid.
	 */
	public static Path find(WorldView world, BlockPos start, Goal goal, Options options) {
		if (goal.mayEndAfloat() && options.canBreak()) {
			// ponytail: a second search where the first finds no way; one search with a cost per break if it shows in profiles.
			Path swim = search(world, start, goal, new Options(false, options.canPlace(), options.maxNodes(), options.maxDrop()));
			if (swim.complete()) {
				return swim;
			}
		}
		return search(world, start, goal, options);
	}

	private static Path search(WorldView world, BlockPos start, Goal goal, Options options) {
		Planner planner = new Planner(world, options);
		Map<BlockPos, Double> bestCost = new HashMap<>();
		PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.priority, b.priority));

		Node startNode = new Node(start, 0, null, null);
		open.add(new Entry(startNode, goal.heuristic(start)));
		bestCost.put(start, 0.0);
		Node closest = startNode;
		double closestHeuristic = goal.heuristic(start);
		boolean closestInWater = world.isWater(start);
		int expanded = 0;

		while (!open.isEmpty() && expanded < options.maxNodes()) {
			Node node = open.poll().node;
			if (node.cost > bestCost.getOrDefault(node.pos, BLOCKED)) {
				continue; // stale queue entry
			}
			boolean inWater = world.isWater(node.pos);
			if ((!inWater || goal.mayEndAfloat() && planner.swimmable(node.pos)) && goal.isReached(node.pos)) {
				return toPath(start, node, true);
			}
			expanded++;

			double h = goal.heuristic(node.pos);
			if (closestInWater && !inWater || inWater == closestInWater && h < closestHeuristic) {
				closest = node;
				closestHeuristic = h;
				closestInWater = inWater;
			}

			for (Candidate move : planner.moves(node)) {
				double cost = node.cost + move.cost;
				if (cost < bestCost.getOrDefault(move.step.to(), BLOCKED)) {
					bestCost.put(move.step.to(), cost);
					Node next = new Node(move.step.to(), cost, node, move.step);
					open.add(new Entry(next, cost + goal.heuristic(next.pos)));
				}
			}
		}
		return toPath(start, closest, false);
	}

	private static Path toPath(BlockPos start, Node end, boolean complete) {
		List<Step> steps = new ArrayList<>();
		for (Node n = end; n.step != null; n = n.parent) {
			steps.add(n.step);
		}
		Collections.reverse(steps);
		return new Path(start, List.copyOf(steps), complete, end.cost);
	}

	private record Candidate(Step step, double cost) {
	}

	/** Generates the possible moves from a position. */
	private static final class Planner {
		private final WorldView world;
		private final Options options;
		/** The node whose moves are being generated; its path so far tells which blocks it placed and broke. */
		private Node node;

		Planner(WorldView world, Options options) {
			this.world = world;
			this.options = options;
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
			b.clear(feet.above(2)).clear(to).clear(to.above()).support(to.below());
			return b.build();
		}

		private Candidate drop(BlockPos feet, Direction dir) {
			BlockPos edge = feet.relative(dir);
			if (!this.open(edge) || !this.open(edge.above()) || this.canStand(edge.below())) {
				return null;
			}
			int maxFall = Math.max(this.options.maxDrop(), MAX_WATER_DROP);
			for (int fall = 1; fall <= maxFall; fall++) {
				BlockPos to = edge.below(fall);
				if (this.swimmable(to)) {
					return new Candidate(new Step(MoveType.SWIM, to, List.of(), List.of()), SWIM_COST + fall * FALL_COST_PER_BLOCK);
				}
				if (!this.open(to)) {
					return null;
				}
				if (fall <= this.options.maxDrop() && this.canStand(to.below())) {
					return new Candidate(new Step(MoveType.DROP, to, List.of(), List.of()), WALK_COST + fall * FALL_COST_PER_BLOCK);
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

		private Candidate pillar(BlockPos feet) {
			if (!this.options.canPlace() || !this.world.mayPlace(feet)) {
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

		private Candidate digDown(BlockPos feet) {
			BlockPos below = feet.below();
			if (this.world.isPassable(below) || !this.canStand(below.below())) {
				return null;
			}
			Builder b = new Builder(MoveType.DIG_DOWN, below, FALL_COST_PER_BLOCK);
			b.clear(below);
			return b.build();
		}

		private boolean open(BlockPos pos) {
			return this.world.isPassable(pos) && !this.world.isHazard(pos);
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

		private boolean breakable(BlockPos pos) {
			if (!this.options.canBreak() || this.world.isHazard(pos) || this.world.breakTicks(pos) < 0) {
				return false;
			}
			for (Direction dir : Direction.values()) {
				if (this.world.isHazard(pos.relative(dir))) {
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
				if (Planner.this.open(pos)) {
					return this;
				}
				if (Planner.this.breakable(pos)) {
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
