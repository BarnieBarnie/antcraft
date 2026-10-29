package io.github.barniebarnie.antcraft.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Where server tick time goes, for {@code /antcraft profile} and the bot benchmark: the average and worst server tick,
 * and per part of the mod's work (pathfinding, a bot's job choice, a colony's survey, ...) the time per tick, the worst
 * tick and the calls. Measures nothing until {@link #start started}. Parts may nest, such as pathfinding inside a bot's
 * work, so their times overlap and do not add up to the tick. Server thread only.
 */
public final class TickProfiler {
	/** The server's profiler; {@code /antcraft profile} starts and stops it. */
	public static final TickProfiler SERVER = new TickProfiler(System::nanoTime);
	/** What {@link #begin} returns while not running, which {@link #end} ignores. */
	private static final long OFF = Long.MIN_VALUE;
	private static final double NANOS_PER_MS = 1_000_000.0;

	/** One part's time, in nanoseconds. */
	private static final class Part {
		long total;
		long calls;
		long thisTick;
		long worstTick;
	}

	/** What one part cost: per tick on average, in the worst tick, in all, and how often it ran. */
	public record PartReport(String name, long calls, double msPerTick, double worstTickMs, double totalMs) {
	}

	/**
	 * The server ticks measured and the parts, the costliest in all first. {@code p99TickMs} is the 99th percentile tick
	 * (nearest rank), steadier between runs than the single worst (#399). {@code worstTick} is which tick was the worst,
	 * counted from 1 since started, which tells a slow start, such as code the JVM has not compiled yet, from a spike later on.
	 */
	public record Report(long ticks, double averageTickMs, double p99TickMs, double worstTickMs, long worstTick, List<PartReport> parts) {
		/** The report as lines of text: the ticks, then a line per part. */
		public List<String> lines() {
			List<String> lines = new ArrayList<>();
			lines.add(String.format(Locale.ROOT, "%d ticks: average %.2f ms, p99 %.2f ms, worst %.2f ms (tick %d)", this.ticks, this.averageTickMs, this.p99TickMs, this.worstTickMs,
				this.worstTick));
			for (PartReport part : this.parts) {
				lines.add(String.format(Locale.ROOT, "%s: %.2f ms/tick, worst %.2f ms, %d calls", part.name(), part.msPerTick(), part.worstTickMs(),
					part.calls()));
			}
			return lines;
		}
	}

	private final LongSupplier clock;
	private final Map<String, Part> parts = new HashMap<>();
	private boolean running;
	private long tickStart = OFF;
	private long ticks;
	private long tickTotal;
	private long worstTick;
	private long worstTickAt;
	/** Each tick's time, for the percentile. */
	private final List<Long> tickTimes = new ArrayList<>();

	public TickProfiler(LongSupplier clock) {
		this.clock = clock;
	}

	/** Forgets what was measured and starts measuring. */
	public void start() {
		this.parts.clear();
		this.ticks = 0;
		this.tickTotal = 0;
		this.worstTick = 0;
		this.worstTickAt = 0;
		this.tickTimes.clear();
		this.tickStart = OFF;
		this.running = true;
	}

	/** Stops measuring; the report keeps what was measured. */
	public void stop() {
		this.running = false;
	}

	public boolean running() {
		return this.running;
	}

	/** Call as a server tick starts. */
	public void startTick() {
		this.tickStart = this.begin();
	}

	/** Call as a server tick ends: counts the tick, and each part's time in it toward its worst tick. */
	public void endTick() {
		if (!this.running || this.tickStart == OFF) {
			return;
		}
		long time = this.clock.getAsLong() - this.tickStart;
		this.tickStart = OFF;
		this.ticks++;
		this.tickTotal += time;
		this.tickTimes.add(time);
		if (time > this.worstTick) {
			this.worstTick = time;
			this.worstTickAt = this.ticks;
		}
		for (Part part : this.parts.values()) {
			part.worstTick = Math.max(part.worstTick, part.thisTick);
			part.thisTick = 0;
		}
	}

	/** The start of a part's call, to pass to {@link #end}. */
	public long begin() {
		return this.running ? this.clock.getAsLong() : OFF;
	}

	/** Counts the time since {@code begin} toward {@code part}; nothing if not running at {@code begin}. */
	public void end(String part, long begin) {
		if (!this.running || begin == OFF) {
			return;
		}
		long time = this.clock.getAsLong() - begin;
		Part counted = this.parts.computeIfAbsent(part, name -> new Part());
		counted.total += time;
		counted.thisTick += time;
		counted.calls++;
	}

	/** Runs {@code work}, counting its time toward {@code part} of the server's profiler. */
	public static <T> T time(String part, Supplier<T> work) {
		long begin = SERVER.begin();
		try {
			return work.get();
		} finally {
			SERVER.end(part, begin);
		}
	}

	/** Runs {@code work}, counting its time toward {@code part} of the server's profiler. */
	public static void time(String part, Runnable work) {
		long begin = SERVER.begin();
		try {
			work.run();
		} finally {
			SERVER.end(part, begin);
		}
	}

	public Report report() {
		long perTick = Math.max(1, this.ticks);
		List<PartReport> parts = this.parts.entrySet().stream()
			.map(entry -> new PartReport(entry.getKey(), entry.getValue().calls, entry.getValue().total / NANOS_PER_MS / perTick,
				Math.max(entry.getValue().worstTick, entry.getValue().thisTick) / NANOS_PER_MS, entry.getValue().total / NANOS_PER_MS))
			.sorted(Comparator.comparingDouble(PartReport::totalMs).reversed().thenComparing(PartReport::name)).toList();
		List<Long> sorted = this.tickTimes.stream().sorted().toList();
		double p99 = sorted.isEmpty() ? 0 : sorted.get((int) Math.ceil(0.99 * sorted.size()) - 1) / NANOS_PER_MS;
		return new Report(this.ticks, this.tickTotal / NANOS_PER_MS / perTick, p99, this.worstTick / NANOS_PER_MS, this.worstTickAt, parts);
	}
}
