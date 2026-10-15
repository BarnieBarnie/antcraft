package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.entity.BotRole;
import java.util.Map;

/** Decides which job an unassigned colony bot should take up. */
public final class RoleAssigner {
	/** Which role does which kind of job. */
	private static final Map<JobType, BotRole> WORKERS = Map.of(
		JobType.CHOP_TREE, BotRole.LUMBERJACK,
		JobType.MINE_LAYER, BotRole.MINER,
		JobType.HARVEST_CROP, BotRole.FARMER
	);

	private RoleAssigner() {
	}

	/**
	 * @param roles how many members already have each role
	 * @param openJobs unclaimed jobs on the board, by type
	 * @param hasOrders whether the colony has crafting orders
	 */
	public static BotRole choose(Map<BotRole, Integer> roles, Map<JobType, Integer> openJobs, boolean hasOrders) {
		if (hasOrders && roles.getOrDefault(BotRole.CRAFTER, 0) == 0) {
			return BotRole.CRAFTER;
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
}
