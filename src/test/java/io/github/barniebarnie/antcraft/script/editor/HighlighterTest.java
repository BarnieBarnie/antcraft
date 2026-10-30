package io.github.barniebarnie.antcraft.script.editor;

import static io.github.barniebarnie.antcraft.script.editor.Highlighter.Style.COMMENT;
import static io.github.barniebarnie.antcraft.script.editor.Highlighter.Style.KEYWORD;
import static io.github.barniebarnie.antcraft.script.editor.Highlighter.Style.NATIVE;
import static io.github.barniebarnie.antcraft.script.editor.Highlighter.Style.NUMBER;
import static io.github.barniebarnie.antcraft.script.editor.Highlighter.Style.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.editor.Highlighter.Span;
import io.github.barniebarnie.antcraft.script.editor.Highlighter.Style;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The editor's colours, from the script tokenizer (docs/features/scripts.md, Editing in game). */
class HighlighterTest {
	private static Span span(String source, String text, Style style) {
		int start = source.indexOf(text);
		return new Span(start, start + text.length(), style);
	}

	@Test
	void aLineGetsKeywordStringNumberCommentAndNativeColours() {
		String source = "if x: say('a\\'b') + 12 # note 'not a string'";
		assertEquals(List.of(
			span(source, "if", KEYWORD),
			span(source, "say", NATIVE),
			span(source, "'a\\'b'", STRING),
			span(source, "12", NUMBER),
			span(source, "# note 'not a string'", COMMENT)), Highlighter.spans(source, Set.of("say")));
	}

	@Test
	void aHashInAStringIsNoComment() {
		String source = "s = f'# {x}' + 1.5";
		assertEquals(List.of(span(source, "f'# {x}'", STRING), span(source, "1.5", NUMBER)), Highlighter.spans(source, Set.of()));
	}

	@Test
	void anUnterminatedStringIsAStringToTheEndOfItsLineAndTheNextLinesAreColoured() {
		String source = "x = 'abc\ny = 1";
		assertEquals(List.of(new Span(4, 8, STRING), span(source, "1", NUMBER)), Highlighter.spans(source, Set.of()));
	}

	@Test
	void anUnterminatedTripleQuotedStringRunsToTheEnd() {
		String source = "x = '''abc\ny = 1";
		assertEquals(List.of(new Span(4, source.length(), STRING)), Highlighter.spans(source, Set.of()));
	}

	@Test
	void linesAfterAnErrorKeepTheirColoursWhateverTheirIndentation() {
		String source = "def f():\n    s = 'oops\n        if x:\n    return 1\n)\nwhile True:\n    pass";
		List<Span> spans = Highlighter.spans(source, Set.of());
		assertTrue(spans.contains(span(source, "return", KEYWORD)), spans.toString());
		int one = source.indexOf("1\n");
		assertTrue(spans.contains(new Span(one, one + 1, NUMBER)), spans.toString());
		assertTrue(spans.contains(span(source, "while", KEYWORD)), spans.toString());
		assertTrue(spans.contains(span(source, "pass", KEYWORD)), spans.toString());
	}

	@Test
	void anUnclosedBracketStillColoursWhatFollows() {
		String source = "print(1,\n  'two'";
		assertEquals(List.of(span(source, "print", NATIVE), span(source, "1", NUMBER), span(source, "'two'", STRING)),
			Highlighter.spans(source, Set.of("print")));
	}

	@Test
	void theStylesHaveDistinctColours() {
		assertEquals(Style.values().length, java.util.Arrays.stream(Style.values()).mapToInt(Style::color).distinct().count());
	}
}
