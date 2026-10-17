package io.github.barniebarnie.antcraft.work;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Levelling an area to a height: a build plan that clears everything in a box above the height,
 * and fills the holes in the ground up to it, so the top layer at the height is solid.
 */
public final class Terraform {
	/** Columns per side of the square of ground one batch covers, so a batch stays small enough to scan every tick. */
	static final int CHUNK = 4;

	private Terraform() {
	}

	/** A fill step: any block a bot can stand on will do, dirt if there is none (see {@link BuildPlan.Step#anySolid}). */
	public static BuildPlan.Step fill(BlockPos pos) {
		return new BuildPlan.Step(pos, Blocks.DIRT.defaultBlockState(), true);
	}

	/**
	 * The plan that levels the box between the corners {@code from} and {@code to} (inclusive, any
	 * order) at height {@code y}, or empty if it is level already. Above {@code y} every block is
	 * cleared; at {@code y} and below, each column's hole is filled from the bottom of the box or
	 * the first solid block down. Blocks already right get no step, and caves under solid ground
	 * are left alone.
	 *
	 * <p>Each square of {@link #CHUNK} columns gets a dig batch, top down, and a fill batch, bottom
	 * up, which needs that dig batch if it digs above any of the holes: the dig batches come first.
	 */
	public static Optional<BuildPlan> plan(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to, int y) {
		BlockPos min = BlockPos.min(from, to);
		BlockPos max = BlockPos.max(from, to);
		List<List<BuildPlan.Step>> digs = new ArrayList<>();
		List<List<BuildPlan.Step>> fills = new ArrayList<>();
		for (int cz = min.getZ(); cz <= max.getZ(); cz += CHUNK) {
			for (int cx = min.getX(); cx <= max.getX(); cx += CHUNK) {
				int toX = Math.min(cx + CHUNK - 1, max.getX());
				int toZ = Math.min(cz + CHUNK - 1, max.getZ());
				digs.add(dig(world, cx, cz, toX, toZ, Math.max(y + 1, min.getY()), max.getY()));
				fills.add(fill(world, cx, cz, toX, toZ, min.getY(), Math.min(y, max.getY())));
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
		return steps.isEmpty() ? Optional.empty() : Optional.of(new BuildPlan(steps, batches, BuildPlan.Kind.TERRAFORM));
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

	/** Fill steps for each column's hole from {@code toY} down to the first solid block or {@code fromY}, layer by layer from the bottom. */
	private static List<BuildPlan.Step> fill(Function<BlockPos, BlockState> world, int fromX, int fromZ, int toX, int toZ, int fromY, int toY) {
		List<BuildPlan.Step> steps = new ArrayList<>();
		// How deep each column's hole goes.
		int[][] bottom = new int[toX - fromX + 1][toZ - fromZ + 1];
		for (int x = fromX; x <= toX; x++) {
			for (int z = fromZ; z <= toZ; z++) {
				int py = toY;
				while (py >= fromY && !fill(new BlockPos(x, py, z)).isDone(world.apply(new BlockPos(x, py, z)))) {
					py--;
				}
				bottom[x - fromX][z - fromZ] = py + 1;
			}
		}
		for (int py = fromY; py <= toY; py++) {
			for (int z = fromZ; z <= toZ; z++) {
				for (int x = fromX; x <= toX; x++) {
					if (py >= bottom[x - fromX][z - fromZ]) {
						steps.add(fill(new BlockPos(x, py, z)));
					}
				}
			}
		}
		return steps;
	}
}
