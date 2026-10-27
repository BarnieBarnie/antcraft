package io.github.barniebarnie.antcraft.script.lang;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Python's text formatting: {@code format(value, spec)} with the format specification mini-language, f-string fields
 * with their conversions, and {@code str.format}. Numbers are rounded from their exact binary value, half to even, as
 * CPython does.
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

	/** {@code template.format(*args, **kwargs)}: fields {@code {}}, {@code {0}} or {@code {name}}, each with an optional conversion and spec. */
	static String strFormat(String template, List<Object> args, Map<String, Object> kwargs) {
		StringBuilder out = new StringBuilder();
		int next = 0;
		Boolean manual = null;
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
			while (end < n && template.charAt(end) != '}') {
				if (template.charAt(end) == '{') {
					throw new ScriptError("nested replacement fields in format specs are not supported");
				}
				end++;
			}
			if (end >= n) {
				throw new ScriptError(i + 1 == n ? "Single '{' encountered in format string"
						: "expected '}' before end of string");
			}
			String field = template.substring(i + 1, end);
			i = end + 1;
			int colon = field.indexOf(':');
			String spec = colon < 0 ? "" : field.substring(colon + 1);
			String head = colon < 0 ? field : field.substring(0, colon);
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
			Object value;
			if (head.isEmpty()) {
				if (Boolean.TRUE.equals(manual)) {
					throw new ScriptError("cannot switch from manual field specification to automatic field numbering");
				}
				manual = false;
				value = positional(args, next++);
			} else if (head.chars().allMatch(Character::isDigit)) {
				if (Boolean.FALSE.equals(manual)) {
					throw new ScriptError("cannot switch from automatic field numbering to manual field specification");
				}
				manual = true;
				value = positional(args, head.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(head));
			} else if (Tokenizer.isIdentifier(head)) {
				value = kwargs.get(head);
				if (value == null) {
					throw new ScriptError("key '" + head + "' is not in the keyword arguments");
				}
			} else {
				throw new ScriptError("format field '" + head + "' is not supported (only {}, {0} and {name})");
			}
			out.append(field(value, conversion, spec));
		}
		return out.toString();
	}

	private static Object positional(List<Object> args, int index) {
		if (index >= args.size()) {
			throw new ScriptError("Replacement index " + index + " out of range for positional args tuple");
		}
		return args.get(index);
	}
}
