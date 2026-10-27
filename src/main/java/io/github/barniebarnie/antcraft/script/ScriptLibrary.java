package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.Code;
import io.github.barniebarnie.antcraft.script.lang.CompileError;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The scripts found in the script folders, compiled (docs/scripting.md, Files and commands). A script is named by its
 * path in its folder without {@code .py}, with dots for folders, as a module is imported: {@code lib/trees.py} is
 * {@code lib.trees}. A script that does not compile is kept with its error, so {@code /antcraft script list} can show it.
 */
public final class ScriptLibrary {
	/** One script file: compiled to {@code code}, or with the {@code error} that kept it from compiling. */
	public record Script(String name, Path path, String source, @Nullable Code code, @Nullable String error) {
	}

	private final Map<String, Script> scripts;

	private ScriptLibrary(Map<String, Script> scripts) {
		this.scripts = scripts;
	}

	/** Loads the {@code .py} files of {@code folders}; on a name clash, the later folder wins. Missing folders are skipped. */
	public static ScriptLibrary load(List<Path> folders) {
		Map<String, Script> scripts = new TreeMap<>();
		for (Path folder : folders) {
			if (!Files.isDirectory(folder)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(folder)) {
				files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".py")).sorted().forEach(file -> {
					Script script = compile(folder, file);
					scripts.put(script.name(), script);
				});
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return new ScriptLibrary(scripts);
	}

	private static Script compile(Path folder, Path file) {
		String relative = folder.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
		String name = relative.substring(0, relative.length() - ".py".length()).replace('/', '.');
		String source;
		try {
			source = Files.readString(file);
		} catch (IOException e) {
			return new Script(name, file, "", null, relative + ": cannot be read: " + e.getMessage());
		}
		try {
			return new Script(name, file, source, Compiler.compile(Parser.parse(relative, source)), null);
		} catch (CompileError e) {
			return new Script(name, file, source, null, e.getMessage());
		}
	}

	/** Every script, by name. */
	public Collection<Script> scripts() {
		return scripts.values();
	}

	public Optional<Script> get(String name) {
		return Optional.ofNullable(scripts.get(name));
	}

	/** The scripts that do not compile, by name. */
	public List<Script> errors() {
		return scripts.values().stream().filter(script -> script.error() != null).toList();
	}
}
