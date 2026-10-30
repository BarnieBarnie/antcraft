package io.github.barniebarnie.antcraft.script.lang;

import io.github.barniebarnie.antcraft.script.lang.Token.Kind;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Splits a script's source into Python 3 tokens. Lines are ended by NEWLINE, and a change of indentation gives
 * INDENT and DEDENT tokens as in Python. Blank and comment-only lines are skipped; inside brackets, and after a
 * backslash at the end of a line, lines are joined. Literals carry their value; an f-string carries its pieces,
 * whose fields the parser parses with {@link #tokenizeField}.
 */
public final class Tokenizer {
	private static final Set<String> STRING_PREFIXES = Set.of("r", "u", "f", "b", "br", "rb", "fr", "rf");
	private static final String[] OPERATORS = {
			"**=", "//=", ">>=", "<<=", "...",
			"->", ":=", "**", "//", "==", "!=", "<=", ">=", "<<", ">>",
			"+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "@=",
			"+", "-", "*", "/", "%", "<", ">", "=", "(", ")", "[", "]", "{", "}", ",", ":", ".", ";", "@", "&",
			"|", "^", "~"};

	private final String file;
	private final String src;
	private final boolean field;
	private int pos;
	private int line;
	private int col;
	private final List<Token> tokens = new ArrayList<>();
	private final Deque<Token> brackets = new ArrayDeque<>();
	private final Deque<Integer> indents = new ArrayDeque<>();

	private Tokenizer(String file, String src, int line, int col, boolean field) {
		this.file = file;
		this.src = src;
		this.line = line;
		this.col = col;
		this.field = field;
		indents.push(0);
	}

	/** The tokens of a whole file, ending with EOF. */
	public static List<Token> tokenize(String file, String source) {
		String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
		if (normalized.startsWith("﻿")) {
			normalized = normalized.substring(1);
		}
		return new Tokenizer(file, normalized, 1, 1, false).run();
	}

	/** The tokens a source has up to its first error, and that error, or null if it has none. */
	public record Partial(List<Token> tokens, @Nullable CompileError error) {
	}

	/**
	 * The tokens of {@code source} up to its first error, which it gives instead of throwing, for the editor's colours
	 * (docs/features/scripts.md, Editing in game). Unlike {@link #tokenize}, line ends must be {@code \n} already.
	 */
	public static Partial tokenizeUntilError(String source) {
		Tokenizer tokenizer = new Tokenizer("<editor>", source, 1, 1, false);
		try {
			return new Partial(tokenizer.run(), null);
		} catch (CompileError e) {
			return new Partial(List.copyOf(tokenizer.tokens), e);
		}
	}

	/**
	 * The tokens of an f-string field's expression, which starts at {@code line} and {@code col}: as inside brackets,
	 * there are no layout tokens, only EOF at the end.
	 */
	static List<Token> tokenizeField(String file, String source, int line, int col) {
		return new Tokenizer(file, source, line, col, true).run();
	}

	private List<Token> run() {
		boolean lineStart = !field;
		while (true) {
			if (lineStart) {
				if (!startLine()) {
					if (pos >= src.length()) {
						break;
					}
					continue;
				}
				lineStart = false;
			}
			if (pos >= src.length()) {
				break;
			}
			char c = cur();
			if (c == ' ' || c == '\t' || c == '\f') {
				advance();
			} else if (c == '#') {
				skipComment();
			} else if (c == '\n') {
				if (field || !brackets.isEmpty()) {
					advance();
				} else {
					add(Kind.NEWLINE, "", null, line, col);
					advance();
					lineStart = true;
				}
			} else if (c == '\\') {
				int l = line;
				int cl = col;
				advance();
				if (pos >= src.length()) {
					throw error(l, cl, "unexpected end of file after line continuation character");
				}
				if (cur() != '\n') {
					throw error(l, cl, "unexpected character after line continuation character");
				}
				advance();
			} else if (isIdentifierStart(c)) {
				identifierOrString();
			} else if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
				number();
			} else if (c == '\'' || c == '"') {
				string(pos, line, col, "");
			} else {
				operator();
			}
		}
		if (!brackets.isEmpty()) {
			Token open = brackets.peek();
			throw error(open.line(), open.col(), "'" + open.text() + "' was never closed");
		}
		if (!field) {
			if (!tokens.isEmpty() && tokens.getLast().kind() != Kind.NEWLINE) {
				add(Kind.NEWLINE, "", null, line, col);
			}
			while (indents.peek() > 0) {
				indents.pop();
				add(Kind.DEDENT, "", null, line, col);
			}
		}
		add(Kind.EOF, "", null, line, col);
		return tokens;
	}

	/**
	 * Measures the indentation of the line starting at {@code pos}. A blank or comment-only line is consumed and gives
	 * false; otherwise the INDENT or DEDENT tokens are added and it gives true.
	 */
	private boolean startLine() {
		int width = 0;
		while (pos < src.length()) {
			char c = cur();
			if (c == ' ') {
				width++;
			} else if (c == '\t') {
				width = (width / 8 + 1) * 8;
			} else if (c == '\f') {
				width = 0;
			} else {
				break;
			}
			advance();
		}
		if (pos < src.length() && cur() == '#') {
			skipComment();
		}
		if (pos >= src.length()) {
			return false;
		}
		if (cur() == '\n') {
			advance();
			return false;
		}
		if (width > indents.peek()) {
			indents.push(width);
			add(Kind.INDENT, "", null, line, col);
		} else {
			while (width < indents.peek()) {
				indents.pop();
				add(Kind.DEDENT, "", null, line, col);
			}
			if (width != indents.peek()) {
				throw error(line, col, "unindent does not match any outer indentation level");
			}
		}
		return true;
	}

	private void skipComment() {
		while (pos < src.length() && cur() != '\n') {
			advance();
		}
	}

	private void identifierOrString() {
		int start = pos;
		int l = line;
		int c = col;
		while (pos < src.length() && isIdentifierPart(cur())) {
			advance();
		}
		String text = src.substring(start, pos);
		if (pos < src.length() && (cur() == '\'' || cur() == '"') && STRING_PREFIXES.contains(text.toLowerCase())) {
			string(start, l, c, text.toLowerCase());
			return;
		}
		add(Kind.NAME, text, null, l, c);
	}

	private void number() {
		int start = pos;
		int l = line;
		int c = col;
		char first = cur();
		char second = Character.toLowerCase(peek(1));
		if (first == '0' && (second == 'x' || second == 'o' || second == 'b')) {
			int radix = second == 'x' ? 16 : second == 'o' ? 8 : 2;
			String name = second == 'x' ? "hexadecimal" : second == 'o' ? "octal" : "binary";
			advance(2);
			StringBuilder digits = new StringBuilder();
			while (pos < src.length() && (Character.digit(cur(), radix) >= 0 || cur() == '_')) {
				if (cur() != '_') {
					digits.append(cur());
				}
				advance();
			}
			if (digits.isEmpty() || (pos < src.length() && isIdentifierPart(cur()))) {
				throw error(l, c, "invalid " + name + " literal");
			}
			add(Kind.INT, src.substring(start, pos), toLong(new BigInteger(digits.toString(), radix), l, c), l, c);
			return;
		}
		boolean isFloat = false;
		skipDigits();
		if (pos < src.length() && cur() == '.') {
			isFloat = true;
			advance();
			skipDigits();
		}
		if (pos < src.length() && (cur() == 'e' || cur() == 'E')
				&& (isDigit(peek(1)) || ((peek(1) == '+' || peek(1) == '-') && isDigit(peek(2))))) {
			isFloat = true;
			advance(2);
			skipDigits();
		}
		if (pos < src.length() && (cur() == 'j' || cur() == 'J')) {
			throw error(l, c, "complex numbers are not supported");
		}
		if (pos < src.length() && isIdentifierPart(cur())) {
			throw error(l, c, "invalid decimal literal");
		}
		String text = src.substring(start, pos);
		String clean = text.replace("_", "");
		if (isFloat) {
			add(Kind.FLOAT, text, Double.parseDouble(clean), l, c);
			return;
		}
		if (clean.length() > 1 && clean.charAt(0) == '0' && !clean.chars().allMatch(ch -> ch == '0')) {
			throw error(l, c, "leading zeros in decimal integer literals are not permitted");
		}
		add(Kind.INT, text, toLong(new BigInteger(clean), l, c), l, c);
	}

	private long toLong(BigInteger value, int l, int c) {
		if (value.bitLength() > 63) {
			throw error(l, c, "integer literal is too large (ints are 64-bit)");
		}
		return value.longValue();
	}

	private void skipDigits() {
		while (pos < src.length() && (isDigit(cur()) || cur() == '_')) {
			advance();
		}
	}

	/** A string literal whose prefix starts at {@code start}; {@code pos} is at its opening quote. */
	private void string(int start, int l, int c, String prefix) {
		if (prefix.contains("b")) {
			throw error(l, c, "bytes literals are not supported");
		}
		boolean raw = prefix.contains("r");
		char quote = cur();
		boolean triple = peek(1) == quote && peek(2) == quote;
		advance(triple ? 3 : 1);
		Quote q = new Quote(quote, triple, raw, l, c);
		if (prefix.contains("f")) {
			List<Token.FPiece> pieces = fString(q);
			add(Kind.FSTRING, src.substring(start, pos), List.copyOf(pieces), l, c);
		} else {
			StringBuilder value = new StringBuilder();
			while (true) {
				char ch = stringChar(q);
				if (atClosingQuote(q)) {
					advance(triple ? 3 : 1);
					break;
				}
				if (ch == '\\') {
					backslash(value, raw);
				} else {
					value.append(ch);
					advance();
				}
			}
			add(Kind.STRING, src.substring(start, pos), value.toString(), l, c);
		}
	}

	/** How a string literal is quoted, and where it starts, for errors. */
	private record Quote(char quote, boolean triple, boolean raw, int line, int col) {
	}

	/** The current character inside a string, failing if the string ends unterminated here. */
	private char stringChar(Quote q) {
		if (pos >= src.length() || (cur() == '\n' && !q.triple())) {
			throw error(q.line(), q.col(), q.triple() ? "unterminated triple-quoted string literal" : "unterminated string literal");
		}
		return cur();
	}

	private boolean atClosingQuote(Quote q) {
		return cur() == q.quote() && (!q.triple() || (peek(1) == q.quote() && peek(2) == q.quote()));
	}

	private void backslash(StringBuilder value, boolean raw) {
		if (raw) {
			value.append('\\');
			advance();
			if (pos < src.length()) {
				value.append(cur());
				advance();
			}
			return;
		}
		int l = line;
		int c = col;
		advance();
		if (pos >= src.length()) {
			return;
		}
		char e = cur();
		advance();
		switch (e) {
			case '\n' -> {
			}
			case '\\', '\'', '"' -> value.append(e);
			case 'a' -> value.append('\u0007');
			case 'b' -> value.append('\b');
			case 'f' -> value.append('\f');
			case 'n' -> value.append('\n');
			case 'r' -> value.append('\r');
			case 't' -> value.append('\t');
			case 'v' -> value.append('\u000b');
			case 'x' -> value.appendCodePoint(hex(2, l, c, "\\x"));
			case 'u' -> value.appendCodePoint(hex(4, l, c, "\\u"));
			case 'U' -> {
				int code = hex(8, l, c, "\\U");
				if (code > Character.MAX_CODE_POINT) {
					throw error(l, c, "invalid \\U escape: no such character");
				}
				value.appendCodePoint(code);
			}
			case 'N' -> throw error(l, c, "`\\N{...}` escapes are not supported");
			default -> {
				if (e >= '0' && e <= '7') {
					int code = e - '0';
					for (int i = 0; i < 2 && pos < src.length() && cur() >= '0' && cur() <= '7'; i++) {
						code = code * 8 + (cur() - '0');
						advance();
					}
					value.append((char) code);
				} else {
					value.append('\\').append(e);
				}
			}
		}
	}

	private int hex(int digits, int l, int c, String escape) {
		int code = 0;
		for (int i = 0; i < digits; i++) {
			int digit = pos < src.length() ? Character.digit(cur(), 16) : -1;
			if (digit < 0) {
				throw error(l, c, "invalid " + escape + " escape: expected " + digits + " hex digits");
			}
			code = code * 16 + digit;
			advance();
		}
		return code;
	}

	private List<Token.FPiece> fString(Quote q) {
		List<Token.FPiece> pieces = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		while (true) {
			char ch = stringChar(q);
			if (atClosingQuote(q)) {
				advance(q.triple() ? 3 : 1);
				break;
			}
			if (ch == '\\') {
				backslash(text, q.raw());
			} else if (ch == '{' && peek(1) == '{') {
				text.append('{');
				advance(2);
			} else if (ch == '}' && peek(1) == '}') {
				text.append('}');
				advance(2);
			} else if (ch == '}') {
				throw error(line, col, "f-string: single '}' is not allowed");
			} else if (ch == '{') {
				field(q, pieces, text);
			} else {
				text.append(ch);
				advance();
			}
		}
		if (!text.isEmpty()) {
			pieces.add(new Token.Text(text.toString()));
		}
		return pieces;
	}

	/**
	 * Reads a replacement field, {@code pos} at its '{', and adds it to {@code pieces} after the pending {@code text}.
	 * With a trailing {@code =} ({@code {x=}}), the expression's source up to the field's end is text before it, and
	 * the value is shown as its repr unless the field has a conversion or a spec. A spec may hold fields itself.
	 */
	private void field(Quote q, List<Token.FPiece> pieces, StringBuilder text) {
		int braceLine = line;
		int braceCol = col;
		advance();
		int sourceLine = line;
		int sourceCol = col;
		int sourceStart = pos;
		int depth = 0;
		char conversion = '\0';
		List<Token.FPiece> spec = null;
		int sourceEnd;
		while (true) {
			char ch = stringChar(q);
			if (atClosingQuote(q)) {
				throw error(braceLine, braceCol, "f-string: expecting '}'");
			}
			if (ch == '\'' || ch == '"') {
				skipNestedString(q);
				continue;
			}
			if (ch == '#') {
				throw error(line, col, "f-string expression part cannot include '#'");
			}
			if (depth == 0 && (ch == '}' || ch == ':' || (ch == '!' && peek(1) != '='))) {
				sourceEnd = pos;
				break;
			}
			if (ch == '(' || ch == '[' || ch == '{') {
				depth++;
			} else if (ch == ')' || ch == ']' || ch == '}') {
				depth--;
			}
			advance();
		}
		String source = src.substring(sourceStart, sourceEnd);
		if (source.isBlank()) {
			throw error(braceLine, braceCol, "f-string: empty expression not allowed");
		}
		String trimmed = source.stripTrailing();
		boolean debug = trimmed.length() >= 2 && trimmed.endsWith("=")
				&& "=!<>".indexOf(trimmed.charAt(trimmed.length() - 2)) < 0;
		if (debug) {
			text.append(source);
			source = trimmed.substring(0, trimmed.length() - 1);
		}
		if (cur() == '!') {
			advance();
			char ch = stringChar(q);
			if (ch != 'r' && ch != 's' && ch != 'a') {
				throw error(line, col, "f-string: invalid conversion character: expected 's', 'r', or 'a'");
			}
			conversion = ch;
			advance();
			ch = stringChar(q);
			if (ch != ':' && ch != '}') {
				throw error(line, col, "f-string: expecting '}'");
			}
		}
		if (cur() == ':') {
			advance();
			spec = new ArrayList<>();
			StringBuilder format = new StringBuilder();
			while (true) {
				char ch = stringChar(q);
				if (atClosingQuote(q)) {
					throw error(braceLine, braceCol, "f-string: expecting '}'");
				}
				if (ch == '{') {
					field(q, spec, format);
					continue;
				}
				if (ch == '}') {
					break;
				}
				format.append(ch);
				advance();
			}
			if (!format.isEmpty()) {
				spec.add(new Token.Text(format.toString()));
			}
			spec = List.copyOf(spec);
		}
		advance();
		if (debug && conversion == '\0' && spec == null) {
			conversion = 'r';
		}
		if (!text.isEmpty()) {
			pieces.add(new Token.Text(text.toString()));
			text.setLength(0);
		}
		pieces.add(new Token.Field(source, sourceLine, sourceCol, conversion, spec));
	}

	/** Skips a string inside an f-string field; the field's tokenizer reads it later. */
	private void skipNestedString(Quote outer) {
		char quote = cur();
		advance();
		while (pos < src.length() && cur() != quote) {
			if (cur() == '\n' && !outer.triple()) {
				return;
			}
			if (cur() == '\\') {
				advance();
			}
			advance();
		}
		if (pos < src.length()) {
			advance();
		}
	}

	private void operator() {
		int l = line;
		int c = col;
		for (String op : OPERATORS) {
			if (src.startsWith(op, pos)) {
				advance(op.length());
				Token token = new Token(Kind.OP, op, null, l, c);
				if (op.length() == 1 && "([{".contains(op)) {
					brackets.push(token);
				} else if (op.length() == 1 && ")]}".contains(op)) {
					if (brackets.isEmpty()) {
						throw error(l, c, "unmatched '" + op + "'");
					}
					Token open = brackets.pop();
					if ("([{".indexOf(open.text().charAt(0)) != ")]}".indexOf(op.charAt(0))) {
						throw error(l, c, "closing '" + op + "' does not match opening '" + open.text() + "'");
					}
				}
				tokens.add(token);
				return;
			}
		}
		throw error(l, c, "invalid character '" + cur() + "'");
	}

	private void add(Kind kind, String text, Object value, int l, int c) {
		tokens.add(new Token(kind, text, value, l, c));
	}

	private char cur() {
		return src.charAt(pos);
	}

	private char peek(int ahead) {
		return pos + ahead < src.length() ? src.charAt(pos + ahead) : '\0';
	}

	private void advance() {
		if (src.charAt(pos) == '\n') {
			line++;
			col = 1;
		} else {
			col++;
		}
		pos++;
	}

	private void advance(int count) {
		for (int i = 0; i < count && pos < src.length(); i++) {
			advance();
		}
	}

	private CompileError error(int l, int c, String message) {
		return new CompileError(file, l, c, message);
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isIdentifierStart(char c) {
		return c == '_' || Character.isLetter(c);
	}

	private static boolean isIdentifierPart(char c) {
		return c == '_' || Character.isLetterOrDigit(c);
	}

	/** Whether {@code text} is a name. */
	static boolean isIdentifier(String text) {
		if (text.isEmpty() || !isIdentifierStart(text.charAt(0))) {
			return false;
		}
		return text.chars().allMatch(c -> isIdentifierPart((char) c));
	}
}
