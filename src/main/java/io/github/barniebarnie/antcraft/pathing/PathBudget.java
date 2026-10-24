package io.github.barniebarnie.antcraft.pathing;

/**
 * How many path nodes all bots together may expand in one server tick, so many bots planning at once, such as each
 * failing to reach an item no bot can get to, spread their searches over the next ticks rather than stall one
 * (see {@link PathFinder.Search#run}). A bot whose search is cut short waits where it stands for the next tick's nodes.
 * Server thread only.
 */
public final class PathBudget {
	/** Half a search of the most nodes a bot plans with (see PathFollower), some 10 to 20 milliseconds of a tick. */
	// ponytail: fixed share; measured with the bot benchmark (BenchmarkGameTest), tune from play-tests.
	public static final int NODES_PER_TICK = 2000;
	/** The server's budget, which every bot's path follower shares. */
	public static final PathBudget SERVER = new PathBudget(NODES_PER_TICK);

	private final int perTick;
	private long tick = Long.MIN_VALUE;
	private int left;

	public PathBudget(int perTick) {
		this.perTick = perTick;
	}

	/** The nodes left in {@code tick}. */
	public int available(long tick) {
		if (tick != this.tick) {
			this.tick = tick;
			this.left = this.perTick;
		}
		return this.left;
	}

	/** Counts {@code nodes} expanded in {@code tick}. */
	public void spend(long tick, int nodes) {
		this.left = Math.max(0, this.available(tick) - nodes);
	}
}
