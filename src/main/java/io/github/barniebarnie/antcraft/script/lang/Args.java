package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;
import java.util.Map;

/**
 * Binds the arguments of a native call to its parameters, by position or by name, with CPython's messages. Public for
 * the natives of the host, such as the bot API.
 */
public final class Args {
	private Args() {
	}

	/**
	 * The values of {@code params}, in order, from {@code args} and {@code kwargs}; null for an optional one not given.
	 * The first {@code required} parameters must be given.
	 */
	static Object[] bind(String function, List<Object> args, Map<String, Object> kwargs, int required,
			String... params) {
		if (args.size() > params.length) {
			throw new ScriptError(function + "() takes " + (required == params.length ? "exactly" : "at most") + " "
					+ count(params.length) + " (" + args.size() + " given)");
		}
		Object[] values = new Object[params.length];
		for (int i = 0; i < args.size(); i++) {
			values[i] = args.get(i);
		}
		for (Map.Entry<String, Object> kwarg : kwargs.entrySet()) {
			int index = List.of(params).indexOf(kwarg.getKey());
			if (index < 0) {
				throw new ScriptError(function + "() got an unexpected keyword argument '" + kwarg.getKey() + "'");
			}
			if (values[index] != null) {
				throw new ScriptError("argument for " + function + "() given by name ('" + kwarg.getKey()
						+ "') and position (" + (index + 1) + ")");
			}
			values[index] = kwarg.getValue();
		}
		for (int i = 0; i < required; i++) {
			if (values[i] == null) {
				if (kwargs.isEmpty()) {
					throw new ScriptError(function + "() takes " + (required == params.length ? "exactly" : "at least")
							+ " " + count(required) + " (" + args.size() + " given)");
				}
				throw new ScriptError(function + "() missing required argument '" + params[i] + "' (pos " + (i + 1)
						+ ")");
			}
		}
		return values;
	}

	/** Binds positional arguments only. */
	public static Object[] bind(String function, NativeCall call, int required, String... params) {
		return bind(function, call.args(), call.kwargs(), required, params);
	}

	static void noKeywords(String function, Map<String, Object> kwargs) {
		if (!kwargs.isEmpty()) {
			throw new ScriptError(function + "() takes no keyword arguments");
		}
	}

	private static String count(int n) {
		return n == 1 ? "one argument" : n + " arguments";
	}

	/** An int argument (a bool counts), with Python's message for anything else. */
	public static long integer(Object value) {
		if (Values.isInt(value)) {
			return Values.asLong(value);
		}
		throw new ScriptError("'" + Values.typeName(value) + "' object cannot be interpreted as an integer");
	}

	/** Whether {@code value} is None or left out. */
	public static boolean absent(Object value) {
		return value == null || value instanceof PyNone;
	}

	static boolean callable(Object value) {
		return value instanceof PyFunction || value instanceof PyNative || value instanceof PyMethod || value instanceof PyClass
				|| value instanceof PyBoundMethod;
	}
}
