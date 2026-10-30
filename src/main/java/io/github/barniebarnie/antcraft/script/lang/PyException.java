package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An exception as a script sees it (docs/language/errors.md, Catching errors): what {@code except ... as e} binds and
 * {@code raise} raises. Its type is one of the builtin exception types ({@link #PARENTS}), named as natives are;
 * {@code str(e)} is its message. {@code detail} is the text the bot log shows when nothing catches it, and {@code file}
 * and {@code line} where it was first raised, or null and 0 while it has not been.
 */
public record PyException(String type, String message, String detail, @Nullable String file, int line) {
	/**
	 * The builtin exception types, each with the type it derives from: the errors the VM raises, and {@code Exception},
	 * which every one derives from.
	 */
	static final Map<String, String> PARENTS = Map.ofEntries(Map.entry("AssertionError", "Exception"),
			Map.entry("AttributeError", "Exception"), Map.entry("ImportError", "Exception"),
			Map.entry("IndexError", "Exception"), Map.entry("KeyError", "Exception"), Map.entry("NameError", "Exception"),
			Map.entry("OverflowError", "Exception"), Map.entry("RecursionError", "RuntimeError"),
			Map.entry("RuntimeError", "Exception"), Map.entry("SyntaxError", "Exception"), Map.entry("TypeError", "Exception"),
			Map.entry("ValueError", "Exception"), Map.entry("ZeroDivisionError", "Exception"));

	static boolean isType(String name) {
		return name.equals("Exception") || PARENTS.containsKey(name);
	}

	/** A new exception of {@code type} with {@code message}, as {@code ValueError('bad')} makes, not raised yet. */
	static PyException of(String type, String message) {
		return new PyException(type, message, message.isEmpty() ? type : type + ": " + message, null, 0);
	}

	/** Whether it is of type {@code type} or of one derived from it, as {@code except type} tests. */
	boolean isA(String type) {
		for (String t = this.type; t != null; t = PARENTS.get(t)) {
			if (t.equals(type)) {
				return true;
			}
		}
		return false;
	}

	/** {@code repr(e)}: {@code ValueError('bad')}. */
	String repr() {
		return type + "(" + (message.isEmpty() ? "" : Values.repr(message)) + ")";
	}

	/** Calls the exception type {@code type}: its message is the argument, or the arguments as a tuple. */
	static PyException call(String type, List<Object> args, Map<String, Object> kwargs) {
		Args.noKeywords(type, kwargs);
		return of(type, args.isEmpty() ? "" : Values.str(args.size() == 1 ? args.getFirst() : new PyTuple(args)));
	}

	/**
	 * The type of an error the VM or a native raised, from its message, which follows CPython's.
	 *
	 * <p>ponytail: classified by message in this one place rather than at each of the ~190 places that raise an error;
	 * give {@link ScriptError} an explicit type where a message is misread.
	 */
	static String classify(String detail) {
		if (detail.equals("division by zero") || detail.equals("zero to a negative power")) {
			return "ZeroDivisionError";
		}
		if (detail.contains("index out of range") || detail.equals("pop from empty list")
				|| detail.startsWith("Replacement index")) {
			return "IndexError";
		}
		if (detail.startsWith("key ") && detail.contains(" is not in the ")) {
			return "KeyError";
		}
		if (detail.matches("name '.*' is not defined") || detail.startsWith("cannot access local variable")) {
			return "NameError";
		}
		if (detail.contains("has no attribute")) {
			return "AttributeError";
		}
		if (detail.equals("maximum recursion depth exceeded")) {
			return "RecursionError";
		}
		if (detail.startsWith("assertion failed")) {
			return "AssertionError";
		}
		if (detail.startsWith("No module named") || detail.startsWith("cannot import name")
				|| detail.startsWith("circular import")) {
			return "ImportError";
		}
		if (detail.equals(Values.OVERFLOW) || detail.equals("cannot convert float infinity to integer")
				|| detail.startsWith("result is too large")) {
			return "OverflowError";
		}
		if (detail.equals("dictionary changed size during iteration") || detail.contains("() needs a bot")
				|| detail.contains("() needs the bot")) {
			return "RuntimeError";
		}
		for (String value : VALUE_ERRORS) {
			if (detail.contains(value)) {
				return "ValueError";
			}
		}
		if (detail.matches("'.*' is not .+: .+")) { // a selector that does not parse
			return "ValueError";
		}
		return "TypeError";
	}

	/** Parts of the messages of errors that are a ValueError; every other error not named above is a TypeError. */
	private static final List<String> VALUE_ERRORS = List.of("invalid literal", "could not convert",
			"cannot convert float NaN", " must be at least ", " must be from ", " must not be", "must be top or bottom",
			"must be north", "empty separator", "not in list", "is empty", "slice step cannot be zero", "values to unpack",
			"attempt to assign sequence", "negative number to a fractional power", "base must be >= 2", "not in range(0x110000)",
			"width too big", "Max string recursion exceeded", "selects nothing", "unknown ", "needs one item",
			"contains itself", "Invalid format specifier", "Unknown format code", "not allowed", "missing precision",
			"Too many decimal digits", "Cannot specify '", "Single '", "unmatched '{'", "expected '}'", "unexpected '{'",
			"while looking for conversion", "expected ':' after conversion", "Unknown conversion specifier", "cannot switch from",
			"unsupported format character", "incomplete format", "format field '");
}
