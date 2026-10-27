package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The block search behind {@code scan} and {@code find_nearest}: nearest first, up to a limit, a budget at a time. */
class BlockScanTest {
	private static final Pos CENTER = new Pos(10, 64, -5);
	private static final Set<Pos> TARGETS = Set.of(new Pos(13, 64, -5), new Pos(10, 65, -5), new Pos(9, 62, -4), new Pos(10, 64, 10));

	private static BlockScan scan(int radius, int limit) {
		return new BlockScan(CENTER, radius, limit, (x, y, z) -> TARGETS.contains(new Pos(x, y, z)));
	}

	@Test
	void findsMatchesWithinTheRadiusNearestFirst() {
		BlockScan scan = scan(5, 64);
		assertTrue(scan.step(Integer.MAX_VALUE));
		assertEquals(List.of(new Pos(10, 65, -5), new Pos(9, 62, -4), new Pos(13, 64, -5)), scan.found());
	}

	@Test
	void stopsAtTheLimitWithoutReadingFurther() {
		BlockScan whole = scan(5, 64);
		whole.step(Integer.MAX_VALUE);
		BlockScan limited = scan(5, 2);
		assertTrue(limited.step(Integer.MAX_VALUE));
		assertEquals(List.of(new Pos(10, 65, -5), new Pos(9, 62, -4)), limited.found());
		assertTrue(limited.reads() < whole.reads(), limited.reads() + " reads with a limit, " + whole.reads() + " without");
	}

	@Test
	void readsOnlyTheSphereOfTheRadius() {
		BlockScan scan = scan(2, 64);
		scan.step(Integer.MAX_VALUE);
		// Blocks within distance 2 of the center: 1 + 6 + 12 + 8 + 6 = 33.
		assertEquals(33, scan.reads());
		assertEquals(List.of(new Pos(10, 65, -5)), scan.found());
	}

	@Test
	void continuesWhereABudgetRanOut() {
		BlockScan scan = scan(5, 64);
		int steps = 0;
		while (!scan.step(7)) {
			steps++;
			assertTrue(scan.reads() == steps * 7, "read " + scan.reads() + " after " + steps + " steps");
		}
		BlockScan whole = scan(5, 64);
		whole.step(Integer.MAX_VALUE);
		assertEquals(whole.found(), scan.found());
		assertEquals(whole.reads(), scan.reads());
		assertFalse(steps == 0);
	}

	@Test
	void aZeroLimitFindsNothing() {
		BlockScan scan = scan(5, 0);
		assertTrue(scan.step(10));
		assertEquals(List.of(), scan.found());
		assertEquals(0, scan.reads());
	}
}
