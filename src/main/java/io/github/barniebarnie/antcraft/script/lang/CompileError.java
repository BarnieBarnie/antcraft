package io.github.barniebarnie.antcraft.script.lang;

/**
 * An error found while loading a script, before it runs: a syntax error or a construct outside the supported subset of
 * Python. The message reads {@code file:line:col: detail}, with line and column counted from 1.
 */
public final class CompileError extends RuntimeException {
	private final String file;
	private final int line;
	private final int column;
	private final String detail;

	public CompileError(String file, int line, int column, String detail) {
		super(file + ":" + line + ":" + column + ": " + detail);
		this.file = file;
		this.line = line;
		this.column = column;
		this.detail = detail;
	}

	public String file() {
		return file;
	}

	public int line() {
		return line;
	}

	public int column() {
		return column;
	}

	/** The message without the position. */
	public String detail() {
		return detail;
	}

	/** The error for a construct outside the subset, such as {@code `class` is not supported}. */
	static CompileError unsupported(String file, int line, int column, String construct) {
		return new CompileError(file, line, column, construct + " is not supported");
	}
}
