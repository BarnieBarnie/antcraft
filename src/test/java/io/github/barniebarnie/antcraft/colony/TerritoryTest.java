package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
	void anAreaIsInsideOnlyIfAllOfItIs() {
		// What /antcraft colony terraform may level: any corner order, any height.
		assertTrue(TERRITORY.contains(new BlockPos(110, 0, 110), new BlockPos(90, 200, 90)));
		assertFalse(TERRITORY.contains(new BlockPos(95, 64, 95), new BlockPos(111, 64, 105)), "leaves on one side");
		assertFalse(TERRITORY.contains(new BlockPos(89, 64, 95), new BlockPos(105, 64, 105)), "leaves on the other");
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
	void idleBotInTheMineStays() {
		// Branches reach far past the radius, deep below; a bot there is in the colony's mine.
		Territory territory = new Territory(new BlockPos(100, 64, 100), 10, null, Set.of(new BlockPos(150, -30, 100)));
		assertFalse(territory.shouldReturnHome(new BlockPos(150, -30, 100), false, false));
		assertTrue(territory.shouldReturnHome(new BlockPos(150, 64, 100), false, false), "on the surface above the mine");
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

	@Test
	void mayTargetMobsInTheMine() {
		// Bots defend the members mining far branches, past the radius.
		Territory territory = new Territory(new BlockPos(100, 64, 100), 10, null, Set.of(new BlockPos(150, -30, 100)));
		assertTrue(territory.mayTarget(new BlockPos(150, -30, 100)));
		assertFalse(territory.mayTarget(new BlockPos(150, 64, 100)), "on the surface above the mine");
	}

	@Test
	void boundsClipTheSquareSideways() {
		// Game tests clip each colony to its test area, so it cannot reach into the tests beside it.
		Territory clipped = new Territory(new BlockPos(100, 64, 100), 10, new BoundingBox(95, 60, 95, 102, 67, 102), Set.of());
		assertTrue(clipped.contains(new BlockPos(102, 64, 95)));
		assertFalse(clipped.contains(new BlockPos(103, 64, 100)), "outside the bounds, inside the radius");
		assertFalse(clipped.contains(new BlockPos(100, 64, 94)), "outside the bounds, inside the radius");
		assertTrue(clipped.contains(new BlockPos(100, -40, 100)), "height still does not matter");
	}
}
