package io.github.barniebarnie.antcraft.script.editor;

import io.github.barniebarnie.antcraft.script.lang.CompileError;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import io.github.barniebarnie.antcraft.script.lang.Token;
import io.github.barniebarnie.antcraft.script.lang.Tokenizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The editor's colours (docs/features/scripts.md, Editing in game): the script {@link Tokenizer} splits the source, so
 * colours follow the language's rules, and text it cannot read yet, such as a string still being typed, does not stop
 * the lines after it from being coloured.
 */
public final class Highlighter {
	/** What a piece of text is, with its colour (ARGB). */
	public enum Style {
		PLAIN(0xFFE0E0E0),
		KEYWORD(0xFFCC88FF),
		STRING(0xFF88DD88),
		NUMBER(0xFF66CCFF),
		COMMENT(0xFF8A8A8A),
		NATIVE(0xFFFFCC55);

		private final int color;

		Style(int color) {
			this.color = color;
		}

		public int color() {
			return color;
		}
	}

	/** Characters {@code start} (inclusive) to {@code end} (exclusive) of the source have {@code style}. */
	public record Span(int start, int end, Style style) {
	}

	private Highlighter() {
	}

	/**
	 * The coloured pieces of {@code source} (lines ended by {@code \n}), in order and not overlapping; text in none of
	 * them is {@link Style#PLAIN}. Names in {@code natives} get {@link Style#NATIVE}.
	 *
	 * <p>Where the tokenizer stops at an error, the rest of that line is a string if the error is an unterminated string
	 * (the rest of the source for a triple-quoted one), else plain, and tokenizing starts again on the next line; on the
	 * line of an indentation error, since a fresh start has no indentation to mismatch.
	 */
	public static List<Span> spans(String source, Set<String> natives) {
		List<Span> spans = new ArrayList<>();
		List<Span> strings = walk(source, (start, token) -> {
			Style style = style(token, natives);
			if (style != Style.PLAIN) {
				spans.add(new Span(start, start + token.text().length(), style));
			}
		});
		spans.addAll(strings);
		spans.sort(Comparator.comparingInt(Span::start));
		addComments(source, spans);
		return spans;
	}

	/** The names {@code source} uses that are not keywords: those it defines among them, for suggestions. */
	public static Set<String> names(String source) {
		Set<String> names = new HashSet<>();
		walk(source, (start, token) -> {
			if (token.kind() == Token.Kind.NAME && !Parser.KEYWORDS.contains(token.text())) {
				names.add(token.text());
			}
		});
		return names;
	}

	/**
	 * Gives {@code visitor} each token of {@code source} with text, and where it starts, starting again after errors as
	 * {@link #spans} says; returns the string spans of unterminated strings.
	 */
	private static List<Span> walk(String source, BiConsumer<Integer, Token> visitor) {
		List<Integer> lineStarts = new ArrayList<>();
		lineStarts.add(0);
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				lineStarts.add(i + 1);
			}
		}
		List<Span> spans = new ArrayList<>();
		int firstLine = 0;
		while (firstLine < lineStarts.size()) {
			int from = lineStarts.get(firstLine);
			Tokenizer.Partial partial = Tokenizer.tokenizeUntilError(source.substring(from));
			int end = from;
			for (Token token : partial.tokens()) {
				if (token.text().isEmpty()) {
					continue;
				}
				int start = lineStarts.get(firstLine + token.line() - 1) + token.col() - 1;
				end = Math.max(end, start + token.text().length());
				visitor.accept(start, token);
			}
			CompileError error = partial.error();
			if (error == null) {
				break;
			}
			int errorLine = firstLine + error.line() - 1;
			int at = lineStarts.get(errorLine) + error.column() - 1;
			if (error.detail().startsWith("unindent")) {
				firstLine = errorLine;
				continue;
			}
			if (error.detail().startsWith("unterminated triple-quoted")) {
				spans.add(new Span(at, source.length(), Style.STRING));
				break;
			}
			if (error.detail().startsWith("unterminated string")) {
				spans.add(new Span(at, lineEnd(source, at), Style.STRING));
			}
			int last = Math.max(at, end - 1);
			firstLine = lineIndex(lineStarts, last) + 1;
		}
		return spans;
	}

	private static Style style(Token token, Set<String> natives) {
		return switch (token.kind()) {
			case NAME -> Parser.KEYWORDS.contains(token.text()) ? Style.KEYWORD
				: natives.contains(token.text()) ? Style.NATIVE : Style.PLAIN;
			case INT, FLOAT -> Style.NUMBER;
			case STRING, FSTRING -> Style.STRING;
			default -> Style.PLAIN;
		};
	}

	/**
	 * Adds a comment span for each {@code #} outside the strings; the tokenizer skips comments, and a {@code #} can be in
	 * no other token.
	 */
	private static void addComments(String source, List<Span> spans) {
		List<Span> strings = spans.stream().filter(s -> s.style() == Style.STRING).toList();
		List<Span> comments = new ArrayList<>();
		int s = 0;
		for (int i = source.indexOf('#'); i >= 0; i = source.indexOf('#', i)) {
			while (s < strings.size() && strings.get(s).end() <= i) {
				s++;
			}
			if (s < strings.size() && strings.get(s).start() <= i) {
				i = strings.get(s).end();
				continue;
			}
			int end = lineEnd(source, i);
			comments.add(new Span(i, end, Style.COMMENT));
			i = end;
		}
		spans.addAll(comments);
		spans.sort(Comparator.comparingInt(Span::start));
	}

	private static int lineEnd(String source, int at) {
		int end = source.indexOf('\n', at);
		return end < 0 ? source.length() : end;
	}

	private static int lineIndex(List<Integer> lineStarts, int offset) {
		int found = java.util.Collections.binarySearch(lineStarts, offset);
		return found >= 0 ? found : -found - 2;
	}
}
