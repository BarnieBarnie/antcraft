package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
