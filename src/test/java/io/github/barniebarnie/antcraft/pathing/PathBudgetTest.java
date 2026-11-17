package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PathBudgetTest {
	@Test
	void searchesShareTheNodesOfATick() {
		PathBudget budget = new PathBudget(1000);
		assertEquals(1000, budget.available(5));
		budget.spend(5, 700);
		assertEquals(300, budget.available(5));
		budget.spend(5, 300);
		assertEquals(0, budget.available(5));
	}

	@Test
	void eachTickHasItsOwnNodes() {
		PathBudget budget = new PathBudget(1000);
		budget.spend(5, 1000);
		assertEquals(0, budget.available(5));
		assertEquals(1000, budget.available(6));
		budget.spend(7, 400);
		assertEquals(600, budget.available(7));
	}

	@Test
	void spendingMoreThanIsLeftLeavesNone() {
		PathBudget budget = new PathBudget(1000);
		budget.spend(5, 1500);
		assertEquals(0, budget.available(5));
	}

	/**
	 * A search paused last tick gets its part of this tick even when another asks first, so a big search cannot take
	 * all of it and hold another bot's small search up until it is done (#527).
	 */
	@Test
	void pausedSearchesShareTheNextTick() {
		PathBudget budget = new PathBudget(1000);
		assertEquals(1000, budget.share(5, "big"), "alone, a search may take the whole tick");
		budget.spend(5, 1000);
		budget.pause(5, "big");
		assertEquals(0, budget.share(5, "small"));
		budget.pause(5, "small");
		assertEquals(500, budget.share(6, "big"), "half is kept for the small search");
		budget.spend(6, 500);
		budget.pause(6, "big");
		assertEquals(500, budget.share(6, "small"));
		budget.spend(6, 20);
		assertEquals(480, budget.share(6, "other"), "nobody is waiting any more");
		assertEquals(1000, budget.share(7, "big"), "the small search was done");
	}

	/** Only a search that goes on keeps a part of the next tick, so a finished one leaves no blocks unused. */
	@Test
	void onlyTheNextTickKeepsAPart() {
		PathBudget budget = new PathBudget(1000);
		budget.pause(5, "a");
		assertEquals(1000, budget.share(7, "b"), "nothing searched in tick 6");
	}

	/**
	 * The bot benchmark logs the nodes expanded a tick, on average and in the worst tick (#693), over the ticks strictly
	 * between two takes, as the tick profiler started and stopped in those ticks counts them, so the average is never above
	 * the worst (#701).
	 */
	@Test
	void countsTheNodesExpandedAndTheMostInOneTickBetweenTwoTakes() {
		PathBudget budget = new PathBudget(1000);
		budget.spend(5, 300);
		budget.spend(5, 900);
		budget.spend(6, 200);
		assertEquals(new PathBudget.Count(1400, 1200), budget.takeCount(7), "spent past the budget counts too");
		budget.spend(7, 100);
		budget.spend(8, 50);
		budget.spend(9, 10);
		assertEquals(new PathBudget.Count(50, 50), budget.takeCount(9), "neither the tick of the last take nor this one");
		assertEquals(new PathBudget.Count(0, 0), budget.takeCount(10));
	}

	/**
	 * A tick's paths stop at its time as well as at its nodes: a node may cost several times another, such as where the
	 * bot may dig or before the server has compiled the search, and 2000 of them took up to 57 ms in the bot benchmark's
	 * guard case (#791). What a search has not expanded it goes on with next tick.
	 */
	@Test
	void searchesStopOnceTheTicksTimeIsUsedUp() {
		long[] now = {0};
		// Every look at the clock finds it a millisecond on.
		PathBudget budget = new PathBudget(1000, 10_000_000, () -> now[0] += 1_000_000);
		PathFinder.Search search = nowhere();
		int expanded = budget.run(5, "a", search);
		assertTrue(expanded > 0 && expanded < 1000, "expanded " + expanded);
		assertEquals(0, budget.run(5, "b", nowhere()), "the tick's time is used up");
		assertTrue(budget.run(6, "a", search) > 0, "the next tick has time again");
	}

	/**
	 * Game tests plan by nodes only (#847): however slow the clock, a tick gives its nodes, so a test takes as many ticks
	 * on a busy machine as on an idle one. The server's budget otherwise also stops at its time.
	 */
	@Test
	void aNodeOnlyServerBudgetGivesItsNodesHoweverSlowTheClock() {
		long[] now = {0};
		// Every look at the clock finds it a second on.
		PathBudget nodesOnly = PathBudget.server(true, () -> now[0] += 1_000_000_000);
		assertEquals(PathBudget.NODES_PER_TICK, nodesOnly.run(5, "a", nowhere()));
		PathBudget timed = PathBudget.server(false, () -> now[0] += 1_000_000_000);
		assertTrue(timed.run(5, "a", nowhere()) < PathBudget.NODES_PER_TICK, "the real budget stops at its time");
	}

	/** A search paused last tick gets its part of this tick's time too, not only of its nodes, so it is not left without. */
	@Test
	void pausedSearchesShareTheNextTicksTime() {
		long[] now = {0};
		PathBudget budget = new PathBudget(1_000_000, 10_000_000, () -> now[0] += 1_000_000);
		budget.run(5, "a", nowhere());
		budget.pause(5, "a");
		budget.run(5, "b", nowhere());
		budget.pause(5, "b");
		budget.run(6, "a", nowhere());
		assertTrue(budget.run(6, "b", nowhere()) > 0, "half the time was kept for b");
	}

	/** A search that expands nodes until stopped: to a block in the air over a flat floor, which it never reaches. */
	private static PathFinder.Search nowhere() {
		return PathFinder.search(GridWorld.flat(40), new BlockPos(0, 1, 0), new Goal.Block(new BlockPos(0, 30, 0)), new PathFinder.Options(false, false, 1_000_000, 3));
	}
}
