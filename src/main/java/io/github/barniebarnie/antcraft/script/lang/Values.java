package io.github.barniebarnie.antcraft.script.lang;

import io.github.barniebarnie.antcraft.script.lang.Ast.BinaryOp;
import io.github.barniebarnie.antcraft.script.lang.Ast.CmpOp;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The values of a script and Python's rules for them. A value is a {@link Long} ({@code int}), {@link Double}
 * ({@code float}), {@link String} ({@code str}), {@link Boolean} ({@code bool}), {@link PyNone}, {@link PyList},
 * {@link PyTuple}, {@link PyDict}, {@link PyRange}, {@link PyDictView}, {@link Iter}, {@link PyFunction}, {@link PyNative}
 * or {@link PyMethod}. Every operation throws a {@link ScriptError} with CPython's message where the script did something
 * Python would not allow.
 */
public final class Values {
	static final String OVERFLOW = "int overflow: the result does not fit in 64 bits";

	private Values() {
	}

	public static String typeName(Object value) {
		return switch (value) {
			case Long l -> "int";
			case Double d -> "float";
			case String s -> "str";
			case Boolean b -> "bool";
			case PyNone n -> "NoneType";
			case PyList l -> "list";
			case PyTuple t -> "tuple";
			case PyDict d -> "dict";
			case PyRange r -> "range";
			case PyDictView v -> v.kind().typeName;
			case PyFunction f -> "function";
			case PyNative n -> "builtin_function_or_method";
			case PyMethod m -> "builtin_function_or_method";
			case Iter i -> i.typeName();
			default -> value.getClass().getSimpleName();
		};
	}

	public static boolean truthy(Object value) {
		return switch (value) {
			case Boolean b -> b;
			case Long l -> l != 0;
			case Double d -> d != 0;
			case String s -> !s.isEmpty();
			case PyNone n -> false;
			case PyList l -> !l.items.isEmpty();
			case PyTuple t -> !t.items().isEmpty();
			case PyDict d -> d.size() != 0;
			case PyRange r -> r.length() != 0;
			case PyDictView v -> v.dict().size() != 0;
			default -> true;
		};
	}

	// Text

	/** {@code str(value)}. */
	public static String str(Object value) {
		return value instanceof String s ? s : repr(value);
	}

	/** {@code repr(value)}. */
	public static String repr(Object value) {
		StringBuilder out = new StringBuilder();
		repr(value, out, Collections.newSetFromMap(new IdentityHashMap<>()));
		return out.toString();
	}

	private static void repr(Object value, StringBuilder out, Set<Object> open) {
		switch (value) {
			case Boolean b -> out.append(b ? "True" : "False");
			case Long l -> out.append(l);
			case Double d -> out.append(floatRepr(d));
			case String s -> out.append(stringRepr(s));
			case PyList l -> {
				if (!open.add(l)) {
					out.append("[...]");
					return;
				}
				out.append('[');
				items(l.items, out, open);
				out.append(']');
				open.remove(l);
			}
			case PyTuple t -> {
				out.append('(');
				items(t.items(), out, open);
				out.append(t.items().size() == 1 ? ",)" : ")");
			}
			case PyDict d -> {
				if (!open.add(d)) {
					out.append("{...}");
					return;
				}
				out.append('{');
				boolean first = true;
				for (Map.Entry<Object, Object> entry : d.items()) {
					if (!first) {
						out.append(", ");
					}
					first = false;
					repr(entry.getKey(), out, open);
					out.append(": ");
					repr(entry.getValue(), out, open);
				}
				out.append('}');
				open.remove(d);
			}
			case PyRange r -> out.append("range(").append(r.start()).append(", ").append(r.stop())
					.append(r.step() == 1 ? ")" : ", " + r.step() + ")");
			case PyDictView v -> {
				out.append(v.kind().typeName).append('(');
				repr(new PyList(elements(v)), out, open);
				out.append(')');
			}
			default -> out.append(value);
		}
	}

