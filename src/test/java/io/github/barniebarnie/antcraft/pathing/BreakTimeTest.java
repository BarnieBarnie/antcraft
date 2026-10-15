package io.github.barniebarnie.antcraft.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Expected values match vanilla mining times for a player without effects. */
class BreakTimeTest {
	@Test
	void logByHand() {
		assertEquals(60, BreakTime.ticks(2.0F, 1.0F, true));
	}

	@Test
	void dirtByHand() {
		assertEquals(15, BreakTime.ticks(0.5F, 1.0F, true));
	}

	@Test
	void stoneByHandIsSlowBecauseItNeedsAPickaxe() {
		assertEquals(150, BreakTime.ticks(1.5F, 1.0F, false));
	}

	@Test
	void stoneWithWoodenPickaxe() {
		assertEquals(23, BreakTime.ticks(1.5F, 2.0F, true));
	}

	@Test
	void instantBlocksTakeOneTick() {
		assertEquals(1, BreakTime.ticks(0.0F, 1.0F, true));
	}

	@Test
	void unbreakableIsNegative() {
		assertEquals(-1, BreakTime.ticks(-1.0F, 1.0F, true));
	}
}
