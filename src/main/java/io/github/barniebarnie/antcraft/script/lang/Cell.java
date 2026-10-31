package io.github.barniebarnie.antcraft.script.lang;

import org.jspecify.annotations.Nullable;

/**
 * A variable that a nested function shares with the function that defines it, as CPython's cells: both reach it
 * through the cell, so an assignment by one is seen by the other (docs/language/functions.md, Closures).
 */
final class Cell {
	/** Null while the variable is not set. */
	@Nullable Object value;

	Cell(@Nullable Object value) {
		this.value = value;
	}
}
