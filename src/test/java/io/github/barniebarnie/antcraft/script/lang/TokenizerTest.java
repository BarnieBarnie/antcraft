package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.barniebarnie.antcraft.script.lang.Token.Kind;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TokenizerTest {
	/** The tokens of a source, one word each: kinds for layout tokens, text for the rest. */
	private static String kinds(String source) {
		return Tokenizer.tokenize("t.py", source).stream().map(TokenizerTest::word).collect(Collectors.joining(" "));
	}

	private static String word(Token token) {
		return switch (token.kind()) {
			case NEWLINE, INDENT, DEDENT, EOF -> token.kind().name();
			default -> token.text();
		};
	}

	private static Object value(String source) {
		return Tokenizer.tokenize("t.py", source).getFirst().value();
	}

	@Test
	void indentedBlocksGiveIndentAndDedentTokens() {
		assertEquals("if a : NEWLINE INDENT b NEWLINE if c : NEWLINE INDENT d NEWLINE DEDENT DEDENT e NEWLINE EOF",
				kinds("if a:\n    b\n    if c:\n        d\ne\n"));
	}

	@Test
	void blankAndCommentOnlyLinesDoNotChangeTheIndentation() {
		assertEquals("if a : NEWLINE INDENT b NEWLINE c NEWLINE DEDENT d NEWLINE EOF",
				kinds("if a:\n    b\n\n# a comment at column 1\n        # deeper comment\n   \n    c  # trailing\nd"));
	}

	@Test
	void theLastLineNeedsNoNewlineAndOpenBlocksCloseAtTheEnd() {
		assertEquals("while x : NEWLINE INDENT y NEWLINE DEDENT EOF", kinds("while x:\n\ty"));
	}

	@Test
	void anEmptySourceIsJustTheEnd() {
		assertEquals("EOF", kinds(""));
		assertEquals("EOF", kinds("\n# only a comment\n\n"));
	}

	@Test
	void bracketsJoinLinesAndIgnoreIndentationInside() {
		assertEquals("x = [ 1 , 2 , ( 3 , 4 ) ] NEWLINE y NEWLINE EOF",
				kinds("x = [1,\n        2, (3,\n  # comment inside\n4)]\ny"));
	}

	@Test
	void aBackslashJoinsTheNextLine() {
		assertEquals("x = 1 + 2 NEWLINE EOF", kinds("x = 1 + \\\n        2\n"));
	}

	@Test
	void tokensKnowTheirLineAndColumn() {
		List<Token> tokens = Tokenizer.tokenize("t.py", "a = 1\nif b:\n    c(d)\n");
		Token c = tokens.stream().filter(t -> t.text().equals("c")).findFirst().orElseThrow();
		assertEquals(3, c.line());
		assertEquals(5, c.col());
		Token d = tokens.stream().filter(t -> t.text().equals("d")).findFirst().orElseThrow();
		assertEquals(7, d.col());
	}

	@Test
	void operatorsTakeTheLongestMatch() {
		assertEquals("a **= b // c ** d <= e != f == g -> h NEWLINE EOF", kinds("a **= b // c ** d <= e != f == g -> h"));
	}

	@Test
	void integerLiterals() {
		assertEquals(42L, value("42"));
		assertEquals(1_000_000L, value("1_000_000"));
		assertEquals(255L, value("0xff"));
		assertEquals(8L, value("0o10"));
		assertEquals(5L, value("0b101"));
		assertEquals(0L, value("0"));
		assertEquals(0L, value("00"));
		assertEquals(Long.MAX_VALUE, value("9223372036854775807"));
		assertEquals(Kind.INT, Tokenizer.tokenize("t.py", "7").getFirst().kind());
	}

	@Test
	void floatLiterals() {
		assertEquals(1.5, value("1.5"));
		assertEquals(0.5, value(".5"));
		assertEquals(2.0, value("2."));
		assertEquals(1e10, value("1e10"));
		assertEquals(1.5e-3, value("1.5E-3"));
		assertEquals(Kind.FLOAT, Tokenizer.tokenize("t.py", "1.0").getFirst().kind());
	}

	@Test
	void stringLiterals() {
		assertEquals("abc", value("'abc'"));
		assertEquals("it's", value("\"it's\""));
		assertEquals("a\nb\tc\\d'e\"f", value("'a\\nb\\tc\\\\d\\'e\\\"f'"));
		assertEquals("A\u00e9\u20ac\uD83D\uDE00\0", value("'\\x41\\u00e9\\u20AC\\U0001F600\\0'"));
		assertEquals("ab", value("'a\\\nb'"));
		assertEquals("\\d\\n", value("r'\\d\\n'"));
		assertEquals("u", value("u'u'"));
		assertEquals("line 1\n  line 2 'q' \"q\"\n", value("'''line 1\n  line 2 'q' \"q\"\n'''"));
		assertEquals("x\"\"y", value("\"\"\"x\"\"y\"\"\""));
		assertEquals(Kind.STRING, Tokenizer.tokenize("t.py", "'s'").getFirst().kind());
	}

	@Test
	void aTripleQuotedStringKeepsLineCountingRight() {
		List<Token> tokens = Tokenizer.tokenize("t.py", "s = '''a\nb\n'''\nx");
		Token x = tokens.stream().filter(t -> t.text().equals("x")).findFirst().orElseThrow();
		assertEquals(4, x.line());
		assertEquals(1, x.col());
	}

	@Test
	void fStringsAreSplitIntoTextAndFields() {
		Token token = Tokenizer.tokenize("t.py", "f'a{x}b{{c}}{y!r:>5}{d[1:2]}'").getFirst();
		assertEquals(Kind.FSTRING, token.kind());
		@SuppressWarnings("unchecked")
		List<Token.FPiece> pieces = (List<Token.FPiece>) token.value();
		assertEquals(List.of(
				new Token.Text("a"),
				new Token.Field("x", 1, 5, '\0', null),
				new Token.Text("b{c}"),
				new Token.Field("y", 1, 14, 'r', ">5"),
				new Token.Field("d[1:2]", 1, 22, '\0', null)), pieces);
	}

	@Test
	void fStringFieldsMayHoldStringsAndBrackets() {
		Token token = Tokenizer.tokenize("t.py", "f\"{d['}']} {x}\"").getFirst();
		@SuppressWarnings("unchecked")
		List<Token.FPiece> pieces = (List<Token.FPiece>) token.value();
		assertEquals(new Token.Field("d['}']", 1, 4, '\0', null), pieces.getFirst());
	}

	@Test
	void unterminatedStringsAreErrors() {
		CompileError error = assertThrows(CompileError.class, () -> Tokenizer.tokenize("t.py", "x = 'abc\ny"));
		assertEquals("t.py:1:5: unterminated string literal", error.getMessage());
		error = assertThrows(CompileError.class, () -> Tokenizer.tokenize("t.py", "x = '''abc\ny"));
		assertEquals("t.py:1:5: unterminated triple-quoted string literal", error.getMessage());
	}

	@Test
	void anIntegerTooLargeForSixtyFourBitsIsAnError() {
		CompileError error = assertThrows(CompileError.class, () -> Tokenizer.tokenize("t.py", "x = 9223372036854775808"));
		assertEquals("t.py:1:5: integer literal is too large (ints are 64-bit)", error.getMessage());
	}
}
