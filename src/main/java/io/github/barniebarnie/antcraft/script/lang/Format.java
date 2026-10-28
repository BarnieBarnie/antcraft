package io.github.barniebarnie.antcraft.script.lang;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Python's text formatting: {@code format(value, spec)} with the format specification mini-language, f-string fields
 * with their conversions, {@code str.format} and {@code str % args}. Numbers are rounded from their exact binary
 * value, half to even, as CPython does.
 */
final class Format {
	private Format() {
	}

	/** An f-string field: {@code value} converted ({@code 's'}, {@code 'r'}, {@code 'a'} or {@code '\0'}), then formatted. */
	static String field(Object value, char conversion, String spec) {
		Object converted = switch (conversion) {
			case 's' -> Values.str(value);
			case 'r' -> Values.repr(value);
			case 'a' -> ascii(Values.repr(value));
			default -> value;
		};
		return format(converted, spec);
	}

	/** {@code repr} text with every non-ASCII character escaped, as Python's {@code ascii}. */
	private static String ascii(String text) {
		StringBuilder out = new StringBuilder();
		text.codePoints().forEach(c -> {
			if (c < 0x80) {
				out.appendCodePoint(c);
			} else if (c < 0x100) {
				out.append(String.format("\\x%02x", c));
			} else if (c < 0x10000) {
				out.append(String.format("\\u%04x", c));
			} else {
				out.append(String.format("\\U%08x", c));
			}
		});
		return out.toString();
	}

	/** A parsed format spec: {@code [[fill]align][sign][z][#][0][width][grouping][.precision][type]}. */
	private record Spec(char fill, boolean fillGiven, char align, char sign, boolean z, boolean alt, boolean zero,
			int width, char grouping, int precision, char type) {
		static Spec parse(String spec, String typeName) {
			int n = spec.length();
			int i = 0;
			char fill = ' ';
			boolean fillGiven = false;
			char align = 0;
			if (n >= 2 && isAlign(spec.charAt(1))) {
				fill = spec.charAt(0);
				fillGiven = true;
				align = spec.charAt(1);
				i = 2;
			} else if (n >= 1 && isAlign(spec.charAt(0))) {
				align = spec.charAt(0);
				i = 1;
			}
			char sign = 0;
			if (i < n && (spec.charAt(i) == '+' || spec.charAt(i) == '-' || spec.charAt(i) == ' ')) {
				sign = spec.charAt(i++);
			}
			boolean z = i < n && spec.charAt(i) == 'z';
			if (z) {
				i++;
			}
			boolean alt = i < n && spec.charAt(i) == '#';
			if (alt) {
				i++;
			}
			boolean zero = i < n && spec.charAt(i) == '0';
			if (zero) {
				i++;
			}
			int width = -1;
			int start = i;
			while (i < n && Character.isDigit(spec.charAt(i))) {
				i++;
			}
			if (i > start) {
				width = number(spec.substring(start, i));
			}
			char grouping = 0;
			if (i < n && (spec.charAt(i) == ',' || spec.charAt(i) == '_')) {
				grouping = spec.charAt(i++);
			}
			int precision = -1;
			if (i < n && spec.charAt(i) == '.') {
				start = ++i;
				while (i < n && Character.isDigit(spec.charAt(i))) {
					i++;
				}
				if (i == start) {
					throw new ScriptError("Format specifier missing precision");
				}
				precision = number(spec.substring(start, i));
			}
			if (n - i > 1) {
				throw new ScriptError("Invalid format specifier '" + spec + "' for object of type '" + typeName + "'");
			}
			char type = i < n ? spec.charAt(i) : 0;
			return new Spec(zero && !fillGiven ? '0' : fill, fillGiven, align, sign, z, alt, zero, width, grouping,
					precision, type);
		}

		private static boolean isAlign(char c) {
			return c == '<' || c == '>' || c == '^' || c == '=';
		}

		private static int number(String digits) {
			try {
				return Integer.parseInt(digits);
			} catch (NumberFormatException e) {
				throw new ScriptError("Too many decimal digits in format string");
			}
		}

		/** The alignment, or the default for numbers: after the sign with the 0 flag, else right. */
		char numberAlign() {
			return align != 0 ? align : zero ? '=' : '>';
		}
	}

