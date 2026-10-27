package io.github.barniebarnie.antcraft.script.lang;

import static io.github.barniebarnie.antcraft.script.lang.Scripts.error;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.eval;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.fails;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** f-strings and their format specs, each checked against what CPython 3.14 gives. */
class FStringTest {
	/** What the f-string {@code body} (without its quotes) gives. */
	private static String f(String body) {
		return run("print(f'" + body + "')");
	}

	@Test
	void fieldsAreFormattedWithStr() {
		assertEquals("no pickaxe: no_item!", run("reason = 'no_item'\nprint(f'no pickaxe: {reason}!')"));
		assertEquals("[1, 'a'] 0.1 1e+20 True None {}", f("{[1, \"a\"]} {0.1} {1e20} {True} {None} {{}}"));
		assertEquals("3", run("def f(x):\n    return f'{x + 1}'\nprint(f(2))"));
	}

	@Test
	void conversions() {
		assertEquals("'ab' 1.5 '\\xe9'", f("{\"ab\"!r} {1.5!s} {\"é\"!a}"));
		assertEquals("  'ab'|", f("{\"ab\"!r:>6}|"));
	}

	@Test
	void fixedPointAndPrecision() {
		assertEquals("3.14 2 -1.00 100000000000000000000.00 12.000000 12.0 1.2 0.12 -0.0 inf",
				f("{3.14159:.2f} {2.5:.0f} {-1.005:.2f} {1e20:.2f} {12:f} {12:.1f} {1.25:.1f} {0.125:.2f} {-0.0:.1f} "
						+ "{1e308 * 10:.2f}"));
		assertEquals("0001.500|  3.0|", f("{1.5:08.3f}|{3:5.1f}|"));
	}

	@Test
	void alignmentFillAndWidth() {
		assertEquals("   12|   ab|ab   |12   | ab  |**ab**|ab   |-   12|", f("{12:>5}|{\"ab\":>5}|{\"ab\":<5}|{12:<5}|"
				+ "{\"ab\":^5}|{\"ab\":*^6}|{\"ab\":5}|{-12:=6}|"));
		assertEquals("000012 -00012", f("{12:06} {-12:06}"));
		assertEquals("    1234.5|  nan|", f("{1234.5:10}|{1e308 * 10 - 1e308 * 10:5}|"));
	}

	@Test
	void signsAndGrouping() {
		assertEquals("+12| 12|-1.5|1,234,567|1_234_567|1,234,567.89|12",
				f("{12:+}|{12: }|{-1.5:+.1f}|{1234567:,}|{1234567:_}|{1234567.891:,.2f}|{12:,d}"));
	}

	@Test
	void integerPresentations() {
		assertEquals("ff FF 0xff 101 10 A 12 0o14 0b1100 -c", f("{255:x} {255:X} {255:#x} {5:b} {8:o} {65:c} {12:d} "
				+ "{12:#o} {12:#b} {-12:x}"));
	}

	@Test
	void exponentGeneralAndPercent() {
		assertEquals("1.200000e+01 1.234500e+03 1.23e+03 1.234500E+03 50.000000% 12.3%",
				f("{12:e} {1234.5:e} {1234.5:.2e} {1234.5:E} {0.5:%} {0.1234:.1%}"));
		assertEquals("0.0001234 1.23457e+06 123456 1e-05 1 1e+02 1.5 1.0 1.2e+03",
				f("{0.0001234:g} {1234567.0:g} {123456.0:g} {1e-5:g} {1.0:g} {100.0:.2g} {1.5:.3} {1.0:.3} {1234.5:.2}"));
	}

	@Test
	void aSpecWithoutATypeAddsPointZeroAndSwitchesToExponentsEarlier() {
		assertEquals("1e+00|5e+00|1.2e+01|12.0|0.5|1e+02|0e+00|0.0|1e+16|1e+02",
				f("{1.0:.1}|{5.0:.1}|{12.0:.2}|{12.0:.3}|{0.5:.1}|{99.9:.2}|{0.0:.1}|{0.0:.3}|{1e16:.3}|{100.0:.3}"));
	}

	@Test
	void zeroPaddingIsGroupedAndZCoercesNegativeZero() {
		assertEquals("0,001,234 0,001,234.5 0.0    10.000%| 0b101  |",
				f("{1234:08,} {1234.5:010,.1f} {-0.0:z.1f} {0.1:10.3%}|{5:^#8b}|"));
	}

	@Test
	void boolWithASpecIsFormattedAsAnInt() {
		assertEquals("True     1 1", f("{True} {True:>5} {True:d}"));
	}

	@Test
	void badSpecsAreErrors() {
		assertEquals("unsupported format string passed to NoneType.__format__", error("f'{None:>5}'"));
		assertEquals("unsupported format string passed to list.__format__", error("f'{[1]:>5}'"));
		assertEquals("Unknown format code 'd' for object of type 'str'", error("f'{\"ab\":d}'"));
		assertEquals("Unknown format code 'd' for object of type 'float'", error("f'{1.5:d}'"));
		assertEquals("Unknown format code 's' for object of type 'int'", error("f'{12:s}'"));
		assertEquals("Precision not allowed in integer format specifier", error("f'{12:.2}'"));
		assertEquals("'=' alignment not allowed in string format specifier", error("f'{\"a\":=5}'"));
		assertEquals("Sign not allowed in string format specifier", error("f'{\"a\":+}'"));
	}

	@Test
	void anErrorInAFieldNamesItsLine() {
		assertEquals("t.py:2: name 'y' is not defined", fails("x = 1\nprint(f'{x} {y}')"));
	}

	@Test
	void stringPrecisionTruncates() {
		assertEquals("a", eval("f'{\"ab\":.1}'"));
	}
}
