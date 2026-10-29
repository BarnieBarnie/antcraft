package io.github.barniebarnie.antcraft.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TickProfilerTest {
	private static final long MS = 1_000_000;

	/** A clock the test moves by hand. */
	private static final class Clock {
		long now;
	}

	@Test
	void reportsTheAverageAndWorstTickAndWhatEachPartCost() {
		Clock clock = new Clock();
		TickProfiler profiler = new TickProfiler(() -> clock.now);
		profiler.start();

		// Tick 1: 10 ms, of which pathing 4 ms over two calls.
		profiler.startTick();
		long a = profiler.begin();
		clock.now += 3 * MS;
		profiler.end("path", a);
		long b = profiler.begin();
		clock.now += MS;
		profiler.end("path", b);
		clock.now += 6 * MS;
		profiler.endTick();
		// Tick 2: 30 ms, pathing 2 ms and a survey 20 ms.
		profiler.startTick();
		long c = profiler.begin();
		clock.now += 2 * MS;
		profiler.end("path", c);
		long d = profiler.begin();
		clock.now += 20 * MS;
		profiler.end("survey", d);
		clock.now += 8 * MS;
		profiler.endTick();

		TickProfiler.Report report = profiler.report();
		assertEquals(2, report.ticks());
		assertEquals(20.0, report.averageTickMs(), 1e-9);
		assertEquals(30.0, report.worstTickMs(), 1e-9);
		// Which tick was the worst, counted from 1 since started, to tell a slow start from a spike later on.
		assertEquals(2, report.worstTick());
		// The costliest part first.
		assertEquals(List.of("survey", "path"), report.parts().stream().map(TickProfiler.PartReport::name).toList());
		TickProfiler.PartReport path = report.parts().get(1);
		assertEquals(3, path.calls());
		assertEquals(6.0, path.totalMs(), 1e-9);
		assertEquals(3.0, path.msPerTick(), 1e-9);
		assertEquals(4.0, path.worstTickMs(), 1e-9);
		TickProfiler.PartReport survey = report.parts().get(0);
		assertEquals(10.0, survey.msPerTick(), 1e-9);
		assertEquals(20.0, survey.worstTickMs(), 1e-9);
	}

	@Test
	void measuresNothingUntilStartedAndAfterStopped() {
		Clock clock = new Clock();
		TickProfiler profiler = new TickProfiler(() -> clock.now);
		assertFalse(profiler.running());
		profiler.startTick();
		long a = profiler.begin();
		clock.now += 5 * MS;
		profiler.end("path", a);
		profiler.endTick();
		assertEquals(0, profiler.report().ticks());
		assertTrue(profiler.report().parts().isEmpty());

		// Started halfway through a part: that call is not counted.
		long b = profiler.begin();
		profiler.start();
		clock.now += 5 * MS;
		profiler.end("path", b);
		assertTrue(profiler.report().parts().isEmpty());

		profiler.stop();
		profiler.startTick();
		clock.now += 5 * MS;
		profiler.endTick();
		assertEquals(0, profiler.report().ticks());
	}

	@Test
	void startingAgainForgetsWhatWasMeasured() {
		Clock clock = new Clock();
		TickProfiler profiler = new TickProfiler(() -> clock.now);
		profiler.start();
		profiler.startTick();
		long a = profiler.begin();
		clock.now += 5 * MS;
		profiler.end("path", a);
		profiler.endTick();
		profiler.start();
		assertEquals(0, profiler.report().ticks());
		assertTrue(profiler.report().parts().isEmpty());
	}

	/**
	 * The 99th percentile tick (nearest rank), steadier between runs than the single worst tick (#399): of 99 ticks of 1 ms
	 * and one of 50 ms, the p99 is 1 ms and the worst 50 ms.
	 */
	@Test
	void reportsThe99thPercentileTick() {
		Clock clock = new Clock();
		TickProfiler profiler = new TickProfiler(() -> clock.now);
		profiler.start();
		for (int i = 0; i < 100; i++) {
			profiler.startTick();
			clock.now += i == 40 ? 50 * MS : MS;
			profiler.endTick();
		}
		TickProfiler.Report report = profiler.report();
		assertEquals(1.0, report.p99TickMs(), 1e-9);
		assertEquals(50.0, report.worstTickMs(), 1e-9);
		// Two slow ticks of the hundred reach the p99.
		profiler.startTick();
		clock.now += 30 * MS;
		profiler.endTick();
		assertEquals(30.0, profiler.report().p99TickMs(), 1e-9);
		// Starting again forgets the ticks.
		profiler.start();
		assertEquals(0.0, profiler.report().p99TickMs(), 1e-9);
	}

	@Test
	void theReportReadsAsLines() {
		Clock clock = new Clock();
		TickProfiler profiler = new TickProfiler(() -> clock.now);
		profiler.start();
		profiler.startTick();
		long a = profiler.begin();
		clock.now += 2 * MS;
		profiler.end("path", a);
		profiler.endTick();
		List<String> lines = profiler.report().lines();
		assertEquals("1 ticks: average 2.00 ms, p99 2.00 ms, worst 2.00 ms (tick 1)", lines.getFirst());
		assertEquals("path: 2.00 ms/tick, worst 2.00 ms, 1 calls", lines.get(1));
	}
}