	/** {@code format(value, spec)}. */
	static String format(Object value, String spec) {
		if (spec.isEmpty()) {
			return Values.str(value);
		}
		return switch (value) {
			case String s -> string(s, Spec.parse(spec, "str"));
			case Boolean b -> integer(b ? 1 : 0, Spec.parse(spec, "bool"), "bool");
			case Long l -> integer(l, Spec.parse(spec, "int"), "int");
			case Double d -> floating(d, Spec.parse(spec, "float"));
			default -> throw new ScriptError("unsupported format string passed to " + Values.typeName(value)
					+ ".__format__");
		};
	}

	private static ScriptError unknownCode(char type, String typeName) {
		return new ScriptError("Unknown format code '" + type + "' for object of type '" + typeName + "'");
	}

	private static String string(String s, Spec spec) {
		if (spec.type() != 0 && spec.type() != 's') {
			throw unknownCode(spec.type(), "str");
		}
		if (spec.sign() != 0) {
			throw new ScriptError("Sign not allowed in string format specifier");
		}
		if (spec.alt()) {
			throw new ScriptError("Alternate form (#) not allowed in string format specifier");
		}
		if (spec.z()) {
			throw new ScriptError("Negative zero coercion (z) not allowed in format specifier");
		}
		if (spec.align() == '=') {
			throw new ScriptError("'=' alignment not allowed in string format specifier");
		}
		if (spec.grouping() != 0) {
			throw new ScriptError("Cannot specify '" + spec.grouping() + "' with 's'.");
		}
		String text = s;
		if (spec.precision() >= 0 && s.codePointCount(0, s.length()) > spec.precision()) {
			text = s.substring(0, s.offsetByCodePoints(0, spec.precision()));
		}
		return pad("", text, spec.fill(), spec.align() != 0 ? spec.align() : '<', spec.width());
	}

	private static String integer(long value, Spec spec, String typeName) {
		char type = spec.type();
		switch (type) {
			case 'e', 'E', 'f', 'F', 'g', 'G', '%' -> {
				return floating((double) value, spec);
			}
			case 0, 'd', 'n', 'b', 'o', 'x', 'X', 'c' -> {
			}
			default -> throw unknownCode(type, typeName);
		}
		if (spec.precision() >= 0) {
			throw new ScriptError("Precision not allowed in integer format specifier");
		}
		if (spec.z()) {
			throw new ScriptError("Negative zero coercion (z) not allowed in integer format specifier");
		}
		if (spec.grouping() != 0 && (type == 'n' || type == 'c'
				|| spec.grouping() == ',' && (type == 'b' || type == 'o' || type == 'x' || type == 'X'))) {
			throw new ScriptError("Cannot specify '" + spec.grouping() + "' with '" + type + "'.");
		}
		String sign = value < 0 ? "-" : spec.sign() == '+' ? "+" : spec.sign() == ' ' ? " " : "";
		if (type == 'c') {
			if (spec.sign() != 0) {
				throw new ScriptError("Sign not allowed with integer format specifier 'c'");
			}
			if (spec.alt()) {
				throw new ScriptError("Alternate form (#) is not allowed with integer format specifier 'c'");
			}
			if (value < 0 || value > 0x10FFFF) {
				throw new ScriptError("%c arg not in range(0x110000)");
			}
			return pad("", Character.toString((int) value), spec.fill(), spec.numberAlign(), spec.width());
		}
		int radix = switch (type) {
			case 'b' -> 2;
			case 'o' -> 8;
			case 'x', 'X' -> 16;
			default -> 10;
		};
		String digits = BigInteger.valueOf(value).abs().toString(radix);
		if (type == 'X') {
			digits = digits.toUpperCase();
		}
		String prefix = !spec.alt() ? "" : switch (type) {
			case 'b' -> "0b";
			case 'o' -> "0o";
			case 'x' -> "0x";
			case 'X' -> "0X";
			default -> "";
		};
		return number(sign + prefix, digits, "", spec, radix == 10 ? 3 : 4);
	}

