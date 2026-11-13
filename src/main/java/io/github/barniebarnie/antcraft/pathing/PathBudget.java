package io.github.barniebarnie.antcraft.pathing;

import java.util.HashSet;
import java.util.Set;

/**
 * How many path nodes all bots together may expand in one server tick, so many bots planning at once, such as each
 * failing to reach an item no bot can get to, spread their searches over the next ticks rather than stall one
 * (see {@link PathFinder.Search#run}). A bot whose search is cut short waits where it stands for the next tick's nodes,
 * and gets an even part of them with the other searches that go on ({@link #share}, #532), so one bot's long plan cannot
 * hold up another's short one until it is done. Server thread only.
 */
public final class PathBudget {
	/**
	 * Half a search of the most nodes a bot plans with (see PathFollower): some 7 milliseconds of a tick on average, and up
	 * to 15 in the worst tick, in the bot benchmark's reachable case, where 30 bots plan all the time (#329).
	 */
	// ponytail: fixed share; measured with the bot benchmark (BenchmarkGameTest), tune from play-tests.
	public static final int NODES_PER_TICK = 2000;
	/** The server's budget, which every bot's path follower shares. */
	public static final PathBudget SERVER = new PathBudget(NODES_PER_TICK);

	private final int perTick;
	private long tick = Long.MIN_VALUE;
	private int left;
	/** The searches paused last tick that have not asked for their {@link #share} yet this tick. */
	private Set<Object> waiting = new HashSet<>();
	/** The searches paused this tick. */
	private Set<Object> paused = new HashSet<>();

	public PathBudget(int perTick) {
		this.perTick = perTick;
	}

	/** The nodes left in {@code tick}. */
	public int available(long tick) {
		if (tick != this.tick) {
			this.waiting = tick == this.tick + 1 ? this.paused : new HashSet<>();
			this.paused = new HashSet<>();
			this.tick = tick;
			this.left = this.perTick;
		}
		return this.left;
	}

	/**
	 * What {@code who}'s search may use of what is left in {@code tick}: an even part, keeping one for each search
	 * {@link #pause}d last tick that has not had its part yet, so one big search cannot hold the others up until it is
	 * done (#527). Alone it may use all that is left.
	 */
	public int share(long tick, Object who) {
		int left = this.available(tick);
		this.waiting.remove(who);
		return left / (1 + this.waiting.size());
	}

	/** {@code who}'s search goes on next tick: it gets its part of that tick, even if others ask before it. */
	public void pause(long tick, Object who) {
		this.available(tick);
		this.paused.add(who);
	}

	/** Counts {@code nodes} expanded in {@code tick}. */
	public void spend(long tick, int nodes) {
		this.left = Math.max(0, this.available(tick) - nodes);
	}
}
