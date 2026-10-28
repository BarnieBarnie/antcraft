package io.github.barniebarnie.antcraft.script.lang;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The builtin functions of docs/scripting.md, with Python's behaviour and CPython's error messages. Integers are 64-bit,
 * so where Python would make a bigger int, the script fails with the overflow error of {@link Values}.
 *
 * <p>Builtins that call a script function ({@code sorted}, {@code min} and {@code max} with a {@code key}) hand the
 * calls to {@link #KEYED}, a function written in the script language, through {@link NativeCall#tailCall}: the VM
 * runs it as any script function, so the script stays pausable, and a native gets the keys when they are all made.
 */
final class Builtins {
	private Builtins() {
	}

	/** The source of the builtins written in the script language; its errors are placed at the script's call. */
	private static final String PRELUDE = """
			def keyed(items, key, finish, extra):
			    return finish(items, [key(x) for x in items], extra)
			""";

	/** {@code keyed(items, key, finish, extra)}: calls {@code finish(items, keys, extra)} with the key of each item. */
	static final PyFunction KEYED = prelude("keyed");

	/** The builtin types, by name, for {@code isinstance}. */
	private static final Map<String, Predicate<Object>> TYPES = Map.of(
			"int", v -> v instanceof Long || v instanceof Boolean,
			"float", v -> v instanceof Double,
			"str", v -> v instanceof String,
			"bool", v -> v instanceof Boolean,
			"list", v -> v instanceof PyList,
			"dict", v -> v instanceof PyDict,
			"tuple", v -> v instanceof PyTuple,
			"range", v -> v instanceof PyRange);

	private static PyFunction prelude(String name) {
		Code module = Compiler.compile(Parser.parse(Natives.BUILTINS_FILE, PRELUDE));
		for (Object constant : module.constants()) {
			if (constant instanceof Code code && code.name().equals(name)) {
				return new PyFunction(code, List.of(), null);
			}
		}
		throw new IllegalStateException("no builtin " + name);
	}

	static void register(Natives natives, Output output) {
		natives.register("print", call -> write(call, output, Output.Level.PRINT));
		natives.register("debug", call -> write(call, output, Output.Level.DEBUG));
		natives.register("info", call -> write(call, output, Output.Level.INFO));
		natives.register("warn", call -> write(call, output, Output.Level.WARN));
		natives.register("error", call -> write(call, output, Output.Level.ERROR));
		natives.register("range", Builtins::range);
		natives.register("len", call -> Values.len(Args.bind("len", call, 1, "obj")[0]));
		natives.register("abs", Builtins::abs);
		natives.register("round", Builtins::round);
		natives.register("sum", Builtins::sum);
		natives.register("min", call -> extreme(call, false));
		natives.register("max", call -> extreme(call, true));
		natives.register("sorted", Builtins::sorted);
		natives.register("reversed", Builtins::reversed);
		natives.register("enumerate", Builtins::enumerate);
		natives.register("zip", Builtins::zip);
		natives.register("str", Builtins::str);
		natives.register("int", Builtins::toInt);
		natives.register("float", Builtins::toFloat);
		natives.register("bool", call -> {
			Object[] a = Args.bind("bool", call, 0, "x");
			return a[0] != null && Values.truthy(a[0]);
		});
		natives.register("list", call -> {
			Object[] a = Args.bind("list", call, 0, "iterable");
			return new PyList(a[0] == null ? new ArrayList<>() : Values.elements(a[0]));
		});
		natives.register("tuple", call -> {
			Object[] a = Args.bind("tuple", call, 0, "iterable");
			return a[0] == null ? PyTuple.EMPTY : new PyTuple(Values.elements(a[0]));
		});
		natives.register("dict", Builtins::dict);
		natives.register("isinstance", Builtins::isinstance);
		natives.register("any", call -> Values.elements(Args.bind("any", call, 1, "iterable")[0]).stream()
				.anyMatch(Values::truthy));
		natives.register("all", call -> Values.elements(Args.bind("all", call, 1, "iterable")[0]).stream()
				.allMatch(Values::truthy));
		// What KEYED hands the keys to; the names are not identifiers, so no script can call or shadow them.
		natives.register("$sorted", call -> {
			PyList items = (PyList) call.args().get(0);
			sort(items.items, ((PyList) call.args().get(1)).items, Values.truthy(call.args().get(2)));
			return items;
		});
		natives.register("$sort", call -> {
			PyList items = (PyList) call.args().get(0);
			sort(items.items, ((PyList) call.args().get(1)).items, Values.truthy(call.args().get(2)));
			return PyNone.NONE;
		});
		natives.register("$extreme", call -> {
			List<Object> extra = ((PyTuple) call.args().get(2)).items();
			return pick(((PyList) call.args().get(0)).items, ((PyList) call.args().get(1)).items,
					(Boolean) extra.get(0), extra.size() > 1 ? extra.get(1) : null);
		});
	}

	private static Object write(NativeCall call, Output output, Output.Level level) {
		String sep = " ";
		for (Map.Entry<String, Object> kwarg : call.kwargs().entrySet()) {
			if (!kwarg.getKey().equals("sep")) {
				throw new ScriptError("'" + kwarg.getKey() + "' is an invalid keyword argument for " + call.name() + "()");
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
		output.write(level, line.toString());
		return PyNone.NONE;
	}

	private static Object range(NativeCall call) {
		Args.noKeywords("range", call.kwargs());
		int n = call.args().size();
		if (n == 0) {
			throw new ScriptError("range expected at least 1 argument, got 0");
		}
		if (n > 3) {
			throw new ScriptError("range expected at most 3 arguments, got " + n);
		}
		long[] values = new long[n];
		for (int i = 0; i < n; i++) {
			values[i] = Args.integer(call.args().get(i));
		}
		return switch (n) {
			case 1 -> new PyRange(0, values[0], 1);
			case 2 -> new PyRange(values[0], values[1], 1);
			default -> new PyRange(values[0], values[1], values[2]);
		};
	}

	private static Object abs(NativeCall call) {
		Object x = Args.bind("abs", call, 1, "x")[0];
		return switch (x) {
			case Long l when l == Long.MIN_VALUE -> throw new ScriptError(Values.OVERFLOW);
			case Long l -> Math.abs(l);
			case Boolean b -> b ? 1L : 0L;
			case Double d -> Math.abs(d);
			default -> throw new ScriptError("bad operand type for abs(): '" + Values.typeName(x) + "'");
		};
	}

	private static Object round(NativeCall call) {
		Object[] a = Args.bind("round", call, 1, "number", "ndigits");
		Object number = a[0];
		if (!Values.isNumber(number)) {
			throw new ScriptError("type " + Values.typeName(number) + " doesn't define __round__ method");
		}
		if (Args.absent(a[1])) {
			if (number instanceof Double d) {
				return toLong(Math.rint(d));
			}
			return Values.asLong(number);
		}
		long digits = Args.integer(a[1]);
		if (number instanceof Double d) {
			if (Double.isNaN(d) || Double.isInfinite(d) || digits > 400) {
				return d;
			}
			if (digits < -400) {
				return Math.copySign(0.0, d);
			}
			double rounded = new BigDecimal(d).setScale((int) digits, RoundingMode.HALF_EVEN).doubleValue();
			return rounded == 0 ? Math.copySign(0.0, d) : rounded;
		}
		long value = Values.asLong(number);
		if (digits >= 0) {
			return value;
		}
		if (digits < -19) {
			return 0L;
		}
		try {
			return BigDecimal.valueOf(value).setScale((int) digits, RoundingMode.HALF_EVEN).longValueExact();
		} catch (ArithmeticException e) {
			throw new ScriptError(Values.OVERFLOW);
		}
	}

	/** A whole float as an int, with Python's errors for infinity, NaN and (64-bit here) overflow. */
	private static long toLong(double d) {
		if (Double.isNaN(d)) {
			throw new ScriptError("cannot convert float NaN to integer");
		}
		if (Double.isInfinite(d)) {
			throw new ScriptError("cannot convert float infinity to integer");
		}
		if (d >= 0x1p63 || d < -0x1p63) {
			throw new ScriptError(Values.OVERFLOW);
		}
		return (long) d;
	}

	private static Object sum(NativeCall call) {
		Object[] a = Args.bind("sum", call, 1, "iterable", "start");
		Object total = a[1] == null ? (Object) 0L : a[1];
		if (total instanceof String) {
			throw new ScriptError("sum() can't sum strings [use ''.join(seq) instead]");
		}
		for (Object item : Values.elements(a[0])) {
			if (item instanceof String && !(Values.isNumber(total))) {
				throw new ScriptError("sum() can't sum strings [use ''.join(seq) instead]");
			}
			total = Values.binary(Ast.BinaryOp.ADD, total, item, false);
		}
		return total;
	}

	/** {@code min} or {@code max}: of an iterable, or of several arguments; with {@code key} and {@code default}. */
	private static Object extreme(NativeCall call, boolean max) {
		String name = call.name();
		Object key = null;
		Object fallback = null;
		for (Map.Entry<String, Object> kwarg : call.kwargs().entrySet()) {
			switch (kwarg.getKey()) {
				case "key" -> key = kwarg.getValue();
				case "default" -> fallback = kwarg.getValue();
				default -> throw new ScriptError("'" + kwarg.getKey() + "' is an invalid keyword argument for " + name
						+ "()");
			}
		}
		List<Object> args = call.args();
		if (args.isEmpty()) {
			throw new ScriptError(name + " expected at least 1 argument, got 0");
		}
		if (args.size() > 1 && fallback != null) {
			throw new ScriptError("Cannot specify a default for " + name + "() with multiple positional arguments");
		}
		PyList items = new PyList(args.size() == 1 ? Values.elements(args.getFirst()) : new ArrayList<>(args));
		PyTuple extra = fallback == null ? PyTuple.of(max) : PyTuple.of(max, fallback);
		if (!Args.absent(key)) {
			checkCallable(key);
			return call.tailCall(KEYED, List.of(items, key, new PyNative("$extreme"), extra));
		}
		return pick(items.items, items.items, max, fallback);
	}

	/**
	 * The item whose key is least (or greatest, for {@code max}), the first of equal ones, as Python compares them;
	 * {@code fallback} when there are none, or the error for an empty iterable when that is null.
	 */
	private static Object pick(List<Object> items, List<Object> keys, boolean max, Object fallback) {
		if (items.isEmpty()) {
			if (fallback == null) {
				throw new ScriptError((max ? "max" : "min") + "() iterable argument is empty");
			}
			return fallback;
		}
		int best = 0;
		for (int i = 1; i < items.size(); i++) {
			if (Values.compare(max ? Ast.CmpOp.GT : Ast.CmpOp.LT, keys.get(i), keys.get(best))) {
				best = i;
			}
		}
		return items.get(best);
	}

	private static Object sorted(NativeCall call) {
		if (call.args().size() != 1) {
			throw new ScriptError("sorted expected 1 argument, got " + call.args().size());
		}
		Object[] a = Args.bind("sorted", List.of(), call.kwargs(), 0, "key", "reverse");
		PyList items = new PyList(Values.elements(call.args().getFirst()));
		boolean reverse = a[1] != null && Values.truthy(a[1]);
		if (!Args.absent(a[0])) {
			checkCallable(a[0]);
			return call.tailCall(KEYED, List.of(items, a[0], new PyNative("$sorted"), reverse));
		}
		sort(items.items, items.items, reverse);
		return items;
	}

	static void checkCallable(Object value) {
		if (!Args.callable(value)) {
			throw new ScriptError("'" + Values.typeName(value) + "' object is not callable");
		}
	}

	/**
	 * Sorts {@code items} by {@code keys} (as long as it), stably, comparing only with {@code <} as Python does; with
	 * {@code reverse}, equal items keep their order too.
	 */
	static void sort(List<Object> items, List<Object> keys, boolean reverse) {
		List<Object> itemList = new ArrayList<>(items);
		List<Object> keyList = new ArrayList<>(keys);
		if (reverse) {
			Collections.reverse(itemList);
			Collections.reverse(keyList);
		}
		int n = itemList.size();
		int[] order = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		mergeSort(order, new int[n], 0, n, keyList);
		List<Object> sorted = new ArrayList<>(n);
		for (int index : order) {
			sorted.add(itemList.get(index));
		}
		if (reverse) {
			Collections.reverse(sorted);
		}
		items.clear();
		items.addAll(sorted);
	}

	private static void mergeSort(int[] order, int[] scratch, int from, int to, List<Object> keys) {
		if (to - from < 2) {
			return;
		}
		int middle = (from + to) >>> 1;
		mergeSort(order, scratch, from, middle, keys);
		mergeSort(order, scratch, middle, to, keys);
		int left = from;
		int right = middle;
		int out = from;
		while (left < middle && right < to) {
			// Take from the right only when it is strictly less, so equal keys keep their order.
			if (Values.compare(Ast.CmpOp.LT, keys.get(order[right]), keys.get(order[left]))) {
				scratch[out++] = order[right++];
			} else {
				scratch[out++] = order[left++];
			}
		}
		while (left < middle) {
			scratch[out++] = order[left++];
		}
		while (right < to) {
			scratch[out++] = order[right++];
		}
		System.arraycopy(scratch, from, order, from, to - from);
	}

	private static Object reversed(NativeCall call) {
		Object sequence = Args.bind("reversed", call, 1, "sequence")[0];
		List<Object> items = switch (sequence) {
			case PyList l -> new ArrayList<>(l.items);
			case PyTuple t -> new ArrayList<>(t.items());
			case String s -> Values.elements(s);
			case PyRange r -> Values.elements(r);
			case PyDict d -> d.keys();
			default -> throw new ScriptError("'" + Values.typeName(sequence) + "' object is not reversible");
		};
		Collections.reverse(items);
		return new Iter.OfItems(sequence instanceof PyDict ? "dict_reversekeyiterator" : "reversed", items);
	}

	private static Object enumerate(NativeCall call) {
		Object[] a = Args.bind("enumerate", call, 1, "iterable", "start");
		return new Iter.OfEnumerate(Values.iter(a[0]), a[1] == null ? 0 : Args.integer(a[1]));
	}

	private static Object zip(NativeCall call) {
		Args.noKeywords("zip", call.kwargs());
		List<Iter> iters = new ArrayList<>();
		call.args().forEach(arg -> iters.add(Values.iter(arg)));
		return new Iter.OfZip(iters);
	}

	private static Object str(NativeCall call) {
		Object[] a = Args.bind("str", call, 0, "object");
		return a[0] == null ? "" : Values.str(a[0]);
	}

	private static final Pattern FLOAT = Pattern.compile(
			"[+-]?(?:\\d(?:_?\\d)*(?:\\.(?:\\d(?:_?\\d)*)?)?|\\.\\d(?:_?\\d)*)(?:[eE][+-]?\\d(?:_?\\d)*)?");
	private static final Pattern SPECIAL_FLOAT = Pattern.compile("[+-]?(?:inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

	private static Object toFloat(NativeCall call) {
		Object x = Args.bind("float", call, 0, "x")[0];
		return switch (x) {
			case null -> 0.0;
			case Double d -> d;
			case Long l -> (double) l;
			case Boolean b -> b ? 1.0 : 0.0;
			case String s -> {
				String text = s.strip();
				if (FLOAT.matcher(text).matches()) {
					yield Double.parseDouble(text.replace("_", ""));
				}
				if (SPECIAL_FLOAT.matcher(text).matches()) {
					boolean negative = text.startsWith("-");
					if (text.toLowerCase(Locale.ROOT).endsWith("nan")) {
						yield Double.NaN;
					}
					yield negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
				}
				throw new ScriptError("could not convert string to float: " + Values.repr(s));
			}
			default -> throw new ScriptError("float() argument must be a string or a real number, not '"
					+ Values.typeName(x) + "'");
		};
	}

	private static Object toInt(NativeCall call) {
		Object[] a = Args.bind("int", call, 0, "x", "base");
		Object x = a[0];
		if (a[1] != null) {
			if (!(x instanceof String s)) {
				throw new ScriptError("int() can't convert non-string with explicit base");
			}
			long base = Args.integer(a[1]);
			if (base != 0 && (base < 2 || base > 36)) {
				throw new ScriptError("int() base must be >= 2 and <= 36, or 0");
			}
			return parseInt(s, (int) base);
		}
		return switch (x) {
			case null -> 0L;
			case Long l -> l;
			case Boolean b -> b ? 1L : 0L;
			case Double d -> toLong(d < 0 ? Math.ceil(d) : Math.floor(d));
			case String s -> parseInt(s, 10);
			default -> throw new ScriptError("int() argument must be a string, a bytes-like object or a real number, "
					+ "not '" + Values.typeName(x) + "'");
		};
	}

	/** Python's {@code int(text, base)}: sign, base prefix (with base 0 or its own base) and underscores between digits. */
	private static long parseInt(String text, int base) {
		ScriptError invalid = new ScriptError("invalid literal for int() with base " + base + ": " + Values.repr(text));
		String s = text.strip();
		boolean negative = false;
		if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
			negative = s.charAt(0) == '-';
			s = s.substring(1);
		}
		int radix = base;
		boolean prefixed = false;
		if (s.length() >= 2 && s.charAt(0) == '0') {
			int prefixBase = switch (Character.toLowerCase(s.charAt(1))) {
				case 'x' -> 16;
				case 'o' -> 8;
				case 'b' -> 2;
				default -> 0;
			};
			if (prefixBase != 0 && (base == 0 || base == prefixBase)) {
				radix = prefixBase;
				s = s.substring(2);
				prefixed = true;
			}
		}
		if (radix == 0) {
			radix = 10;
			if (s.length() > 1 && s.charAt(0) == '0' && !s.replace("_", "").chars().allMatch(c -> c == '0')) {
				throw invalid;
			}
		}
		if (prefixed && s.startsWith("_")) {
			s = s.substring(1);
		}
		if (s.isEmpty() || s.startsWith("_") || s.endsWith("_") || s.contains("__")) {
			throw invalid;
		}
		String digits = s.replace("_", "");
		for (int i = 0; i < digits.length(); i++) {
			if (Character.digit(digits.charAt(i), radix) < 0) {
				throw invalid;
			}
		}
		BigInteger value = new BigInteger(digits, radix);
		if (negative) {
			value = value.negate();
		}
		if (value.bitLength() > 63) {
			throw new ScriptError(Values.OVERFLOW);
		}
		return value.longValue();
	}

	private static Object dict(NativeCall call) {
		if (call.args().size() > 1) {
			throw new ScriptError("dict expected at most 1 argument, got " + call.args().size());
		}
		PyDict dict = new PyDict();
		if (!call.args().isEmpty()) {
			Object source = call.args().getFirst();
			if (source instanceof PyDict d) {
				d.items().forEach(entry -> dict.put(entry.getKey(), entry.getValue()));
			} else {
				List<Object> pairs = Values.elements(source);
				for (int i = 0; i < pairs.size(); i++) {
					List<Object> pair = Values.elements(pairs.get(i));
					if (pair.size() != 2) {
						throw new ScriptError("dictionary update sequence element #" + i + " has length " + pair.size()
								+ "; 2 is required");
					}
					dict.put(pair.get(0), pair.get(1));
				}
			}
		}
		call.kwargs().forEach(dict::put);
		return dict;
	}

	private static Object isinstance(NativeCall call) {
		Object[] a = Args.bind("isinstance", call, 2, "obj", "class_or_tuple");
		List<Object> types = a[1] instanceof PyTuple t ? t.items() : List.of(a[1]);
		boolean matches = false;
		for (Object type : types) {
			Predicate<Object> test = type instanceof PyNative n ? TYPES.get(n.name()) : null;
			if (test == null) {
				throw new ScriptError("isinstance() arg 2 must be a type, a tuple of types, or a union");
			}
			matches |= test.test(a[0]);
		}
		return matches;
	}
}