	private static void items(List<Object> items, StringBuilder out, Set<Object> open) {
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}
			repr(items.get(i), out, open);
		}
	}

	/** Python's {@code repr} of a float: the shortest digits that read back the same, in exponent form outside 1e-4 to 1e16. */
	static String floatRepr(double d) {
		if (Double.isNaN(d)) {
			return "nan";
		}
		if (Double.isInfinite(d)) {
			return d > 0 ? "inf" : "-inf";
		}
		if (d == 0) {
			return 1 / d < 0 ? "-0.0" : "0.0";
		}
		BigDecimal decimal = new BigDecimal(Double.toString(d)).stripTrailingZeros();
		String digits = decimal.unscaledValue().abs().toString();
		int exponent = digits.length() - 1 - decimal.scale();
		String sign = d < 0 ? "-" : "";
		if (exponent < -4 || exponent >= 16) {
			String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
			return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + (Math.abs(exponent) < 10 ? "0" : "")
					+ Math.abs(exponent);
		}
		String plain = decimal.abs().toPlainString();
		return sign + (plain.contains(".") ? plain : plain + ".0");
	}

	private static String stringRepr(String s) {
		char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
		StringBuilder out = new StringBuilder().append(quote);
		s.codePoints().forEach(c -> {
			switch (c) {
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c == quote) {
						out.append('\\').append(quote);
					} else if (c < 0x20 || c == 0x7f) {
						out.append(String.format("\\x%02x", c));
					} else {
						out.appendCodePoint(c);
					}
				}
			}
		});
		return out.append(quote).toString();
	}

	// Equality, order and hashing

	static boolean isNumber(Object value) {
		return value instanceof Long || value instanceof Double || value instanceof Boolean;
	}

	/** The int value of a bool or int. */
	static long asLong(Object value) {
		return value instanceof Boolean b ? (b ? 1 : 0) : (Long) value;
	}

	static double asDouble(Object value) {
		return value instanceof Double d ? d : asLong(value);
	}

	/**
	 * Compares two numbers exactly, as Python does even for an int that a double cannot hold; null when one is NaN.
	 */
	private static Integer compareNumbers(Object a, Object b) {
		if (!(a instanceof Double) && !(b instanceof Double)) {
			return Long.compare(asLong(a), asLong(b));
		}
		double x = asDouble(a);
		double y = asDouble(b);
		if (Double.isNaN(x) || Double.isNaN(y)) {
			return null;
		}
		if (a instanceof Double && b instanceof Double || Double.isInfinite(x) || Double.isInfinite(y)) {
			return Double.compare(x == 0 ? 0.0 : x, y == 0 ? 0.0 : y);
		}
		BigDecimal left = a instanceof Double ? new BigDecimal(x) : BigDecimal.valueOf(asLong(a));
		BigDecimal right = b instanceof Double ? new BigDecimal(y) : BigDecimal.valueOf(asLong(b));
		return left.compareTo(right);
	}

	/** {@code a == b}. */
	public static boolean eq(Object a, Object b) {
		if (a == b) {
			return !(a instanceof Double d && d.isNaN());
		}
		if (isNumber(a) && isNumber(b)) {
			Integer order = compareNumbers(a, b);
			return order != null && order == 0;
		}
		return switch (a) {
			case String s -> s.equals(b);
			case PyList l -> b instanceof PyList m && itemsEqual(l.items, m.items);
			case PyTuple t -> b instanceof PyTuple u && itemsEqual(t.items(), u.items());
			case PyDict d -> {
				if (!(b instanceof PyDict e) || d.size() != e.size()) {
					yield false;
				}
				for (Map.Entry<Object, Object> entry : d.items()) {
					Object other = e.get(entry.getKey());
					if (other == null || !eq(entry.getValue(), other)) {
						yield false;
					}
				}
				yield true;
			}
			case PyRange r -> b instanceof PyRange s && r.length() == s.length()
					&& (r.length() == 0 || r.start() == s.start() && (r.length() == 1 || r.step() == s.step()));
			// Keys and items compare as sets, as in Python; values views are only equal to themselves.
			case PyDictView v -> v.kind() != PyDictView.Kind.VALUES && b instanceof PyDictView w && w.kind() == v.kind()
					&& v.dict().size() == w.dict().size() && elements(v).stream().allMatch(item -> contains(w, item));
			default -> a.equals(b);
		};
	}

	private static boolean itemsEqual(List<Object> a, List<Object> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (!eq(a.get(i), b.get(i))) {
				return false;
			}
		}
		return true;
	}

	/** {@code a op b} for {@code <}, {@code <=}, {@code >} and {@code >=}. */
	private static boolean order(CmpOp op, Object a, Object b) {
		if (isNumber(a) && isNumber(b)) {
			Integer order = compareNumbers(a, b);
			return order != null && holds(op, order);
		}
		if (a instanceof String s && b instanceof String t) {
			return holds(op, s.compareTo(t));
		}
		List<Object> left = a instanceof PyList l ? l.items : a instanceof PyTuple t ? t.items() : null;
		List<Object> right = b instanceof PyList l && a instanceof PyList ? l.items
				: b instanceof PyTuple t && a instanceof PyTuple ? t.items() : null;
		if (left != null && right != null) {
			int n = Math.min(left.size(), right.size());
			for (int i = 0; i < n; i++) {
				if (!eq(left.get(i), right.get(i))) {
					return order(op, left.get(i), right.get(i));
				}
			}
			return holds(op, Integer.compare(left.size(), right.size()));
		}
		throw new ScriptError("'" + op.symbol + "' not supported between instances of '" + typeName(a) + "' and '"
				+ typeName(b) + "'");
	}

	private static boolean holds(CmpOp op, int order) {
		return switch (op) {
			case LT -> order < 0;
			case LE -> order <= 0;
			case GT -> order > 0;
			case GE -> order >= 0;
			default -> throw new IllegalArgumentException(op.name());
		};
	}

	/** {@code a op b} for every comparison. */
	public static boolean compare(CmpOp op, Object a, Object b) {
		return switch (op) {
			case EQ -> eq(a, b);
			case NE -> !eq(a, b);
			case LT, LE, GT, GE -> order(op, a, b);
			case IN -> contains(b, a);
			case NOT_IN -> !contains(b, a);
			case IS -> a == b || a instanceof PyNone && b instanceof PyNone;
			case IS_NOT -> !(a == b || a instanceof PyNone && b instanceof PyNone);
		};
	}

	/**
	 * The form of a dict key that Java's {@code equals} and {@code hashCode} compare as Python does: bools and whole
	 * floats become ints, and tuples hold keys.
	 */
	static Object key(Object value) {
		return switch (value) {
			case Boolean b -> b ? 1L : 0L;
			case Double d when d == Math.rint(d) && Math.abs(d) < 0x1p63 -> (long) (double) d;
			case Double d -> d;
			case Long l -> l;
			case String s -> s;
			case PyNone n -> n;
			case PyTuple t -> {
				List<Object> keys = new ArrayList<>(t.items().size());
				t.items().forEach(item -> keys.add(key(item)));
				yield new PyTuple(keys);
			}
			case PyList l -> throw unhashable(value);
			case PyDict d -> throw unhashable(value);
			case PyDictView v -> throw unhashable(value);
			default -> value;
		};
	}

	private static ScriptError unhashable(Object value) {
		return new ScriptError("cannot use '" + typeName(value) + "' as a dict key (unhashable type: '" + typeName(value)
				+ "')");
	}

	/** {@code item in container}. */
	public static boolean contains(Object container, Object item) {
		switch (container) {
			case PyList l -> {
				return l.items.stream().anyMatch(x -> eq(x, item));
			}
			case PyTuple t -> {
				return t.items().stream().anyMatch(x -> eq(x, item));
			}
			case String s -> {
				if (!(item instanceof String sub)) {
					throw new ScriptError("'in <string>' requires string as left operand, not " + typeName(item));
				}
				return s.contains(sub);
			}
			case PyDict d -> {
				return d.containsKey(item);
			}
			case PyDictView v -> {
				return switch (v.kind()) {
					case KEYS -> v.dict().containsKey(item);
					case VALUES -> elements(v).stream().anyMatch(x -> eq(x, item));
					case ITEMS -> {
						if (!(item instanceof PyTuple t) || t.items().size() != 2) {
							yield false;
						}
						Object value = v.dict().get(t.items().get(0));
						yield value != null && eq(value, t.items().get(1));
					}
				};
			}
			case Iter i -> {
				for (Object next = i.next(); next != null; next = i.next()) {
					if (eq(next, item)) {
						return true;
					}
				}
				return false;
			}
			case PyRange r -> {
				if (item instanceof Long || item instanceof Boolean) {
					return r.contains(asLong(item));
				}
				if (item instanceof Double d) {
					return d == Math.rint(d) && Math.abs(d) < 0x1p63 && r.contains((long) (double) d);
				}
				return false;
			}
			default -> throw new ScriptError("argument of type '" + typeName(container)
					+ "' is not a container or iterable");
		}
	}

	// Arithmetic

	public static Object unary(Ast.UnaryOp op, Object value) {
		return switch (op) {
			case NOT -> !truthy(value);
			case NEG -> switch (value) {
				case Double d -> -d;
				case Long l when l == Long.MIN_VALUE -> throw new ScriptError(OVERFLOW);
				case Long l -> -l;
				case Boolean b -> b ? -1L : 0L;
				default -> throw new ScriptError("bad operand type for unary -: '" + typeName(value) + "'");
			};
			case POS -> switch (value) {
				case Double d -> d;
				case Long l -> l;
				case Boolean b -> b ? 1L : 0L;
				default -> throw new ScriptError("bad operand type for unary +: '" + typeName(value) + "'");
			};
		};
	}

	/**
	 * {@code a op b}; when {@code inPlace} ({@code a op= b}), a list on the left is changed rather than copied, as
	 * Python's {@code +=} and {@code *=} do.
	 */
	public static Object binary(BinaryOp op, Object a, Object b, boolean inPlace) {
		if (isNumber(a) && isNumber(b)) {
			if (a instanceof Double || b instanceof Double) {
				return floatOp(op, asDouble(a), asDouble(b));
			}
			return intOp(op, asLong(a), asLong(b));
		}
		switch (op) {
			case ADD -> {
				if (a instanceof String s && b instanceof String t) {
					return s + t;
				}
				if (a instanceof PyList l && b instanceof PyList m) {
					if (inPlace) {
						l.items.addAll(new ArrayList<>(m.items));
						return l;
					}
					List<Object> items = new ArrayList<>(l.items);
					items.addAll(m.items);
					return new PyList(items);
				}
				if (a instanceof PyTuple t && b instanceof PyTuple u) {
					List<Object> items = new ArrayList<>(t.items());
					items.addAll(u.items());
					return new PyTuple(items);
				}
			}
			case MUL -> {
				if (isInt(b) && (a instanceof String || a instanceof PyList || a instanceof PyTuple)) {
					return repeat(a, asLong(b), inPlace);
				}
				if (isInt(a) && (b instanceof String || b instanceof PyList || b instanceof PyTuple)) {
					return repeat(b, asLong(a), false);
				}
			}
			default -> {
			}
		}
		throw new ScriptError("unsupported operand type(s) for " + op.symbol + (inPlace ? "=" : "") + ": '"
				+ typeName(a) + "' and '" + typeName(b) + "'");
	}

	static boolean isInt(Object value) {
		return value instanceof Long || value instanceof Boolean;
	}

	private static Object repeat(Object sequence, long times, boolean inPlace) {
		long n = Math.max(0, times);
		switch (sequence) {
			case String s -> {
				checkSize((long) s.length() * n);
				return s.repeat((int) n);
			}
			case PyList l -> {
				List<Object> items = repeated(l.items, n);
				if (inPlace) {
					l.items.clear();
					l.items.addAll(items);
					return l;
				}
				return new PyList(items);
			}
			default -> {
				return new PyTuple(repeated(((PyTuple) sequence).items(), n));
			}
		}
	}

	private static List<Object> repeated(List<Object> items, long n) {
		checkSize(items.size() * n);
		List<Object> out = new ArrayList<>((int) (items.size() * n));
		for (long i = 0; i < n; i++) {
			out.addAll(items);
		}
		return out;
	}

	private static void checkSize(long size) {
		if (size > 1 << 24) {
			throw new ScriptError("result is too large (" + size + " items)");
		}
	}

	private static Object intOp(BinaryOp op, long a, long b) {
		try {
			return switch (op) {
				case ADD -> Math.addExact(a, b);
				case SUB -> Math.subtractExact(a, b);
				case MUL -> Math.multiplyExact(a, b);
				case DIV -> {
					if (b == 0) {
						throw new ScriptError("division by zero");
					}
					yield (double) a / (double) b;
				}
				case FLOOR_DIV -> {
					if (b == 0) {
						throw new ScriptError("division by zero");
					}
					if (a == Long.MIN_VALUE && b == -1) {
						throw new ScriptError(OVERFLOW);
					}
					yield Math.floorDiv(a, b);
				}
				case MOD -> {
					if (b == 0) {
						throw new ScriptError("division by zero");
					}
					yield Math.floorMod(a, b);
				}
				case POW -> {
					if (b < 0) {
						if (a == 0) {
							throw new ScriptError("zero to a negative power");
						}
						yield Math.pow(a, b);
					}
					long result = 1;
					long base = a;
					long exponent = b;
					while (exponent > 0) {
						if ((exponent & 1) != 0) {
							result = Math.multiplyExact(result, base);
						}
						exponent >>= 1;
						if (exponent > 0) {
							base = Math.multiplyExact(base, base);
						}
					}
					yield result;
				}
			};
		} catch (ArithmeticException e) {
			throw new ScriptError(OVERFLOW);
		}
	}

	private static Object floatOp(BinaryOp op, double a, double b) {
		return switch (op) {
			case ADD -> a + b;
			case SUB -> a - b;
			case MUL -> a * b;
			case DIV -> {
				if (b == 0) {
					throw new ScriptError("division by zero");
				}
				yield a / b;
			}
			case FLOOR_DIV -> {
				if (b == 0) {
					throw new ScriptError("division by zero");
				}
				yield floorDivMod(a, b)[0];
			}
			case MOD -> {
				if (b == 0) {
					throw new ScriptError("division by zero");
				}
				yield floorDivMod(a, b)[1];
			}
			case POW -> {
				if (a == 0 && b < 0) {
					throw new ScriptError("zero to a negative power");
				}
				if (a < 0 && b != Math.rint(b) && !Double.isInfinite(b)) {
					throw new ScriptError("a negative number to a fractional power has no real result");
				}
				yield Math.pow(a, b);
			}
		};
	}

	/** CPython's {@code float_divmod}. */
	private static double[] floorDivMod(double a, double b) {
		double mod = a % b;
		double div = (a - mod) / b;
		if (mod != 0) {
			if (b < 0 != mod < 0) {
				mod += b;
				div -= 1;
			}
		} else {
			mod = Math.copySign(0, b);
		}
		double floor;
		if (div != 0) {
			floor = Math.floor(div);
			if (div - floor > 0.5) {
				floor += 1;
			}
		} else {
			floor = Math.copySign(0, a / b);
		}
		return new double[] {floor, mod};
	}

	// Indexing

	/** An index or slice bound as a long: an int or bool. */
	private static long index(Object value, String what) {
		if (isInt(value)) {
			return asLong(value);
		}
		throw new ScriptError(what + " indices must be integers or slices, not " + typeName(value));
	}

	/** {@code container[index]}; the index is a {@link Slice} for slicing. */
	public static Object getItem(Object container, Object index) {
		if (index instanceof Slice slice) {
			return getSlice(container, slice);
		}
		switch (container) {
			case PyList l -> {
				return l.items.get(position(index(index, "list"), l.items.size(), "list index out of range"));
			}
			case PyTuple t -> {
				return t.items().get(position(index(index, "tuple"), t.items().size(), "tuple index out of range"));
			}
			case String s -> {
				int[] points = s.codePoints().toArray();
				int i = position(index(index, "string"), points.length, "string index out of range");
				return new String(points, i, 1);
			}
			case PyDict d -> {
				Object value = d.get(index);
				if (value == null) {
					throw new ScriptError("key " + repr(index) + " is not in the dict");
				}
				return value;
			}
			case PyRange r -> {
				return r.get(position(index(index, "range"), r.length(), "range object index out of range"));
			}
			default -> throw new ScriptError("'" + typeName(container) + "' object is not subscriptable");
		}
	}

	private static int position(long index, long size, String outOfRange) {
		long i = index < 0 ? index + size : index;
		if (i < 0 || i >= size) {
			throw new ScriptError(outOfRange);
		}
		return (int) i;
	}

	/** {@code container[index] = value}. */
	public static void setItem(Object container, Object index, Object value) {
		switch (container) {
			case PyList l -> {
				if (index instanceof Slice slice) {
					setSlice(l, slice, value);
				} else {
					l.items.set(position(index(index, "list"), l.items.size(), "list assignment index out of range"), value);
				}
			}
			case PyDict d -> d.put(index, value);
			default -> throw new ScriptError("'" + typeName(container) + "' object does not support item assignment");
		}
	}

	/** A slice {@code lower:upper:step}, as a subscript; each part is {@link PyNone} when left out. */
	public record Slice(Object lower, Object upper, Object step) {
		/** Start, step and length of the slice over a sequence of {@code size} items, by CPython's rules. */
		long[] indices(long size) {
			long step = step() instanceof PyNone ? 1 : bound(step());
			if (step == 0) {
				throw new ScriptError("slice step cannot be zero");
			}
			long start = lower() instanceof PyNone ? (step > 0 ? 0 : size - 1) : adjust(bound(lower()), size, step);
			long stop = upper() instanceof PyNone ? (step > 0 ? size : -1) : adjust(bound(upper()), size, step);
			long length = step > 0 ? (stop > start ? (stop - start - 1) / step + 1 : 0)
					: (start > stop ? (start - stop - 1) / -step + 1 : 0);
			return new long[] {start, step, length};
		}

		private static long bound(Object value) {
			if (isInt(value)) {
				return asLong(value);
			}
			throw new ScriptError("slice indices must be integers or None");
		}

		private static long adjust(long index, long size, long step) {
			if (index < 0) {
				index += size;
				if (index < 0) {
					index = step < 0 ? -1 : 0;
				}
			} else if (index >= size) {
				index = step < 0 ? size - 1 : size;
			}
			return index;
		}
	}

	private static Object getSlice(Object container, Slice slice) {
		if (container instanceof String s) {
			int[] points = s.codePoints().toArray();
			long[] range = slice.indices(points.length);
			StringBuilder out = new StringBuilder();
			for (long i = 0, at = range[0]; i < range[2]; i++, at += range[1]) {
				out.appendCodePoint(points[(int) at]);
			}
			return out.toString();
		}
		List<Object> items = switch (container) {
			case PyList l -> l.items;
			case PyTuple t -> t.items();
			default -> throw new ScriptError("'" + typeName(container) + "' object is not subscriptable");
		};
		long[] range = slice.indices(items.size());
		List<Object> out = new ArrayList<>((int) range[2]);
		for (long i = 0, at = range[0]; i < range[2]; i++, at += range[1]) {
			out.add(items.get((int) at));
		}
		return container instanceof PyList ? new PyList(out) : new PyTuple(out);
	}

	private static void setSlice(PyList list, Slice slice, Object value) {
		List<Object> values = elements(value);
		long[] range = slice.indices(list.items.size());
		if (range[1] == 1) {
			int start = (int) range[0];
			list.items.subList(start, start + (int) range[2]).clear();
			list.items.addAll(start, values);
			return;
		}
		if (values.size() != range[2]) {
			throw new ScriptError("attempt to assign sequence of size " + values.size() + " to extended slice of size "
					+ range[2]);
		}
		for (long i = 0, at = range[0]; i < range[2]; i++, at += range[1]) {
			list.items.set((int) at, values.get((int) i));
		}
	}

	/** {@code len(value)}. */
	public static long len(Object value) {
		return switch (value) {
			case String s -> s.codePointCount(0, s.length());
			case PyList l -> l.items.size();
			case PyTuple t -> t.items().size();
			case PyDict d -> d.size();
			case PyDictView v -> v.dict().size();
			case PyRange r -> r.length();
			default -> throw new ScriptError("object of type '" + typeName(value) + "' has no len()");
		};
	}

	// Iteration

	/** The items of an iterable value, copied, as {@code for} would visit them. */
	public static List<Object> elements(Object iterable) {
		Iter iter = iter(iterable);
		List<Object> out = new ArrayList<>();
		for (Object next = iter.next(); next != null; next = iter.next()) {
			out.add(next);
		}
		return out;
	}

	/** {@code iter(value)}. */
	public static Iter iter(Object iterable) {
		return switch (iterable) {
			case PyList l -> new Iter.OfList(l);
			case PyTuple t -> new Iter.OfTuple(t);
			case String s -> new Iter.OfString(s);
			case PyDict d -> new Iter.OfDict(d, PyDictView.Kind.KEYS);
			case PyDictView v -> new Iter.OfDict(v.dict(), v.kind());
			case Iter i -> i;
			case PyRange r -> new Iter.OfRange(r);
			default -> throw new ScriptError("'" + typeName(iterable) + "' object is not iterable");
		};
	}
}
