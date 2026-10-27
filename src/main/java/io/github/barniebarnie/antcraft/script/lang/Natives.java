package io.github.barniebarnie.antcraft.script.lang;

import java.util.HashMap;
import java.util.Map;

/**
 * The native functions a script can call, by name, and the native methods of each value type, by type name (as
 * {@link Values#typeName} gives it) and method name. A name a script does not define itself is looked up here, so
 * builtins and the bot API are registered the same way.
 */
public final class Natives {
	private final Map<String, NativeFunction> functions = new HashMap<>();
	private final Map<String, NativeFunction> methods = new HashMap<>();

	/** The builtins every script has: {@code print} to {@code output}, and {@code range}. */
	public static Natives standard(Output output) {
		Natives natives = new Natives();
		natives.register("print", call -> print(call, output));
		natives.register("range", Natives::range);
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

	private static Object print(NativeCall call, Output output) {
		String sep = " ";
		for (Map.Entry<String, Object> kwarg : call.kwargs().entrySet()) {
			if (!kwarg.getKey().equals("sep")) {
				throw new ScriptError("'" + kwarg.getKey() + "' is an invalid keyword argument for print()");
			}
			if (kwarg.getValue() instanceof String s) {
				sep = s;
			} else if (!(kwarg.getValue() instanceof PyNone)) {
				throw new ScriptError("sep must be None or a string, not " + Values.typeName(kwarg.getValue()));
			}
		}
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < call.args().size(); i++) {
			if (i > 0) {
				line.append(sep);
			}
			line.append(Values.str(call.args().get(i)));
		}
		output.print(line.toString());
		return PyNone.NONE;
	}

	private static Object range(NativeCall call) {
		if (!call.kwargs().isEmpty()) {
			throw new ScriptError("range() takes no keyword arguments");
		}
		int n = call.args().size();
		if (n == 0) {
			throw new ScriptError("range expected at least 1 argument, got 0");
		}
		if (n > 3) {
			throw new ScriptError("range expected at most 3 arguments, got " + n);
		}
		long[] values = new long[n];
		for (int i = 0; i < n; i++) {
			Object arg = call.args().get(i);
			if (arg instanceof Long l) {
				values[i] = l;
			} else if (arg instanceof Boolean b) {
				values[i] = b ? 1 : 0;
			} else {
				throw new ScriptError("'" + Values.typeName(arg) + "' object cannot be interpreted as an integer");
			}
		}
		return switch (n) {
			case 1 -> new PyRange(0, values[0], 1);
			case 2 -> new PyRange(values[0], values[1], 1);
			default -> new PyRange(values[0], values[1], values[2]);
		};
	}
}
