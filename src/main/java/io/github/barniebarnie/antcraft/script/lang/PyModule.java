package io.github.barniebarnie.antcraft.script.lang;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A module of a running script: the main script or one it imported, by its name in the script library, with its own
 * globals. Its globals are its attributes. A package without a file of its own (a folder, such as {@code lib}) is a
 * module whose globals are its submodules. Plain data, so a run's modules can be saved.
 */
public final class PyModule implements HostValue {
	private final String name;
	private final Map<String, Object> globals = new LinkedHashMap<>();
	private boolean loaded;
	/** Its compiled top level; null for a package without a file of its own. */
	private @Nullable Code code;

	public PyModule(String name) {
		this.name = name;
	}

	/** The module's name, as imported: {@code lib.trees}. */
	public String name() {
		return name;
	}

	/** The module's globals, by name; changed as its code runs. */
	public Map<String, Object> globals() {
		return globals;
	}

	/** Whether its top level ran to its end; while it runs, importing it again is a circular import. */
	public boolean loaded() {
		return loaded;
	}

	void setLoaded() {
		loaded = true;
	}

	/** The compiled top level, or null for a package without a file of its own. */
	@Nullable Code code() {
		return code;
	}

	void setCode(@Nullable Code code) {
		this.code = code;
	}

	@Override
	public String typeName() {
		return "module";
	}

	@Override
	public Object attribute(String attribute) {
		return globals.get(attribute);
	}

	@Override
	public String toString() {
		return "<module '" + name + "'>";
	}
}
