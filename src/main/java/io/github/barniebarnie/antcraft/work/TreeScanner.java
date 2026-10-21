package io.github.barniebarnie.antcraft.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Finds natural trees: connected logs with at least one natural (non player-placed) leaf block touching them. */
public final class TreeScanner {
	/** Room for a giant jungle tree (a 2x2 trunk up to 31 tall plus branches), with a margin. */
	private static final int MAX_LOGS = 1024;
	private static final int SCAN_BELOW = 4;
	private static final int SCAN_ABOVE = 8;
	/** How many cut logs under a standing trunk are looked through for the ground it grew on. */
	private static final int MAX_CUT_BELOW = 8;
	/** How many blocks above what it stands on a bot reaches a log. */
	private static final int REACH = 4;

	public interface TreeView {
		boolean isLog(BlockPos pos);

		boolean isNaturalLeaves(BlockPos pos);

		/** Air or a replaceable plant: not ground a tree or sapling stands on. */
		boolean isOpen(BlockPos pos);
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

	/**
	 * What still stands of a tree known to be natural, given the logs it had: those of them that are
	 * still logs. Needs no leaves, so the rest of a tree being felled is still found once its leaves
	 * decayed. Only ever pass logs of a tree found by this scanner: other logs are not checked.
	 */
	public static Optional<Tree> remaining(TreeView view, Collection<BlockPos> knownLogs) {
		List<BlockPos> standing = knownLogs.stream().filter(view::isLog).toList();
		return standing.isEmpty() ? Optional.empty() : Optional.of(sorted(standing));
	}

	/**
	 * Like {@link #remaining(TreeView, Collection)}, but the tree keeps {@code base}, where it stood
	 * on the ground, even when its bottom log is already gone. Drops are collected and a sapling
	 * planted there.
	 */
	public static Optional<Tree> remaining(TreeView view, BlockPos base, Collection<BlockPos> knownLogs) {
		return remaining(view, knownLogs).map(rest -> new Tree(base, rest.logs()));
	}

	/**
	 * Whether a bot can get at the log at {@code log} now: within {@link #REACH} blocks above whatever it
	 * stands on, such as the ground or scaffold, through the logs and air under it. Or, with air right
	 * under it, it is the bottom of the logs left once those under them were cut, which a bot pillars up to.
	 */
	public static boolean isReachable(TreeView view, BlockPos log) {
		if (view.isOpen(log.below())) {
			return true;
		}
		for (int down = 1; down <= REACH; down++) {
			BlockPos under = log.below(down);
			if (!view.isOpen(under) && !view.isLog(under)) {
				return true;
			}
		}
		return false;
	}

	/** Whether a bot can get at any of {@code logs} still standing, such as a layer of a log job (see {@link #isReachable(TreeView, BlockPos)}). */
	public static boolean isReachable(TreeView view, Collection<BlockPos> logs) {
		return logs.stream().anyMatch(log -> view.isLog(log) && isReachable(view, log));
	}

	/**
	 * Where saplings go to grow {@code tree} back: the four spots of a 2x2 trunk, else its base. The
	 * square is looked for in the two lowest layers of logs, as the bottom one may be partly cut, and
	 * put on the ground at the base's height.
	 */
	public static List<BlockPos> replantSpots(Tree tree) {
		Set<BlockPos> logs = new HashSet<>(tree.logs());
		int bottom = tree.logs().getFirst().getY();
		for (BlockPos log : tree.logs()) {
			if (log.getY() > bottom + 1) {
				break;
			}
			if (logs.contains(log.east()) && logs.contains(log.south()) && logs.contains(log.east().south())) {
				BlockPos corner = new BlockPos(log.getX(), tree.base().getY(), log.getZ());
				return List.of(corner, corner.east(), corner.south(), corner.east().south());
			}
		}
		return List.of(tree.base());
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
		Tree tree = sorted(logs);
		return Optional.of(new Tree(groundBase(view, tree.base()), tree.logs()));
	}

	/**
	 * Where a tree whose lowest log is {@code lowest} stood: the open spot on the ground under the
	 * trunk when its bottom logs were cut, else {@code lowest}. With no ground in reach it stays
	 * {@code lowest}.
	 */
	private static BlockPos groundBase(TreeView view, BlockPos lowest) {
		BlockPos pos = lowest;
		for (int i = 0; i <= MAX_CUT_BELOW && view.isOpen(pos.below()); i++) {
			pos = pos.below();
		}
		return view.isOpen(pos.below()) ? lowest : pos;
	}

	private static Tree sorted(Collection<BlockPos> logs) {
		List<BlockPos> sorted = new ArrayList<>(logs);
		sorted.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
		return new Tree(sorted.getFirst(), List.copyOf(sorted));
	}
}
