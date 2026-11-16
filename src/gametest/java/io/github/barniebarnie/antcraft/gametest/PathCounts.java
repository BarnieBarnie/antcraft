package io.github.barniebarnie.antcraft.gametest;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Mob;

/**
 * What each mob's path searches cost, per mob, as tests run side by side (#792): how many searches its vanilla
 * navigation ran (PathNavigationMixin), which path.plan and path.find leave out, and how many nodes a bot's path
 * searches expanded (PathBudgetMixin).
 */
public final class PathCounts {
	private static final Map<Mob, Integer> PLANS = new WeakHashMap<>();
	private static final Map<Mob, Integer> NODES = new WeakHashMap<>();

	private PathCounts() {
	}

	public static synchronized void plan(Mob mob) {
		PLANS.merge(mob, 1, Integer::sum);
	}

	public static synchronized void nodes(Mob mob, int nodes) {
		NODES.merge(mob, nodes, Integer::sum);
	}

	/** How many searches {@code mob}'s vanilla navigation ran. */
	public static synchronized int plans(Mob mob) {
		return PLANS.getOrDefault(mob, 0);
	}

	/** How many nodes {@code bot}'s path searches expanded. */
	public static synchronized int nodes(Mob bot) {
		return NODES.getOrDefault(bot, 0);
	}
}