	private static String floating(double value, Spec spec) {
		char type = spec.type();
		switch (type) {
			case 0, 'e', 'E', 'f', 'F', 'g', 'G', 'n', '%' -> {
			}
			default -> throw unknownCode(type, "float");
		}
		boolean negative = !Double.isNaN(value) && (value < 0 || value == 0 && 1 / value < 0);
		double magnitude = Math.abs(value);
		int precision = spec.precision();
		String body;
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			body = Double.isNaN(value) ? "nan" : "inf";
			if (type == 'E' || type == 'F' || type == 'G') {
				body = body.toUpperCase();
			}
			if (type == '%') {
				body += "%";
			}
		} else {
			body = switch (type) {
				case 'f', 'F' -> fixed(magnitude, precision < 0 ? 6 : precision, spec.alt());
				case 'e', 'E' -> {
					String text = scientific(magnitude, precision < 0 ? 6 : precision, spec.alt());
					yield type == 'E' ? text.toUpperCase() : text;
				}
				case '%' -> fixed(magnitude * 100, precision < 0 ? 6 : precision, spec.alt()) + "%";
				case 'g', 'G', 'n' -> {
					String text = general(magnitude, precision < 0 ? 6 : precision, spec.alt(), false);
					yield type == 'G' ? text.toUpperCase() : text;
				}
				default -> {
					if (precision < 0) {
						yield Values.floatRepr(magnitude);
					}
					String text = general(magnitude, precision, spec.alt(), true);
					yield text.contains(".") || text.contains("e") ? text : text + ".0";
				}
			};
		}
		if (spec.z() && negative && body.chars().noneMatch(c -> c >= '1' && c <= '9')) {
			negative = false;
		}
		String sign = negative ? "-" : spec.sign() == '+' ? "+" : spec.sign() == ' ' ? " " : "";
		int end = 0;
		while (end < body.length() && Character.isDigit(body.charAt(end))) {
			end++;
		}
		return number(sign, body.substring(0, end), body.substring(end), spec, 3);
	}

	/** {@code value} with {@code decimals} digits after the point. */
	private static String fixed(double value, int decimals, boolean alt) {
		String text = new BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
		return alt && decimals == 0 ? text + "." : text;
	}

	/** {@code value} as {@code d.ddde+XX} with {@code decimals} digits after the point. */
	private static String scientific(double value, int decimals, boolean alt) {
		String digits;
		int exponent;
		if (value == 0) {
			digits = "0".repeat(decimals + 1);
			exponent = 0;
		} else {
			BigDecimal rounded = new BigDecimal(value).round(new MathContext(decimals + 1, RoundingMode.HALF_EVEN));
			String unscaled = rounded.unscaledValue().toString();
			exponent = unscaled.length() - 1 - rounded.scale();
			digits = (unscaled + "0".repeat(decimals + 1)).substring(0, decimals + 1);
		}
		String mantissa = digits.charAt(0) + (decimals > 0 ? "." + digits.substring(1) : alt ? "." : "");
		return mantissa + "e" + (exponent < 0 ? "-" : "+") + (Math.abs(exponent) < 10 ? "0" : "") + Math.abs(exponent);
	}

	/**
	 * {@code value} with {@code precision} significant digits, fixed or scientific by its size, as {@code 'g'}. With
	 * {@code pointZero} (a spec without a type), a fixed result gets {@code .0} added (by the caller), so it switches to
	 * scientific one digit earlier, as CPython does.
	 */
	private static String general(double value, int precision, boolean alt, boolean pointZero) {
		int p = precision == 0 ? 1 : precision;
		int exponent = 0;
		if (value != 0) {
			BigDecimal rounded = new BigDecimal(value).round(new MathContext(p, RoundingMode.HALF_EVEN));
			exponent = rounded.precision() - 1 - rounded.scale();
		}
		String text;
		if (exponent >= -4 && exponent < (pointZero ? p - 1 : p)) {
			text = fixed(value, p - 1 - exponent, alt);
			if (!alt && text.contains(".")) {
				text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
			}
		} else {
			text = scientific(value, p - 1, alt);
			if (!alt) {
				int e = text.indexOf('e');
				String mantissa = text.substring(0, e);
				if (mantissa.contains(".")) {
					mantissa = mantissa.replaceAll("0+$", "").replaceAll("\\.$", "");
				}
				text = mantissa + text.substring(e);
			}
		}
		return text;
	}

	/**
	 * A number's text: its sign and prefix, its integer digits (grouped every {@code groupSize} when asked) and the
	 * rest (fraction, exponent), padded to the width. With the 0 flag, zeros pad the digits, grouped as well.
	 */
	private static String number(String sign, String digits, String rest, Spec spec, int groupSize) {
		char align = spec.numberAlign();
		String grouped = group(digits, spec.grouping(), groupSize);
		if (spec.zero() && !spec.fillGiven() && align == '=' && spec.grouping() != 0) {
			while (sign.length() + grouped.length() + rest.length() < spec.width()) {
				digits = "0" + digits;
				grouped = group(digits, spec.grouping(), groupSize);
			}
		}
		return pad(sign, grouped + rest, spec.fill(), align, spec.width());
	}

	private static String group(String digits, char separator, int size) {
		if (separator == 0) {
			return digits;
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < digits.length(); i++) {
			if (i > 0 && (digits.length() - i) % size == 0) {
				out.append(separator);
			}
			out.append(digits.charAt(i));
		}
		return out.toString();
	}

	/** {@code prefix + text}, padded with {@code fill} to {@code width}; {@code '='} pads between the two. */
	private static String pad(String prefix, String text, char fill, char align, int width) {
		int length = prefix.codePointCount(0, prefix.length()) + text.codePointCount(0, text.length());
		int padding = Math.max(0, width - length);
		String fills = String.valueOf(fill);
		return switch (align) {
			case '<' -> prefix + text + fills.repeat(padding);
			case '^' -> fills.repeat(padding / 2) + prefix + text + fills.repeat(padding - padding / 2);
			case '=' -> prefix + fills.repeat(padding) + text;
			default -> fills.repeat(padding) + prefix + text;
		};
	}

	/**
	 * {@code template.format(*args, **kwargs)}: fields {@code {}}, {@code {0}} or {@code {name}}, each with an optional
	 * conversion and spec. A spec may hold replacement fields of its own, one level deep, as in {@code '{:{}}'}.
	 */
	static String strFormat(String template, List<Object> args, Map<String, Object> kwargs) {
		return new StrFormat(args, kwargs).render(template, 2);
	}

	/** One {@code str.format} call: its arguments and the automatic field numbering, which nested specs share. */
	private static final class StrFormat {
		private final List<Object> args;
		private final Map<String, Object> kwargs;
		private int next;
		private Boolean manual;

		StrFormat(List<Object> args, Map<String, Object> kwargs) {
			this.args = args;
			this.kwargs = kwargs;
		}

		/** Formats {@code template}; {@code depth} is how many levels of fields it may still nest, as in CPython. */
		String render(String template, int depth) {
			if (depth <= 0) {
				throw new ScriptError("Max string recursion exceeded");
			}
			StringBuilder out = new StringBuilder();
			int n = template.length();
			int i = 0;
			while (i < n) {
				char c = template.charAt(i);
				if (c == '}') {
					if (i + 1 < n && template.charAt(i + 1) == '}') {
						out.append('}');
						i += 2;
						continue;
					}
					throw new ScriptError("Single '}' encountered in format string");
				}
				if (c != '{') {
					out.append(c);
					i++;
					continue;
				}
				if (i + 1 < n && template.charAt(i + 1) == '{') {
					out.append('{');
					i += 2;
					continue;
				}
				int end = i + 1;
				int level = 1;
				boolean inSpec = false;
				while (end < n) {
					char d = template.charAt(end);
					if (d == '{') {
						level++;
					} else if (d == '}' && --level == 0) {
						break;
					} else if (d == ':') {
						inSpec = true;
					}
					end++;
				}
				if (end >= n) {
					throw new ScriptError(i + 1 == n ? "Single '{' encountered in format string"
							: inSpec ? "unmatched '{' in format spec" : "expected '}' before end of string");
				}
				out.append(field(template.substring(i + 1, end), depth));
				i = end + 1;
			}
			return out.toString();
		}

		private String field(String field, int depth) {
			int colon = field.indexOf(':');
			String spec = colon < 0 ? "" : field.substring(colon + 1);
			String head = colon < 0 ? field : field.substring(0, colon);
			if (head.indexOf('{') >= 0) {
				throw new ScriptError("unexpected '{' in field name");
			}
			char conversion = 0;
			int bang = head.indexOf('!');
			if (bang >= 0) {
				String conv = head.substring(bang + 1);
				if (conv.length() != 1) {
					throw new ScriptError(conv.isEmpty() ? "end of string while looking for conversion specifier"
							: "expected ':' after conversion specifier");
				}
				conversion = conv.charAt(0);
				if (conversion != 'r' && conversion != 's' && conversion != 'a') {
					throw new ScriptError("Unknown conversion specifier " + conversion);
				}
				head = head.substring(0, bang);
			}
			// The field's own value comes first, then those of its spec, so '{:{}}' takes argument 0 and width 1.
			Object value = value(head);
			if (spec.indexOf('{') >= 0) {
				spec = render(spec, depth - 1);
			}
			return Format.field(value, conversion, spec);
		}

		private Object value(String head) {
			if (head.isEmpty()) {
				if (Boolean.TRUE.equals(manual)) {
					throw new ScriptError("cannot switch from manual field specification to automatic field numbering");
				}
				manual = false;
				return positional(args, next++);
			}
			if (head.chars().allMatch(Character::isDigit)) {
				if (Boolean.FALSE.equals(manual)) {
					throw new ScriptError("cannot switch from automatic field numbering to manual field specification");
				}
				manual = true;
				return positional(args, head.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(head));
			}
			if (Tokenizer.isIdentifier(head)) {
				Object value = kwargs.get(head);
				if (value == null) {
					throw new ScriptError("key '" + head + "' is not in the keyword arguments");
				}
				return value;
			}
			throw new ScriptError("format field '" + head + "' is not supported (only {}, {0} and {name})");
		}
	}

	/**
	 * {@code template % args}, Python's printf-style formatting: {@code %[(key)][flags][width][.precision]type} with
	 * the flags {@code - + space # 0}, {@code *} for a width or precision taken from the arguments, and the types
	 * {@code d i u o x X e E f F g G c s r a %}. A tuple gives one value a field, a dict the values of named fields;
	 * anything else is the one value.
	 */
	static String percent(String template, Object args) {
		Percent state = new Percent(args);
		StringBuilder out = new StringBuilder();
		int n = template.length();
		int i = 0;
		while (i < n) {
			char c = template.charAt(i++);
			if (c != '%') {
				out.append(c);
				continue;
			}
			Object keyed = null;
			if (i < n && template.charAt(i) == '(') {
				if (!state.mapping) {
					throw new ScriptError("format requires a mapping");
				}
				int depth = 1;
				int start = ++i;
				while (i < n && depth > 0) {
					char k = template.charAt(i++);
					depth += k == '(' ? 1 : k == ')' ? -1 : 0;
				}
				if (depth > 0) {
					throw new ScriptError("incomplete format key");
				}
				keyed = state.key(template.substring(start, i - 1));
			}
			boolean minus = false;
			boolean plus = false;
			boolean space = false;
			boolean alt = false;
			boolean zero = false;
			flags:
			while (i < n) {
				switch (template.charAt(i)) {
					case '-' -> minus = true;
					case '+' -> plus = true;
					case ' ' -> space = true;
					case '#' -> alt = true;
					case '0' -> zero = true;
					default -> {
						break flags;
					}
				}
				i++;
			}
			int width = -1;
			if (i < n && template.charAt(i) == '*') {
				i++;
				width = state.star();
				if (width < 0) {
					minus = true;
					width = -width;
				}
			} else {
				int start = i;
				while (i < n && Character.isDigit(template.charAt(i))) {
					i++;
				}
				if (i > start) {
					width = Spec.number(template.substring(start, i));
				}
			}
			int precision = -1;
			if (i < n && template.charAt(i) == '.') {
				i++;
				if (i < n && template.charAt(i) == '*') {
					i++;
					precision = Math.max(0, state.star());
				} else {
					int start = i;
					while (i < n && Character.isDigit(template.charAt(i))) {
						i++;
					}
					precision = i > start ? Spec.number(template.substring(start, i)) : 0;
				}
			}
			while (i < n && (template.charAt(i) == 'h' || template.charAt(i) == 'l' || template.charAt(i) == 'L')) {
				i++;
			}
			if (i >= n) {
				throw new ScriptError("incomplete format");
			}
			char type = template.charAt(i++);
			if (type == '%') {
				out.append('%');
				continue;
			}
			if ("diuoxXeEfFgGcsra".indexOf(type) < 0) {
				throw new ScriptError("unsupported format character '" + (type < 0x80 ? type : '?') + "' (0x"
						+ Integer.toHexString(type) + ") at index " + (i - 1));
			}
			Object value = keyed != null ? keyed : state.next();
			char sign = plus ? '+' : space ? ' ' : 0;
			out.append(switch (type) {
				case 's', 'r', 'a' -> {
					String text = type == 's' ? Values.str(value) : type == 'r' ? Values.repr(value)
							: ascii(Values.repr(value));
					if (precision >= 0 && text.codePointCount(0, text.length()) > precision) {
						text = text.substring(0, text.offsetByCodePoints(0, precision));
					}
					yield pad("", text, ' ', minus ? '<' : '>', width);
				}
				case 'c' -> pad("", percentChar(value), ' ', minus ? '<' : '>', width);
				case 'd', 'i', 'u', 'o', 'x', 'X' -> {
					long number = percentInteger(value, type);
					int radix = type == 'o' ? 8 : type == 'x' || type == 'X' ? 16 : 10;
					String digits = BigInteger.valueOf(number).abs().toString(radix);
					if (type == 'X') {
						digits = digits.toUpperCase();
					}
					if (precision > digits.length()) {
						digits = "0".repeat(precision - digits.length()) + digits;
					}
					String prefix = (number < 0 ? "-" : sign == 0 ? "" : String.valueOf(sign))
							+ (!alt || radix == 10 ? "" : type == 'o' ? "0o" : type == 'x' ? "0x" : "0X");
					yield pad(prefix, digits, zero && !minus ? '0' : ' ', minus ? '<' : zero ? '=' : '>', width);
				}
				default -> {
					double number = switch (value) {
						case Double d -> d;
						case Long l -> (double) l;
						case Boolean b -> b ? 1 : 0;
						default -> throw new ScriptError("must be real number, not " + Values.typeName(value));
					};
					boolean zeros = zero && !minus;
					yield floating(number, new Spec(zeros ? '0' : ' ', false, minus ? '<' : 0, sign, false, alt, zeros,
							width, (char) 0, precision, type));
				}
			});
		}
		state.checkAllUsed();
		return out.toString();
	}

	/** The arguments of {@code str % args} and how far the conversions have used them. */
	private static final class Percent {
		final Object args;
		final List<Object> items;
		/** A dict (or another value with items) whose named fields {@code %(key)s} read. */
		final boolean mapping;
		int next;
		boolean keyed;

		Percent(Object args) {
			this.args = args;
			items = args instanceof PyTuple tuple ? tuple.items() : List.of(args);
			mapping = args instanceof PyDict || args instanceof PyList;
		}

		Object key(String key) {
			if (next > 0) {
				throw new ScriptError("not enough arguments for format string");
			}
			keyed = true;
			if (args instanceof PyDict dict) {
				Object value = dict.get(key);
				if (value == null) {
					throw new ScriptError("key " + Values.repr(key) + " is not in the dict");
				}
				return value;
			}
			throw new ScriptError(Values.typeName(args) + " indices must be integers or slices, not str");
		}

		Object next() {
			if (next >= items.size() || keyed && !(args instanceof PyTuple)) {
				throw new ScriptError("not enough arguments for format string");
			}
			return items.get(next++);
		}

		int star() {
			Object value = next();
			if (!Values.isInt(value)) {
				throw new ScriptError("* wants int");
			}
			long width = value instanceof Boolean b ? (b ? 1 : 0) : (Long) value;
			if (Math.abs(width) > Integer.MAX_VALUE) {
				throw new ScriptError("width too big");
			}
			return (int) width;
		}

		void checkAllUsed() {
			if (!mapping && next < items.size()) {
				throw new ScriptError("not all arguments converted during string formatting");
			}
		}
	}

	private static String percentChar(Object value) {
		if (value instanceof String s) {
			if (s.codePointCount(0, s.length()) != 1) {
				throw new ScriptError("%c requires an int or a unicode character, not a string of length "
						+ s.codePointCount(0, s.length()));
			}
			return s;
		}
		if (!Values.isInt(value)) {
			throw new ScriptError("%c requires an int or a unicode character, not " + Values.typeName(value));
		}
		long code = value instanceof Boolean b ? (b ? 1 : 0) : (Long) value;
		if (code < 0 || code > 0x10FFFF) {
			throw new ScriptError("%c arg not in range(0x110000)");
		}
		return Character.toString((int) code);
	}

	/** The integer {@code %d} and its kin show: {@code %d}, {@code %i} and {@code %u} truncate a float. */
	private static long percentInteger(Object value, char type) {
		boolean decimal = type == 'd' || type == 'i' || type == 'u';
		return switch (value) {
			case Long l -> l;
			case Boolean b -> b ? 1 : 0;
			case Double d when decimal -> {
				if (Double.isNaN(d)) {
					throw new ScriptError("cannot convert float NaN to integer");
				}
				if (Double.isInfinite(d)) {
					throw new ScriptError("cannot convert float infinity to integer");
				}
				if (Math.abs(d) >= 0x1p63) {
					throw new ScriptError(Values.OVERFLOW);
				}
				yield (long) (double) d;
			}
			default -> throw new ScriptError("%" + type + " format: " + (decimal ? "a real number" : "an integer")
					+ " is required, not " + Values.typeName(value));
		};
	}

	private static Object positional(List<Object> args, int index) {
		if (index >= args.size()) {
			throw new ScriptError("Replacement index " + index + " out of range for positional args tuple");
		}
		return args.get(index);
	}
}
