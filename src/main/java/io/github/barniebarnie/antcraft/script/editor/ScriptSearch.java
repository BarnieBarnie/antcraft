package io.github.barniebarnie.antcraft.script.editor;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The bots screen's script picker search (docs/features/scripts.md, The bots screen). Unlike the editor's completion
 * ({@link Completions#matching}) it finds a name by any part of it, as one looks for a script to run.
 */
public final class ScriptSearch {
	private ScriptSearch() {
	}

	/**
	 * The names in {@code names} containing {@code search}, in any case: runnable scripts before library modules
	 * ({@link #isLibrary}), then those starting with it, then alphabetically in any case; with nothing typed, every name.
	 */
	public static List<String> matching(String search, Collection<String> names) {
		String wanted = search.toLowerCase(Locale.ROOT);
		return names.stream()
			.filter(name -> name.toLowerCase(Locale.ROOT).contains(wanted))
			.sorted(Comparator.comparing(ScriptSearch::isLibrary)
				.thenComparing((String name) -> !name.toLowerCase(Locale.ROOT).startsWith(wanted))
				.thenComparing(String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	/**
	 * Whether {@code name} is a module other scripts import, not one to run: the {@code lib} package and the
	 * {@code common} module of a package such as {@code colony.common}. Listed last rather than left out, so the picker
	 * shows every script {@code script list} does, and a script this guess gets wrong only moves down.
	 */
	static boolean isLibrary(String name) {
		return name.startsWith("lib.") || name.endsWith(".common");
	}
}
