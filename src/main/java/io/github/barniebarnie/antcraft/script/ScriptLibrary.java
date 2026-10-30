package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.Code;
import io.github.barniebarnie.antcraft.script.lang.CompileError;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.ModuleLoader;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The scripts found in the script folders, compiled (docs/features/scripts.md, Files and commands). A script is named by its
 * path in its folder without {@code .py}, with dots for folders, as a module is imported: {@code lib/trees.py} is
 * {@code lib.trees}. A script that does not compile is kept with its error, so {@code /antcraft script list} can show it.
 * Its scripts are the modules scripts import, and its folders their packages. A data pack script
 * {@code data/<namespace>/script/<path>.py} has the id {@code <namespace>:<path>} and is named as a module is, with dots
 * for the colon and slashes: {@code mypack:lib/trees} is {@code mypack.lib.trees}.
 */
public final class ScriptLibrary implements ModuleLoader {
	/** One script file: compiled to {@code code}, or with the {@code compileError} that kept it from compiling. */
	public record Script(String name, Path path, String source, @Nullable Code code, @Nullable CompileError compileError) {
		/** The compile error's message, placed in its file; null if it compiled. */
		public @Nullable String error() {
			return compileError == null ? null : compileError.getMessage();
		}
	}

	private final Map<String, Script> scripts;

	private ScriptLibrary(Map<String, Script> scripts) {
		this.scripts = scripts;
	}

	/** Loads the {@code .py} files of {@code folders}; on a name clash, the later folder wins. Missing folders are skipped. */
	public static ScriptLibrary load(List<Path> folders) {
		return load(Map.of(), folders);
	}

	/**
	 * Loads the data pack scripts {@code packScripts}, sources by id, and the {@code .py} files of {@code folders}; on a name
	 * clash, the later folder wins, and any folder wins over a data pack.
	 */
	public static ScriptLibrary load(Map<Identifier, String> packScripts, List<Path> folders) {
		Map<String, Script> scripts = new TreeMap<>();
		packScripts.forEach((id, source) -> {
			Path path = Path.of("data", id.getNamespace(), "script", id.getPath() + ".py");
			Script script = compile(name(id.toString()), path, id + ".py", source);
			scripts.put(script.name(), script);
		});
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
			return new Script(name, file, "", null, new CompileError(relative, 1, 1, "cannot be read: " + e.getMessage()));
		}
		return compile(name, file, relative, source);
	}

	private static Script compile(String name, Path path, String file, String source) {
		try {
			return new Script(name, path, source, Compiler.compile(Parser.parse(file, source)), null);
		} catch (CompileError e) {
			return new Script(name, path, source, null, e);
		}
	}

	/** The library name of a script or data pack id: {@code mypack:lib/trees} is {@code mypack.lib.trees}; a name stays as it is. */
	public static String name(String nameOrId) {
		return nameOrId.replace(':', '.').replace('/', '.');
	}

	/** No scripts. */
	public static ScriptLibrary empty() {
		return new ScriptLibrary(Map.of());
	}

	/** The compiled script {@code name}, or null when there is none; one that does not compile throws its kept error. */
	@Override
	public @Nullable Code load(String name) {
		Script script = scripts.get(name);
		if (script == null) {
			return null;
		}
		if (script.code() != null) {
			return script.code();
		}
		throw Objects.requireNonNull(script.compileError());
	}

	/** Whether scripts are named under {@code name}, as those of a folder are. */
	@Override
	public boolean isPackage(String name) {
		String prefix = name + ".";
		return scripts.keySet().stream().anyMatch(script -> script.startsWith(prefix));
	}

	/** Every script, by name. */
	public Collection<Script> scripts() {
		return scripts.values();
	}

	/** The script named {@code name}, or with the data pack id {@code name}. */
	public Optional<Script> get(String name) {
		return Optional.ofNullable(scripts.get(name(name)));
	}

	/** The scripts that do not compile, by name. */
	public List<Script> errors() {
		return scripts.values().stream().filter(script -> script.error() != null).toList();
	}
}
