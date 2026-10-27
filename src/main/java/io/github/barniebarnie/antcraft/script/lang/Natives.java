package io.github.barniebarnie.antcraft.script.lang;

import java.util.HashMap;
import java.util.Map;

/**
 * The native functions a script can call, by name, and the native methods of each value type, by type name (as
 * {@link Values#typeName} gives it) and method name. A name a script does not define itself is looked up here, so
 * builtins and the bot API are registered the same way.
 */
public final class Natives {
	/**
	 * The file of the builtins written in the script language (see {@link Builtins}). An error in them is placed at the
	 * script's call of the builtin.
	 */
	public static final String BUILTINS_FILE = "<builtins>";

	private final Map<String, NativeFunction> functions = new HashMap<>();
	private final Map<String, NativeFunction> methods = new HashMap<>();

	/**
	 * The builtins and methods every script has (docs/scripting.md): {@code print}, {@code debug}, {@code info},
	 * {@code warn} and {@code error} write to {@code output} at their level.
	 */
	public static Natives standard(Output output) {
		Natives natives = new Natives();
		Builtins.register(natives, output);
		Methods.register(natives);
		return natives;
	}

	/** Registers {@code function} under {@code name}, replacing what was there. */
	public Natives register(String name, NativeFunction function) {
		functions.put(name, function);
		return this;
	}

	/** Registers a method {@code name} on values of {@code type}; it gets the value as its first argument. */
	public Natives registerMethod(String type, String name, NativeFunction function) {
		methods.put(type + "." + name, function);
		return this;
	}

	/** The function registered under {@code name}, or null. */
	public NativeFunction function(String name) {
		return functions.get(name);
	}

	/** The method {@code name} of {@code type}, or null. */
	public NativeFunction method(String type, String name) {
		return methods.get(type + "." + name);
	}
}
