package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.entity.BotRole;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Decides which job an unassigned or idle colony bot should take up. */
public final class RoleAssigner {
	/** How long a worker has had nothing to do before the colony moves it to other work. */
	public static final int IDLE_TICKS_BEFORE_REASSIGN = 600;

	/**
	 * The roles a new colony fills first, in order, before open jobs decide: demand alone would make
	 * every bot a lumberjack, as trees are the only work a new colony has. The builder digs the
	 * mineshaft the miner works in.
	 */
	public static final List<BotRole> STARTER_ROSTER = List.of(BotRole.LUMBERJACK, BotRole.MINER, BotRole.BUILDER, BotRole.FARMER);

	/** Which role does which kind of job. */
	private static final Map<JobType, BotRole> WORKERS = Map.of(
		JobType.CHOP_TREE, BotRole.LUMBERJACK,
		JobType.MINE_BRANCH, BotRole.MINER,
		JobType.HARVEST_CROP, BotRole.FARMER,
		JobType.BUILD, BotRole.BUILDER
	);

	private RoleAssigner() {
	}

	/**
	 * Fills the {@link #STARTER_ROSTER} while fewer members than that have a role, then goes by demand.
	 *
	 * @param roles how many members already have each role
	 * @param openJobs unclaimed jobs on the board, by type
	 * @param hasOrders whether the colony has crafting orders
	 * @param needsHauler whether the core is nearly full or storage holds items the core needs
	 */
	public static BotRole choose(Map<BotRole, Integer> roles, Map<JobType, Integer> openJobs, boolean hasOrders, boolean needsHauler) {
		int assigned = roles.entrySet().stream().filter(role -> role.getKey() != BotRole.NONE).mapToInt(Map.Entry::getValue).sum();
		if (assigned < STARTER_ROSTER.size()) {
			Optional<BotRole> starter = STARTER_ROSTER.stream().filter(role -> roles.getOrDefault(role, 0) == 0).findFirst();
			if (starter.isPresent()) {
				return starter.get();
			}
		}
		return byDemand(roles, openJobs, hasOrders, needsHauler);
	}

	private static BotRole byDemand(Map<BotRole, Integer> roles, Map<JobType, Integer> openJobs, boolean hasOrders, boolean needsHauler) {
		if (hasOrders && roles.getOrDefault(BotRole.CRAFTER, 0) == 0) {
			return BotRole.CRAFTER;
		}
		// A full core stalls every role that deposits.
		if (needsHauler && roles.getOrDefault(BotRole.HAULER, 0) == 0) {
			return BotRole.HAULER;
		}

		BotRole best = BotRole.NONE;
		double bestLoad = 0;
		for (Map.Entry<JobType, BotRole> worker : WORKERS.entrySet()) {
			int jobs = openJobs.getOrDefault(worker.getKey(), 0);
			// Jobs per worker once this bot joins in.
			double load = jobs / (roles.getOrDefault(worker.getValue(), 0) + 1.0);
			if (jobs > 0 && load > bestLoad) {
				best = worker.getValue();
				bestLoad = load;
			}
		}
		if (best != BotRole.NONE) {
			return best;
		}
		return roles.getOrDefault(BotRole.GUARD, 0) == 0 ? BotRole.GUARD : BotRole.NONE;
	}

	/**
	 * The role an idle member should switch to, or {@code current} to keep it. Only roles
	 * the colony assigned and that take jobs from the board (lumberjack, miner, farmer, builder) are
	 * switched, and only when none of their own jobs are open and other work is waiting. The only miner,
	 * builder or farmer keeps its role: their work takes setting up, such as digging the mineshaft.
	 *
	 * @param roles how many members have each role, this bot included
	 */
	public static BotRole reassign(BotRole current, boolean setByPlayer, int idleTicks,
			Map<BotRole, Integer> roles, Map<JobType, Integer> openJobs, boolean hasOrders, boolean needsHauler) {
		Optional<JobType> own = WORKERS.entrySet().stream().filter(worker -> worker.getValue() == current).map(Map.Entry::getKey).findFirst();
		if (setByPlayer || own.isEmpty() || idleTicks < IDLE_TICKS_BEFORE_REASSIGN || openJobs.getOrDefault(own.get(), 0) > 0
				|| current != BotRole.LUMBERJACK && STARTER_ROSTER.contains(current) && roles.getOrDefault(current, 0) <= 1) {
			return current;
		}
		// Choose as if this bot had no role yet, by demand only: the roster is for newcomers.
		Map<BotRole, Integer> others = new EnumMap<>(BotRole.class);
		others.putAll(roles);
		others.merge(current, -1, Integer::sum);
		BotRole next = byDemand(others, openJobs, hasOrders, needsHauler);
		// Guarding is the fallback for bots with no work at all; an idle worker may get work again soon.
		return next == BotRole.NONE || next == BotRole.GUARD ? current : next;
	}
}
