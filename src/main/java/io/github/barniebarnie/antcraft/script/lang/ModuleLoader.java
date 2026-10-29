package io.github.barniebarnie.antcraft.script.lang;

import org.jspecify.annotations.Nullable;

/**
 * Finds the modules a script imports (docs/language/modules.md): the scripts of the script library, by name, with dots
 * for folders ({@code lib.trees} is {@code lib/trees.py}).
 */
public interface ModuleLoader {
	/** A loader without modules: every import fails. */
	ModuleLoader NONE = name -> null;

	/**
	 * The compiled top level of module {@code name}, or null when there is none. A module that does not compile throws
	 * its {@link CompileError}.
	 */
	@Nullable Code load(String name);

	/** Whether modules are named under {@code name} (a folder), so it can be imported as a package without a file of its own. */
	default boolean isPackage(String name) {
		return false;
	}
}
