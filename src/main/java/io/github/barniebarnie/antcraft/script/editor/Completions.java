package io.github.barniebarnie.antcraft.script.editor;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** The names the editor suggests while typing (docs/features/scripts.md, Editing in game). */
public final class Completions {
	private Completions() {
	}

	/**
	 * The names in {@code names} that complete the word before {@code caret}: those starting with it in the same case
	 * first, then those starting with it in another case, each shortest first, then alphabetically; not the word itself.
	 * None in a string or comment ({@code spans} are the source's {@link Highlighter#spans}).
	 */
	public static List<String> suggest(String source, int caret, List<Highlighter.Span> spans, Collection<String> names) {
		String prefix = prefix(source, caret);
		if (prefix.isEmpty() || inStringOrComment(spans, caret)) {
			return List.of();
		}
		return names.stream()
			.filter(name -> name.length() > prefix.length() && name.regionMatches(true, 0, prefix, 0, prefix.length()))
			.sorted(Comparator.comparing((String name) -> !name.startsWith(prefix))
				.thenComparingInt(String::length)
				.thenComparing(Comparator.naturalOrder()))
			.toList();
	}

	/**
	 * The word before {@code caret}: name characters and dots (a module such as {@code lib.trees}), not starting with a
	 * digit or a dot; empty if there is none.
	 */
	public static String prefix(String source, int caret) {
		int start = caret;
		while (start > 0 && (CodeText.isWord(source.charAt(start - 1)) || source.charAt(start - 1) == '.')) {
			start--;
		}
		while (start < caret && source.charAt(start) == '.') {
			start++;
		}
		if (start == caret || Character.isDigit(source.charAt(start)) || start > 0 && source.charAt(start - 1) == '.') {
			return "";
		}
		return source.substring(start, caret);
	}

	private static boolean inStringOrComment(List<Highlighter.Span> spans, int caret) {
		for (Highlighter.Span span : spans) {
			if ((span.style() == Highlighter.Style.STRING || span.style() == Highlighter.Style.COMMENT)
				&& span.start() < caret && caret <= span.end()) {
				return true;
			}
		}
		return false;
	}
}
