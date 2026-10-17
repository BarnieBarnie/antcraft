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

	private static final double BLOCKED = Double.POSITIVE_INFINITY;

	public record Options(boolean canBreak, boolean canPlace, int maxNodes, int maxDrop) {
	}

	private record Node(BlockPos pos, double cost, Node parent, Step step) {
	}

	private record Entry(Node node, double priority) {
	}

	private PathFinder() {
	}

	public static Path find(WorldView world, BlockPos start, Goal goal, Options options) {
		Planner planner = new Planner(world, options);
		Map<BlockPos, Double> bestCost = new HashMap<>();
		PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.priority, b.priority));

		Node startNode = new Node(start, 0, null, null);
		open.add(new Entry(startNode, goal.heuristic(start)));
		bestCost.put(start, 0.0);
		Node closest = startNode;
		double closestHeuristic = goal.heuristic(start);
		int expanded = 0;

		while (!open.isEmpty() && expanded < options.maxNodes()) {
			Node node = open.poll().node;
			if (node.cost > bestCost.getOrDefault(node.pos, BLOCKED)) {
				continue; // stale queue entry
			}
			if (goal.isReached(node.pos)) {
				return toPath(start, node, true);
			}
			expanded++;

			double h = goal.heuristic(node.pos);
			if (h < closestHeuristic) {
				closest = node;
				closestHeuristic = h;
			}

			for (Candidate move : planner.moves(node.pos)) {
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
		return new Path(start, List.copyOf(steps), complete);
	}

	private record Candidate(Step step, double cost) {
	}

	/** Generates the possible moves from a position. */
	private static final class Planner {
		private final WorldView world;
		private final Options options;

		Planner(WorldView world, Options options) {
			this.world = world;
			this.options = options;
		}

		List<Candidate> moves(BlockPos feet) {
			List<Candidate> moves = new ArrayList<>();
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				this.add(moves, this.walk(feet, dir));
				this.add(moves, this.stepUp(feet, dir));
				this.add(moves, this.drop(feet, dir));
			}
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
			b.clear(to).clear(to.above()).support(to.below());
			return b.build();
		}

		private Candidate stepUp(BlockPos feet, Direction dir) {
			BlockPos to = feet.relative(dir).above();
			Builder b = new Builder(MoveType.STEP_UP, to, WALK_COST + JUMP_COST);
			b.clear(feet.above(2)).clear(to).clear(to.above()).support(to.below());
			return b.build();
		}

		private Candidate drop(BlockPos feet, Direction dir) {
			BlockPos edge = feet.relative(dir);
			if (!this.open(edge) || !this.open(edge.above()) || this.canStand(edge.below())) {
				return null;
			}
			for (int fall = 1; fall <= this.options.maxDrop(); fall++) {
				BlockPos to = edge.below(fall);
				if (!this.open(to)) {
					return null;
				}
				if (this.canStand(to.below())) {
					return new Candidate(new Step(MoveType.DROP, to, List.of(), List.of()), WALK_COST + fall * FALL_COST_PER_BLOCK);
				}
			}
			return null;
		}

		private Candidate pillar(BlockPos feet) {
			if (!this.options.canPlace() || !this.world.mayPlace(feet)) {
				return null;
			}
			BlockPos to = feet.above();
			Builder b = new Builder(MoveType.PILLAR, to, JUMP_COST + PLACE_COST);
			b.clear(to.above());
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

			/** The bot must be able to stand on this block, placing one if needed. */
			Builder support(BlockPos pos) {
				if (Planner.this.canStand(pos)) {
					return this;
				}
				if (Planner.this.options.canPlace() && Planner.this.open(pos) && Planner.this.world.mayPlace(pos)) {
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
