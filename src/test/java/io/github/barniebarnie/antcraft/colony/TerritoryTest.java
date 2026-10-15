package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class TerritoryTest {
	private static final Territory TERRITORY = new Territory(new BlockPos(100, 64, 100), 10);

	@Test
	void containsIsAHorizontalSquareAroundTheCore() {
		assertTrue(TERRITORY.contains(new BlockPos(110, 64, 90)));
		assertFalse(TERRITORY.contains(new BlockPos(111, 64, 100)));
		assertFalse(TERRITORY.contains(new BlockPos(100, 64, 89)));
	}

	@Test
	void heightDoesNotMatter() {
		// Quarries go deep and trees grow tall, both well inside the colony.
		assertTrue(TERRITORY.contains(new BlockPos(100, -40, 100)));
		assertTrue(TERRITORY.contains(new BlockPos(100, 200, 100)));
	}

	@Test
	void idleBotOutsideReturnsHome() {
		assertTrue(TERRITORY.shouldReturnHome(new BlockPos(130, 64, 100), false, false));
	}

	@Test
	void botInsideStays() {
		assertFalse(TERRITORY.shouldReturnHome(new BlockPos(105, 64, 100), false, false));
	}

	@Test
	void fightingBotIsNotCalledBack() {
		assertFalse(TERRITORY.shouldReturnHome(new BlockPos(130, 64, 100), true, false));
	}

	@Test
	void workingBotIsNotCalledBack() {
		assertFalse(TERRITORY.shouldReturnHome(new BlockPos(130, 64, 100), false, true));
	}

	@Test
	void mayTargetMobsInside() {
		assertTrue(TERRITORY.mayTarget(new BlockPos(108, 64, 108)));
	}

	@Test
	void mobJustOutsideIsLeftAloneEvenFromJustInside() {
		// A bot at the border never starts a chase outside, so it cannot run out,
		// turn back at the border, spot the mob again and loop.
		assertTrue(TERRITORY.contains(new BlockPos(110, 64, 100)));
		assertFalse(TERRITORY.mayTarget(new BlockPos(111, 64, 100)));
	}
}
