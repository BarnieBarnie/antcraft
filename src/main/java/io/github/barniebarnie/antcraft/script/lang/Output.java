package io.github.barniebarnie.antcraft.script.lang;

/** Where a script's {@code print} goes: one call per line printed, without the line break. */
@FunctionalInterface
public interface Output {
	void print(String line);
}
