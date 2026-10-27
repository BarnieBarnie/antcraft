package io.github.barniebarnie.antcraft.script.lang;

/** A script {@code range}; {@code step} is never 0. */
public record PyRange(long start, long stop, long step) {
	public PyRange {
		if (step == 0) {
			throw new ScriptError("range() arg 3 must not be zero");
		}
	}

	public long length() {
		if (step > 0) {
			return stop > start ? (stop - start - 1) / step + 1 : 0;
		}
		return start > stop ? (start - stop - 1) / -step + 1 : 0;
	}

	public long get(long index) {
		return start + index * step;
	}

	public boolean contains(long value) {
		if (step > 0 ? value < start || value >= stop : value > start || value <= stop) {
			return false;
		}
		return (value - start) % step == 0;
	}

	@Override
	public String toString() {
		return Values.repr(this);
	}
}
