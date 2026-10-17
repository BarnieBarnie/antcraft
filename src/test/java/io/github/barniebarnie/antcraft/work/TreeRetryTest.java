package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class TreeRetryTest {
	private static final BlockPos BASE = new BlockPos(4, 1, 4);
	private static final List<BlockPos> LOGS = List.of(BASE.above(5), BASE.above(6));

	@Test
	void firstFailedVisitRetriesAfterTheWait() {
		TreeRetry retry = TreeRetry.after(null, BASE, LOGS, 100L, 600L, 5);

		assertEquals(new TreeRetry(BASE, LOGS, 1, 700L), retry);
		assertFalse(retry.isDue(699L));
		assertTrue(retry.isDue(700L));
		assertFalse(retry.givenUp());
	}

	@Test
	void givesUpAfterTheLastTry() {
		TreeRetry retry = null;
		for (int visit = 1; visit < 5; visit++) {
			retry = TreeRetry.after(retry, BASE, LOGS, visit * 1000L, 600L, 5);
			assertFalse(retry.givenUp(), "given up after visit " + visit);
		}
		TreeRetry last = TreeRetry.after(retry, BASE, LOGS, 9000L, 600L, 5);

		assertTrue(last.givenUp());
		assertEquals(5, last.tries());
		assertFalse(last.isDue(Long.MAX_VALUE - 1));
	}

	@Test
	void keepsTheLogsStillLeftAfterTheLatestVisit() {
		TreeRetry first = TreeRetry.after(null, BASE, LOGS, 0L, 600L, 5);
		TreeRetry second = TreeRetry.after(first, BASE, LOGS.subList(1, 2), 700L, 600L, 5);

		assertEquals(new TreeRetry(BASE, LOGS.subList(1, 2), 2, 1300L), second);
	}

	@Test
	void roundTripsThroughItsCodec() {
		TreeRetry retry = new TreeRetry(BASE, LOGS, 3, 1234L);

		var json = TreeRetry.CODEC.encodeStart(JsonOps.INSTANCE, retry).getOrThrow();

		assertEquals(retry, TreeRetry.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}
}
