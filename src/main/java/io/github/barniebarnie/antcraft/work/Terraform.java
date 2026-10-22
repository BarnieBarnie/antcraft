package io.github.barniebarnie.antcraft.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;

/**
 * Levelling an area to a height: a build plan that clears everything in a box above the height,
 * and fills the holes in the ground up to it, so the top layer at the height is solid.
 */
public final class Terraform {
	/** Columns per side of the square of ground one batch covers, so a batch stays small enough to scan every tick. */
	static final int CHUNK = 4;

	private Terraform() {
	}

	/**
	 * Whether the block at {@code pos} is lava or touches it. A terraform leaves such steps alone, to
	 * be reported as blocked: water it fills, but lava is not to be filled or opened up.
	 */
	public static boolean touchesLava(Function<BlockPos, BlockState> world, BlockPos pos) {
		if (world.apply(pos).getFluidState().getType().isSame(Fluids.LAVA)) {
			return true;
		}
		for (Direction side : Direction.values()) {
			if (world.apply(pos.relative(side)).getFluidState().getType().isSame(Fluids.LAVA)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A fill step: any block a bot can stand on will do, dirt if there is none (see
	 * {@link BuildPlan.Step#anySolid}). Fills go in from the bottom up, so sand and gravel rest on
	 * what is below and may stand in (see {@link BuildPlan.Step#mayFall}).
	 */
	public static BuildPlan.Step fill(BlockPos pos) {
		return new BuildPlan.Step(pos, Blocks.DIRT.defaultBlockState(), true, true);
	}

	/**
	 * The plan that levels the box between the corners {@code from} and {@code to} (inclusive, any
	 * order) at height {@code y}, or empty if it is level already. Above {@code y} every block is
	 * cleared; at {@code y} and below, each column's hole is filled from the bottom of the box or
	 * the first solid block down, water in it too, as a pond or the edge of a river is. Blocks
	 * already right get no step, and caves under solid ground are left alone.
	 *
	 * <p>Each square of {@link #CHUNK} columns gets a dig batch, top down, and a fill batch, bottom
	 * up and water from its edge inwards (see {@link #fill}), which needs that dig batch if it digs
	 * above any of the holes: the dig batches come first.
	 */
	public static Optional<BuildPlan> plan(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to, int y) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		List<List<BuildPlan.Step>> digs = new ArrayList<>();
		List<List<BuildPlan.Step>> fills = new ArrayList<>();
		Map<Long, Integer> fromEdge = fromEdge(world, min, max, y);
		for (int cz = min.getZ(); cz <= max.getZ(); cz += CHUNK) {
			for (int cx = min.getX(); cx <= max.getX(); cx += CHUNK) {
				int toX = Math.min(cx + CHUNK - 1, max.getX());
				int toZ = Math.min(cz + CHUNK - 1, max.getZ());
				digs.add(dig(world, cx, cz, toX, toZ, Math.max(y + 1, min.getY()), max.getY()));
				fills.add(fill(world, fromEdge, cx, cz, toX, toZ, min.getY(), Math.min(y, max.getY())));
			}
		}
		List<BuildPlan.Step> steps = new ArrayList<>();
		List<BuildPlan.Batch> batches = new ArrayList<>();
		// The index of each square's dig batch, or -1 if it has nothing to dig.
		int[] digBatch = new int[digs.size()];
		for (int i = 0; i < digs.size(); i++) {
			digBatch[i] = digs.get(i).isEmpty() ? -1 : batches.size();
			add(steps, batches, digs.get(i), List.of(), true);
		}
		for (int i = 0; i < fills.size(); i++) {
			Set<Long> columns = fills.get(i).stream().map(step -> column(step.pos())).collect(Collectors.toSet());
			boolean above = digs.get(i).stream().anyMatch(step -> columns.contains(column(step.pos())));
			add(steps, batches, fills.get(i), above ? List.of(digBatch[i]) : List.of(), false);
		}
		Optional<BoundingBox> ground = y < min.getY() ? Optional.empty()
			: Optional.of(new BoundingBox(min.getX(), min.getY(), min.getZ(), max.getX(), Math.min(y, max.getY()), max.getZ()));
		return steps.isEmpty() ? Optional.empty() : Optional.of(new BuildPlan(steps, batches, BuildPlan.Kind.TERRAFORM, ground));
	}

	private static long column(BlockPos pos) {
		return BlockPos.asLong(pos.getX(), 0, pos.getZ());
	}

	private static void add(List<BuildPlan.Step> steps, List<BuildPlan.Batch> batches, List<BuildPlan.Step> batch, List<Integer> needs, boolean dig) {
		if (!batch.isEmpty()) {
			batches.add(new BuildPlan.Batch(steps.size(), steps.size() + batch.size(), needs, dig));
			steps.addAll(batch);
		}
	}

	/** Clear steps for the blocks between heights {@code fromY} and {@code toY}, layer by layer from the top. */
	private static List<BuildPlan.Step> dig(Function<BlockPos, BlockState> world, int fromX, int fromZ, int toX, int toZ, int fromY, int toY) {
		List<BuildPlan.Step> steps = new ArrayList<>();
		for (int py = toY; py >= fromY; py--) {
			for (int z = fromZ; z <= toZ; z++) {
				for (int x = fromX; x <= toX; x++) {
					BuildPlan.Step step = BuildPlan.Step.clear(new BlockPos(x, py, z));
					if (!step.isDone(world.apply(step.pos()))) {
						steps.add(step);
					}
				}
			}
		}
		return steps;
	}

	/**
	 * Fill steps for each column's hole from {@code toY} down to the first solid block or
	 * {@code fromY}, each column from the bottom. Water is filled from its edge inwards (see
	 * {@link #fromEdge}), so the builders stand on what they filled to reach further in, and in
	 * each ring the sources go before flowing water, which dries up once they are gone. Dry holes
	 * are at the edge, so they go first, layer by layer.
	 */
	private static List<BuildPlan.Step> fill(Function<BlockPos, BlockState> world, Map<Long, Integer> fromEdge,
			int fromX, int fromZ, int toX, int toZ, int fromY, int toY) {
		List<BuildPlan.Step> steps = new ArrayList<>();
		for (int x = fromX; x <= toX; x++) {
			for (int z = fromZ; z <= toZ; z++) {
				int py = toY;
				while (py >= fromY && !fill(new BlockPos(x, py, z)).isDone(world.apply(new BlockPos(x, py, z)))) {
					py--;
				}
				for (int hole = py + 1; hole <= toY; hole++) {
					steps.add(fill(new BlockPos(x, hole, z)));
				}
			}
		}
		Comparator<BuildPlan.Step> order = Comparator
			.comparingInt((BuildPlan.Step step) -> fromEdge.getOrDefault(column(step.pos()), 0))
			.thenComparing(step -> isFlowing(world.apply(step.pos())))
			.thenComparingInt(step -> fromEdge.getOrDefault(column(step.pos()), 0) == 0 ? step.pos().getY() : 0)
			.thenComparingInt(step -> step.pos().getZ())
			.thenComparingInt(step -> step.pos().getX())
			.thenComparingInt(step -> step.pos().getY());
		steps.sort(order);
		return steps;
	}

	private static boolean isFlowing(BlockState state) {
		return !state.getFluidState().isEmpty() && !state.getFluidState().isSource();
	}

	/**
	 * How far each column of the box between {@code min} and {@code max} is from dry ground, in
	 * steps sideways: 0 for a column without fluid in it up to {@code y}, 1 for wet ones beside such
	 * a column, and so on. The columns just outside the box count too, so a pond at the side of the
	 * area fills from the bank there, and a river running past it from the bank inside. Wet columns
	 * with no dry ground anywhere near get no entry.
	 */
	private static Map<Long, Integer> fromEdge(Function<BlockPos, BlockState> world, BlockPos min, BlockPos max, int y) {
		Map<Long, Integer> distance = new HashMap<>();
		ArrayDeque<long[]> queue = new ArrayDeque<>();
		for (int x = min.getX() - 1; x <= max.getX() + 1; x++) {
			for (int z = min.getZ() - 1; z <= max.getZ() + 1; z++) {
				if (!isWet(world, x, z, min.getY(), Math.min(y, max.getY()))) {
					distance.put(BlockPos.asLong(x, 0, z), 0);
					queue.add(new long[] {x, z});
				}
			}
		}
		while (!queue.isEmpty()) {
			long[] at = queue.poll();
			int next = distance.get(BlockPos.asLong((int) at[0], 0, (int) at[1])) + 1;
			for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int x = (int) at[0] + side[0];
				int z = (int) at[1] + side[1];
				if (x >= min.getX() - 1 && x <= max.getX() + 1 && z >= min.getZ() - 1 && z <= max.getZ() + 1
						&& distance.putIfAbsent(BlockPos.asLong(x, 0, z), next) == null) {
					queue.add(new long[] {x, z});
				}
			}
		}
		return distance;
	}

	/** Whether the column at {@code x}, {@code z} holds a fluid between heights {@code fromY} and {@code toY}. */
	private static boolean isWet(Function<BlockPos, BlockState> world, int x, int z, int fromY, int toY) {
		for (int py = fromY; py <= toY; py++) {
			if (!world.apply(new BlockPos(x, py, z)).getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}
}
