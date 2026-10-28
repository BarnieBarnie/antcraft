package io.github.barniebarnie.antcraft.script.lang;

import static io.github.barniebarnie.antcraft.script.lang.Scripts.error;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.eval;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@code str % args}, each checked against what CPython 3.14 gives. */
class PercentFormatTest {
	@Test
	void oneValueOrATupleOfValues() {
		assertEquals("5 logs", run("n = 5\nprint('%d logs' % n)"));
		assertEquals("a at b", eval("'%s at %s' % ('a', 'b')"));
		assertEquals("[1, 2]|None|abc", eval("'%s|%s|abc' % ([1, 2], None)"));
		assertEquals("5 logs", run("s = '%d logs'\ns %= 5\nprint(s)"));
	}

	@Test
	void flagsWidthAndPrecision() {
		assertEquals(" 3.14|3    |-0042|+5| 5", eval("'%5.2f|%-5d|%05d|%+d|% d' % (3.14159, 3, -42, 5, 5)"));
		assertEquals("3    |+0003| 0003| 0xff|0x0ff|0o10  |",
				eval("'%-05d|%+05d|% 05d|%#5x|%#05x|%-#6o|' % (3, 3, 3, 255, 255, 8)"));
		assertEquals("005|    -005|00000005|005     |0", eval("'%.3d|%8.3d|%08.3d|%-8.3d|%.0d' % (5, -5, 5, 5, 0)"));
		assertEquals("ab|   ab|ab   |   ab|    x|", eval("'%.2s|%5s|%-5s|%05s|%5.1s|' % ('abc', 'ab', 'ab', 'ab', 'xyz')"));
		assertEquals("    3|1.50    ", eval("'%*d|%-*.*f' % (5, 3, 8, 2, 1.5)"));
		assertEquals("1 2 3.000000", eval("'%ld %hd %Lf' % (1, 2, 3.0)"));
	}

	@Test
	void conversions() {
		assertEquals("ff FF 0xff 10 0o10 1.234500e+03 1e-05 1E+20 A z 'hi' '\\xe9' %",
				eval("'%x %X %#x %o %#o %e %g %G %c %c %r %a %%' % (255, 255, 255, 8, 8, 1234.5, 0.00001, 1e20, 65, 'z',"
						+ " 'hi', 'é')"));
		assertEquals("3 -3 1 1 1 2.00", eval("'%d %i %u %d %x %.2f' % (3.7, -3.7, True, True, True, 2)"));
		assertEquals(" 1.235e+04|0.5       |+2.2", eval("'%10.3e|%-10g|%+.1f' % (12345.678, 0.5, 2.25)"));
		assertEquals("1. 1.00000 1.e+00", eval("'%#.0f %#g %#.0e' % (1.0, 1.0, 1.0)"));
		assertEquals("inf NAN -0.000000", eval("'%f %F %f' % (1e308 * 10, 1e308 * 10 - 1e308 * 10, -0.0)"));
		assertEquals("  A|b  |True 1.5", eval("'%3c|%-3c|%s %r' % (65, 'b', True, 1.5)"));
	}

	@Test
	void namedFieldsReadADict() {
		assertEquals("1-002 q 'q'", eval("'%(a)s-%(b)03d %(c)s %(c)r' % {'a': 1, 'b': 2, 'c': 'q'}"));
		assertEquals("{'a': 1}", eval("'%s' % {'a': 1}"));
		assertEquals("abc abc", eval("'abc' % {} + ' ' + 'abc' % []"));
	}

	@Test
	void anUnsupportedConversionIsNamed() {
		assertEquals("unsupported format character 'q' (0x71) at index 1", error("'%q' % 1"));
		assertEquals("unsupported format character 'y' (0x79) at index 4", error("'ab %y' % 1"));
		assertEquals("unsupported format character '?' (0xe9) at index 1", error("'%é' % 1"));
	}

	@Test
	void wrongArgumentsAreErrors() {
		assertEquals("not enough arguments for format string", error("'%d %d' % 1"));
		assertEquals("not enough arguments for format string", error("'%s' % ()"));
		assertEquals("not all arguments converted during string formatting", error("'%d' % (1, 2)"));
		assertEquals("not all arguments converted during string formatting", error("'abc' % 1"));
		assertEquals("%d format: a real number is required, not str", error("'%d' % 'a'"));
		assertEquals("%d format: a real number is required, not NoneType", error("'%d' % None"));
		assertEquals("%x format: an integer is required, not float", error("'%x' % 1.5"));
		assertEquals("must be real number, not str", error("'%f' % 'a'"));
		assertEquals("format requires a mapping", error("'%(a)s' % 1"));
		assertEquals("key 'a' is not in the dict", error("'%(a)s' % {}"));
		assertEquals("%c arg not in range(0x110000)", error("'%c' % -1"));
		assertEquals("%c requires an int or a unicode character, not a string of length 2", error("'%c' % 'ab'"));
		assertEquals("%c requires an int or a unicode character, not float", error("'%c' % 1.5"));
		assertEquals("* wants int", error("'%*d' % ('a', 1)"));
		assertEquals("incomplete format", error("'%' % 1"));
		assertEquals("incomplete format", error("'%5' % 1"));
		assertEquals("incomplete format key", error("'%(a' % {}"));
		assertEquals("cannot convert float infinity to integer", error("'%d' % (1e308 * 10)"));
	}

	@Test
	void unaryPlus() {
		assertEquals("(1, -2, 1.5, -3)", eval("(+True, +-2, +1.5, -+3)"));
		assertEquals("bad operand type for unary +: 'str'", error("+'a'"));
	}
}
