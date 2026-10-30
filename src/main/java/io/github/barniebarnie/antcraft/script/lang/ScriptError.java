package io.github.barniebarnie.antcraft.script.lang;

import org.jspecify.annotations.Nullable;

/**
 * An error while a script runs, such as an index out of range or a name that is not defined. It stops the script,
 * unless a {@code try} catches it (docs/language/errors.md, Catching errors). The message reads {@code file:line: detail}.
 *
 * <p>Operations and native functions throw it without a position ({@link #ScriptError(String)}); the VM places it at
 * the file and line of the instruction that failed.
 */
public final class ScriptError extends RuntimeException {
	private final String file;
	private final int line;
	private final String detail;
	/** The exception a script raised, or the one with an explicit type; null for one {@link PyException#classify}d. */
	private final @Nullable PyException exception;

	/** An error not yet placed in the source; the VM places it at the instruction that is running. */
	public ScriptError(String detail) {
		this(null, 0, detail, null);
	}

	public ScriptError(String file, int line, String detail) {
		this(file, line, detail, null);
	}

	/** Raises {@code exception}: placed where it was first raised, or by the VM when it was not raised yet. */
	ScriptError(PyException exception) {
		this(exception.file(), exception.line(), exception.detail(), exception);
	}

	private ScriptError(String file, int line, String detail, @Nullable PyException exception) {
		super(file == null ? detail : file + ":" + line + ": " + detail, null, false, false);
		this.file = file;
		this.line = line;
		this.detail = detail;
		this.exception = exception;
	}

	/** An error of the exception type {@code type}, such as {@code TypeError}, not yet placed. */
	static ScriptError of(String type, String detail) {
		return new ScriptError(new PyException(type, detail, detail, null, 0));
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

	/** The error as the script's {@code except} sees it. */
	PyException exception() {
		return exception != null ? exception : new PyException(PyException.classify(detail), detail, detail, file, line);
	}

	ScriptError at(String file, int line) {
		return new ScriptError(file, line, detail,
				exception == null ? null : exception.at(detail, file, line));
	}
}
