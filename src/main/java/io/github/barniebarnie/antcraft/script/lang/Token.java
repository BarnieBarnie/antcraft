package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;

/**
 * One token of a script. {@code text} is the source text (for layout tokens, empty); {@code value} is the literal's
 * value: a {@link Long} for INT, a {@link Double} for FLOAT, the decoded {@link String} for STRING and a list of
 * {@link FPiece} for FSTRING. Line and column count from 1.
 */
public record Token(Kind kind, String text, Object value, int line, int col) {
	public enum Kind {
		NAME, INT, FLOAT, STRING, FSTRING, OP, NEWLINE, INDENT, DEDENT, EOF
	}

	public boolean isOp(String op) {
		return kind == Kind.OP && text.equals(op);
	}

	public boolean isName(String name) {
		return kind == Kind.NAME && text.equals(name);
	}

	/** A part of an f-string: literal text or a replacement field. */
	public sealed interface FPiece {
	}

	/** Literal text of an f-string, with escapes and doubled braces decoded. */
	public record Text(String text) implements FPiece {
	}

	/**
	 * A replacement field {@code {source!conversion:spec}}. {@code line} and {@code col} are where {@code source}
	 * starts; {@code conversion} is {@code '\0'} when there is none. {@code spec} is null when there is none, else its
	 * text and nested fields ({@code {x:{width}}}).
	 */
	public record Field(String source, int line, int col, char conversion, List<FPiece> spec)
			implements FPiece {
	}
}
