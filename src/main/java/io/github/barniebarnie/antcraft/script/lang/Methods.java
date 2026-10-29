package io.github.barniebarnie.antcraft.script.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@code str}, {@code list} and {@code dict} methods of docs/language/index.md, with Python's behaviour and CPython's
 * error messages.
 */
final class Methods {
	private Methods() {
	}

	/** A method: its value, and its other arguments and keyword arguments. */
	@FunctionalInterface
	private interface Method<T> {
		Object call(T self, List<Object> args, Map<String, Object> kwargs, NativeCall call);
	}

	private static <T> void method(Natives natives, String type, String name, Class<T> selfType, Method<T> method) {
		natives.registerMethod(type, name, call -> {
			List<Object> args = call.args();
			return method.call(selfType.cast(args.getFirst()), args.subList(1, args.size()), call.kwargs(), call);
		});
	}

	static void register(Natives natives) {
		string(natives);
		list(natives);
		dict(natives);
	}

	// str

	private static void string(Natives natives) {
		method(natives, "str", "split", String.class, (s, args, kwargs, call) -> {
			Object[] a = Args.bind("split", args, kwargs, 0, "sep", "maxsplit");
			long maxsplit = Args.absent(a[1]) ? -1 : Args.integer(a[1]);
			if (Args.absent(a[0])) {
				return new PyList(splitWhitespace(s, maxsplit));
			}
			if (!(a[0] instanceof String sep)) {
				throw new ScriptError("must be str or None, not " + Values.typeName(a[0]));
			}
			if (sep.isEmpty()) {
				throw new ScriptError("empty separator");
			}
			List<Object> parts = new ArrayList<>();
			int start = 0;
			for (int at = s.indexOf(sep); at >= 0 && (maxsplit < 0 || parts.size() < maxsplit); at = s.indexOf(sep,
					start)) {
				parts.add(s.substring(start, at));
				start = at + sep.length();
			}
			parts.add(s.substring(start));
			return new PyList(parts);
		});
		method(natives, "str", "join", String.class, (s, args, kwargs, call) -> {
			List<Object> items = Values.elements(Args.bind("join", args, kwargs, 1, "iterable")[0]);
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < items.size(); i++) {
				if (!(items.get(i) instanceof String item)) {
					throw new ScriptError("sequence item " + i + ": expected str instance, "
							+ Values.typeName(items.get(i)) + " found");
				}
				if (i > 0) {
					out.append(s);
				}
				out.append(item);
			}
			return out.toString();
		});
		method(natives, "str", "strip", String.class, (s, args, kwargs, call) -> {
			Object chars = Args.bind("strip", args, kwargs, 0, "chars")[0];
			if (Args.absent(chars)) {
				return s.strip();
			}
			if (!(chars instanceof String set)) {
				throw new ScriptError("strip arg must be None or str");
			}
			int[] points = s.codePoints().toArray();
			int from = 0;
			int to = points.length;
			while (from < to && set.indexOf(points[from]) >= 0) {
				from++;
			}
			while (to > from && set.indexOf(points[to - 1]) >= 0) {
				to--;
			}
			return new String(points, from, to - from);
		});
		method(natives, "str", "startswith", String.class, (s, args, kwargs, call) ->
				affix(s, Args.bind("startswith", args, kwargs, 1, "prefix")[0], "startswith", s::startsWith));
		method(natives, "str", "endswith", String.class, (s, args, kwargs, call) ->
				affix(s, Args.bind("endswith", args, kwargs, 1, "suffix")[0], "endswith", s::endsWith));
		method(natives, "str", "replace", String.class, (s, args, kwargs, call) -> {
			Object[] a = Args.bind("replace", args, kwargs, 2, "old", "new", "count");
			for (int i = 0; i < 2; i++) {
				if (!(a[i] instanceof String)) {
					throw new ScriptError("replace() argument " + (i + 1) + " must be str, not " + Values.typeName(a[i]));
				}
			}
			return replace(s, (String) a[0], (String) a[1], a[2] == null ? -1 : Args.integer(a[2]));
		});
		method(natives, "str", "lower", String.class, (s, args, kwargs, call) -> {
			Args.bind("lower", args, kwargs, 0);
			return s.toLowerCase(Locale.ROOT);
		});
		method(natives, "str", "upper", String.class, (s, args, kwargs, call) -> {
			Args.bind("upper", args, kwargs, 0);
			return s.toUpperCase(Locale.ROOT);
		});
		method(natives, "str", "format", String.class, (s, args, kwargs, call) -> Format.strFormat(s, args, kwargs));
	}

	private static boolean isSpace(int c) {
		return Character.isWhitespace(c) || Character.isSpaceChar(c);
	}

	/** {@code s.split()}: runs of whitespace separate, and none is at either end. */
	private static List<Object> splitWhitespace(String s, long maxsplit) {
		List<Object> parts = new ArrayList<>();
		int i = 0;
		int n = s.length();
		while (true) {
			while (i < n && isSpace(s.charAt(i))) {
				i++;
			}
			if (i >= n) {
				return parts;
			}
			if (maxsplit >= 0 && parts.size() >= maxsplit) {
				parts.add(s.substring(i));
				return parts;
			}
			int start = i;
			while (i < n && !isSpace(s.charAt(i))) {
				i++;
			}
			parts.add(s.substring(start, i));
		}
	}

	private static boolean affix(String s, Object affix, String name, Function<String, Boolean> test) {
		if (affix instanceof String text) {
			return test.apply(text);
		}
		if (affix instanceof PyTuple tuple) {
			for (Object item : tuple.items()) {
				if (!(item instanceof String text)) {
					throw new ScriptError("tuple for " + name + " must only contain str, not " + Values.typeName(item));
				}
				if (test.apply(text)) {
					return true;
				}
			}
			return false;
		}
		throw new ScriptError(name + " first arg must be str or a tuple of str, not " + Values.typeName(affix));
	}

	private static String replace(String s, String old, String replacement, long count) {
		long left = count < 0 ? Long.MAX_VALUE : count;
		StringBuilder out = new StringBuilder();
		if (old.isEmpty()) {
			int[] points = s.codePoints().toArray();
			for (int i = 0; i <= points.length; i++) {
				if (left > 0) {
					out.append(replacement);
					left--;
				}
				if (i < points.length) {
					out.appendCodePoint(points[i]);
				}
			}
			return out.toString();
		}
		int start = 0;
		for (int at = s.indexOf(old); at >= 0 && left > 0; at = s.indexOf(old, start), left--) {
			out.append(s, start, at).append(replacement);
			start = at + old.length();
		}
		return out.append(s.substring(start)).toString();
	}

	// list

	private static void list(Natives natives) {
		method(natives, "list", "append", PyList.class, (l, args, kwargs, call) -> {
			l.items.add(Args.bind("append", args, kwargs, 1, "object")[0]);
			return PyNone.NONE;
		});
		method(natives, "list", "extend", PyList.class, (l, args, kwargs, call) -> {
			l.items.addAll(Values.elements(Args.bind("extend", args, kwargs, 1, "iterable")[0]));
			return PyNone.NONE;
		});
		method(natives, "list", "pop", PyList.class, (l, args, kwargs, call) -> {
			Object index = Args.bind("pop", args, kwargs, 0, "index")[0];
			long i = index == null ? -1 : Args.integer(index);
			if (l.items.isEmpty()) {
				throw new ScriptError("pop from empty list");
			}
			long at = i < 0 ? i + l.items.size() : i;
			if (at < 0 || at >= l.items.size()) {
				throw new ScriptError("pop index out of range");
			}
			return l.items.remove((int) at);
		});
		method(natives, "list", "insert", PyList.class, (l, args, kwargs, call) -> {
			Object[] a = Args.bind("insert", args, kwargs, 2, "index", "object");
			long size = l.items.size();
			long i = Args.integer(a[0]);
			long at = i < 0 ? Math.max(0, i + size) : Math.min(i, size);
			l.items.add((int) at, a[1]);
			return PyNone.NONE;
		});
		method(natives, "list", "remove", PyList.class, (l, args, kwargs, call) -> {
			Object value = Args.bind("remove", args, kwargs, 1, "value")[0];
			for (int i = 0; i < l.items.size(); i++) {
				if (Values.eq(l.items.get(i), value)) {
					l.items.remove(i);
					return PyNone.NONE;
				}
			}
			throw new ScriptError("list.remove(x): x not in list");
		});
		method(natives, "list", "index", PyList.class, (l, args, kwargs, call) -> {
			Object[] a = Args.bind("index", args, kwargs, 1, "value", "start", "stop");
			long size = l.items.size();
			long start = bound(a[1], 0, size);
			long stop = bound(a[2], size, size);
			for (long i = start; i < Math.min(stop, l.items.size()); i++) {
				if (Values.eq(l.items.get((int) i), a[0])) {
					return i;
				}
			}
			throw new ScriptError("list.index(x): x not in list");
		});
		method(natives, "list", "sort", PyList.class, (l, args, kwargs, call) -> {
			if (!args.isEmpty()) {
				throw new ScriptError("sort() takes no positional arguments");
			}
			Object[] a = Args.bind("sort", args, kwargs, 0, "key", "reverse");
			boolean reverse = a[1] != null && Values.truthy(a[1]);
			if (!Args.absent(a[0])) {
				Builtins.checkCallable(a[0]);
				return call.tailCall(Builtins.KEYED, List.of(l, a[0], new PyNative("$sort"), reverse));
			}
			Builtins.sort(l.items, new ArrayList<>(l.items), reverse);
			return PyNone.NONE;
		});
	}

	/** A start or stop index of a slice-like search, clamped to {@code [0, size]}. */
	private static long bound(Object value, long otherwise, long size) {
		if (Args.absent(value)) {
			return otherwise;
		}
		long i = Args.integer(value);
		return i < 0 ? Math.max(0, i + size) : Math.min(i, size);
	}

	// dict

	private static void dict(Natives natives) {
		method(natives, "dict", "get", PyDict.class, (d, args, kwargs, call) -> {
			Object[] a = Args.bind("get", args, kwargs, 1, "key", "default");
			Object value = d.get(a[0]);
			return value != null ? value : a[1] == null ? PyNone.NONE : a[1];
		});
		method(natives, "dict", "keys", PyDict.class, (d, args, kwargs, call) -> {
			Args.bind("keys", args, kwargs, 0);
			return new PyDictView(d, PyDictView.Kind.KEYS);
		});
		method(natives, "dict", "values", PyDict.class, (d, args, kwargs, call) -> {
			Args.bind("values", args, kwargs, 0);
			return new PyDictView(d, PyDictView.Kind.VALUES);
		});
		method(natives, "dict", "items", PyDict.class, (d, args, kwargs, call) -> {
			Args.bind("items", args, kwargs, 0);
			return new PyDictView(d, PyDictView.Kind.ITEMS);
		});
		method(natives, "dict", "pop", PyDict.class, (d, args, kwargs, call) -> {
			Object[] a = Args.bind("pop", args, kwargs, 1, "key", "default");
			Object value = d.remove(a[0]);
			if (value != null) {
				return value;
			}
			if (a[1] == null) {
				throw new ScriptError("key " + Values.repr(a[0]) + " is not in the dict");
			}
			return a[1];
		});
		method(natives, "dict", "setdefault", PyDict.class, (d, args, kwargs, call) -> {
			Object[] a = Args.bind("setdefault", args, kwargs, 1, "key", "default");
			Object value = d.get(a[0]);
			if (value != null) {
				return value;
			}
			Object fallback = a[1] == null ? PyNone.NONE : a[1];
			d.put(a[0], fallback);
			return fallback;
		});
	}
}
