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
}
