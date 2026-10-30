package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.CompileError;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Checks a script the in-game editor sends before the server writes it (docs/features/scripts.md, Editing in game). The
 * name and source come from a client, so nothing about them is trusted: the name must be dotted words, which map to a
 * file strictly inside the world script folder; the source must fit {@link #MAX_SOURCE_BYTES} and compile.
 */
public final class ScriptEdits {
	/** The most bytes (UTF-8) a saved script may have. */
	public static final int MAX_SOURCE_BYTES = 65_536;
	public static final int MAX_NAME_LENGTH = 128;
	/** Words of letters, digits, {@code _} and {@code -}, joined by single dots: no empty part, no {@code ..}, no separators. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");

	private ScriptEdits() {
	}

	/**
	 * The file in {@code folder} that script {@code name} with {@code source} is saved to: {@code lib.trees} is
	 * {@code lib/trees.py}, as the library names it.
	 *
	 * @throws IllegalArgumentException with the reason, if the name, the size or the source will not do
	 */
	public static Path check(Path folder, String name, String source) {
		if (name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("not a script name: " + name.substring(0, Math.min(name.length(), MAX_NAME_LENGTH)));
		}
		int bytes = source.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_SOURCE_BYTES) {
			throw new IllegalArgumentException("too large: " + bytes + " bytes, at most " + MAX_SOURCE_BYTES);
		}
		String relative = name.replace('.', '/') + ".py";
		Path base = folder.toAbsolutePath().normalize();
		Path file = base.resolve(relative).normalize();
		// The pattern already rules out leaving the folder; this guards it should the pattern ever change.
		if (!file.startsWith(base) || file.equals(base)) {
			throw new IllegalArgumentException("not a script name: " + name);
		}
		try {
			Compiler.compile(Parser.parse(relative, source));
		} catch (CompileError e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
		return file;
	}

	/** The line (from 1) of the compile error {@link #check} refused a source for, or 0 for any other refusal. */
	public static int errorLine(IllegalArgumentException refusal) {
		return refusal.getCause() instanceof CompileError error ? error.line() : 0;
	}
}
