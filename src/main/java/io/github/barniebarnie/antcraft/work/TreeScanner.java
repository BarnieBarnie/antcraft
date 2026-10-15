package io.github.barniebarnie.antcraft.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Finds natural trees: connected logs with at least one natural (non player-placed) leaf block touching them. */
public final class TreeScanner {
	private static final int MAX_LOGS = 128;
	private static final int SCAN_BELOW = 4;
	private static final int SCAN_ABOVE = 8;

	public interface TreeView {
		boolean isLog(BlockPos pos);

		boolean isNaturalLeaves(BlockPos pos);
	}

	/** {@code logs} is sorted bottom-up, so chopping in order works from the trunk base. */
	public record Tree(BlockPos base, List<BlockPos> logs) {
	}

	private TreeScanner() {
	}

	public static Optional<Tree> findNearest(TreeView view, BlockPos origin, int radius) {
		return findAll(view, origin, radius).stream().min(Comparator.comparingDouble(tree -> tree.base().distSqr(origin)));
	}

	/** Every natural tree with a log within {@code radius} blocks horizontally of {@code origin}. */
	public static List<Tree> findAll(TreeView view, BlockPos origin, int radius) {
		Set<BlockPos> seen = new HashSet<>();
		List<Tree> trees = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -SCAN_BELOW, -radius), origin.offset(radius, SCAN_ABOVE, radius))) {
			if (seen.contains(pos) || !view.isLog(pos)) {
				continue;
			}
			Set<BlockPos> logs = connectedLogs(view, pos.immutable());
			seen.addAll(logs);
			toTree(view, logs).ifPresent(trees::add);
		}
		return trees;
	}

	/** The natural tree that the log at {@code log} belongs to. */
	public static Optional<Tree> treeAt(TreeView view, BlockPos log) {
		if (!view.isLog(log)) {
			return Optional.empty();
		}
		return toTree(view, connectedLogs(view, log.immutable()));
	}

	private static Set<BlockPos> connectedLogs(TreeView view, BlockPos start) {
		Set<BlockPos> logs = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		logs.add(start);
		while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
			BlockPos pos = queue.poll();
			for (BlockPos next : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
				if (!logs.contains(next) && view.isLog(next)) {
					BlockPos immutable = next.immutable();
					logs.add(immutable);
					queue.add(immutable);
				}
			}
		}
		return logs;
	}

	private static Optional<Tree> toTree(TreeView view, Set<BlockPos> logs) {
		boolean hasLeaves = logs.stream().anyMatch(log -> {
			for (BlockPos near : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
				if (view.isNaturalLeaves(near)) {
					return true;
				}
			}
			return false;
		});
		if (!hasLeaves) {
			return Optional.empty();
		}
		List<BlockPos> sorted = new ArrayList<>(logs);
		sorted.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
		return Optional.of(new Tree(sorted.getFirst(), List.copyOf(sorted)));
	}
}
