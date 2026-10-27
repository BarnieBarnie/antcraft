package io.github.barniebarnie.antcraft.script.lang;

import java.util.function.Consumer;

/**
 * Where a script's {@code print}, {@code debug}, {@code info}, {@code warn} and {@code error} go: one call per line, without
 * the line break, with the level of the function that wrote it.
 */
@FunctionalInterface
public interface Output {
	/**
	 * The level of a line, lowest first. {@link #PRINT} is meant for the player and is always shown, so it comes above
	 * every log level.
	 */
	enum Level {
		DEBUG,
		INFO,
		WARN,
		ERROR,
		PRINT
	}

	void write(Level level, String line);

	/** An output that passes every line to {@code sink}, whatever its level. */
	static Output lines(Consumer<String> sink) {
		return (level, line) -> sink.accept(line);
	}
}
