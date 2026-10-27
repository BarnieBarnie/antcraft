package io.github.barniebarnie.antcraft.script.lang;

/**
 * An error while a script runs, such as an index out of range or a name that is not defined. It stops the script. The
 * message reads {@code file:line: detail}.
 *
 * <p>Operations and native functions throw it without a position ({@link #ScriptError(String)}); the VM places it at
 * the file and line of the instruction that failed.
 */
public final class ScriptError extends RuntimeException {
	private final String file;
	private final int line;
	private final String detail;

	/** An error not yet placed in the source; the VM places it at the instruction that is running. */
	public ScriptError(String detail) {
		this(null, 0, detail);
	}

	public ScriptError(String file, int line, String detail) {
		super(file == null ? detail : file + ":" + line + ": " + detail, null, false, false);
		this.file = file;
		this.line = line;
		this.detail = detail;
	}

	/** The file, or null while the error is not yet placed. */
	public String file() {
		return file;
	}

	/** The line, counted from 1, or 0 while the error is not yet placed. */
	public int line() {
		return line;
	}

	/** The message without the position. */
	public String detail() {
		return detail;
	}

	ScriptError at(String file, int line) {
		return new ScriptError(file, line, detail);
	}
}
