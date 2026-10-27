package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Statements and operators, each checked against what CPython 3 prints for the same script.
 */
class VmTest {
	/** Runs a script to its end and gives what it printed, one line per {@code print}. */
	static String run(String source) {
		List<String> printed = new ArrayList<>();
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(printed::add)));
		Vm.Status status = vm.run(1_000_000);
		if (status == Vm.Status.FAILED) {
			throw vm.error();
		}
		assertEquals(Vm.Status.FINISHED, status);
		return String.join("\n", printed);
	}

	/** What {@code print(expression)} shows. */
	private static String eval(String expression) {
		return run("print(" + expression + ")");
	}

	@Test
	void integerArithmetic() {
		assertEquals("7", eval("3 + 4"));
		assertEquals("-1", eval("3 - 4"));
		assertEquals("12", eval("3 * 4"));
		assertEquals("0.75", eval("3 / 4"));
		assertEquals("2.0", eval("4 / 2"));
		assertEquals("-4", eval("7 // -2"));
		assertEquals("3", eval("7 // 2"));
		assertEquals("-1", eval("7 % -2"));
		assertEquals("1", eval("-7 % 2"));
		assertEquals("1024", eval("2 ** 10"));
		assertEquals("0.25", eval("2 ** -2"));
		assertEquals("-8", eval("-2 ** 3"));
		assertEquals("512", eval("2 ** 3 ** 2"));
		assertEquals("14", eval("2 + 3 * 4"));
		assertEquals("-5", eval("-(2 + 3)"));
		assertEquals("5", eval("+5"));
		assertEquals("2", eval("True + True"));
		assertEquals("-1", eval("-True"));
		assertEquals("9223372036854775807", eval("9223372036854775806 + 1"));
	}

	@Test
	void floatArithmetic() {
		assertEquals("3.5", eval("1.5 + 2"));
		assertEquals("0.30000000000000004", eval("0.1 + 0.2"));
		assertEquals("-4.0", eval("7.0 // -2"));
		assertEquals("-1.0", eval("7.0 % -2"));
		assertEquals("1.0", eval("-7 % 2.0"));
		assertEquals("2.25", eval("1.5 ** 2"));
		assertEquals("1e+16", eval("1e16"));
		assertEquals("1000000000000000.0", eval("1e15"));
		assertEquals("1e-05", eval("0.00001"));
		assertEquals("0.0001", eval("0.0001"));
		assertEquals("1.5e+300", eval("1.5e300"));
		assertEquals("-0.0", eval("-0.0"));
		assertEquals("inf", eval("1e308 * 10"));
	}

	@Test
	void stringsListsAndTuplesConcatenateAndRepeat() {
		assertEquals("abcd", eval("'ab' + 'cd'"));
		assertEquals("ababab", eval("'ab' * 3"));
		assertEquals("", eval("'ab' * -1"));
		assertEquals("[1, 2, 3]", eval("[1] + [2, 3]"));
		assertEquals("[0, 0, 0]", eval("[0] * 3"));
		assertEquals("(1, 2, 1, 2)", eval("(1, 2) * 2"));
		assertEquals("(1,)", eval("(1,)"));
		assertEquals("()", eval("()"));
		assertEquals("['a', \"it's\", 'x\\ny']", eval("['a', \"it's\", 'x\\ny']"));
		assertEquals("{'a': 1, 2: [None, True]}", eval("{'a': 1, 2: [None, True]}"));
	}

	@Test
	void comparisons() {
		assertEquals("True", eval("1 < 2"));
		assertEquals("True", eval("1 == 1.0"));
		assertEquals("True", eval("True == 1"));
		assertEquals("False", eval("1 != 1"));
		assertEquals("True", eval("'abc' < 'abd'"));
		assertEquals("True", eval("[1, 2] < [1, 3]"));
		assertEquals("True", eval("(1, 2) < (1, 2, 0)"));
		assertEquals("True", eval("[1, [2]] == [1, [2]]"));
		assertEquals("False", eval("[1] == (1,)"));
		assertEquals("True", eval("{'a': 1} == {'a': 1.0}"));
		assertEquals("True", eval("2 >= 2 > 1"));
		assertEquals("True", eval("1 < 2 < 3"));
		assertEquals("False", eval("1 < 3 < 2"));
		assertEquals("False", eval("3 < 1 < 1 / 0"));
		assertEquals("True", eval("None == None"));
		assertEquals("False", eval("None == 0"));
	}

	@Test
	void aChainedComparisonEvaluatesEachOperandOnce() {
		assertEquals("f\nTrue", run("def f():\n    print('f')\n    return 2\nprint(1 < f() < 3)"));
	}

	@Test
	void membershipAndIdentity() {
		assertEquals("True", eval("2 in [1, 2]"));
		assertEquals("True", eval("3 not in (1, 2)"));
		assertEquals("True", eval("'ell' in 'hello'"));
		assertEquals("True", eval("'a' in {'a': 1}"));
		assertEquals("False", eval("1 in {'a': 1}"));
		assertEquals("True", eval("1.0 in {1: 'x'}"));
		assertEquals("True", eval("4 in range(0, 10, 2)"));
		assertEquals("False", eval("5 in range(0, 10, 2)"));
		assertEquals("True", eval("None is None"));
		assertEquals("False", eval("1 is None"));
		assertEquals("True", eval("[] is not None"));
	}

	@Test
	void booleanOperatorsShortCircuitAndGiveAnOperand() {
		assertEquals("0", eval("0 and 1 / 0"));
		assertEquals("2", eval("1 and 2"));
		assertEquals("x", eval("'' or 'x'"));
		assertEquals("1", eval("1 or 1 / 0"));
		assertEquals("[]", eval("None or []"));
		assertEquals("True", eval("not []"));
		assertEquals("False", eval("not 'a'"));
		assertEquals("True", eval("not 0.0"));
		assertEquals("False", eval("not {'a': 0}"));
	}

	@Test
	void conditionalExpressions() {
		assertEquals("yes", eval("'yes' if [0] else 1 / 0"));
		assertEquals("no", eval("1 / 0 if None else 'no'"));
	}

	@Test
	void indexing() {
		assertEquals("3", eval("[1, 2, 3][-1]"));
		assertEquals("e", eval("'hello'[1]"));
		assertEquals("2", eval("(1, 2)[True]"));
		assertEquals("b", eval("{'a': 'b'}['a']"));
		assertEquals("one", eval("{1: 'one'}[True]"));
	}

	@Test
	void slicing() {
		assertEquals("[2, 3]", eval("[1, 2, 3, 4][1:3]"));
		assertEquals("[4, 3, 2, 1]", eval("[1, 2, 3, 4][::-1]"));
		assertEquals("[1, 3]", eval("[1, 2, 3, 4][::2]"));
		assertEquals("[]", eval("[1, 2, 3, 4][3:1]"));
		assertEquals("[3, 4]", eval("[1, 2, 3, 4][-2:]"));
		assertEquals("[1, 2, 3, 4]", eval("[1, 2, 3, 4][-100:100]"));
		assertEquals("[4, 2]", eval("[1, 2, 3, 4][:0:-2]"));
		assertEquals("ell", eval("'hello'[1:4]"));
		assertEquals("olleh", eval("'hello'[::-1]"));
		assertEquals("(2,)", eval("(1, 2)[1:]"));
	}

	@Test
	void assignment() {
		assertEquals("1 1", run("a = b = 1\nprint(a, b)"));
		assertEquals("2 1", run("a, b = 1, 2\na, b = b, a\nprint(a, b)"));
		assertEquals("1 2 3", run("(a, [b, c]) = [1, (2, 3)]\nprint(a, b, c)"));
		assertEquals("x y", run("a, b = 'xy'\nprint(a, b)"));
		assertEquals("[1, 9, 3]", run("xs = [1, 2, 3]\nxs[1] = 9\nprint(xs)"));
		assertEquals("[1, 9]", run("xs = [1, 2, 3]\nxs[-2:] = [9]\nprint(xs)"));
		assertEquals("[0, 1, 0, 3]", run("xs = [0, 0, 0, 0]\nxs[1::2] = [1, 3]\nprint(xs)"));
		assertEquals("{'a': 2, 'b': 3}", run("d = {'a': 1}\nd['a'] = 2\nd['b'] = 3\nprint(d)"));
		assertEquals("{1: 'b'}", run("d = {1: 'a'}\nd[True] = 'b'\nprint(d)"));
	}

	@Test
	void augmentedAssignment() {
		assertEquals("8", run("x = 5\nx += 3\nprint(x)"));
		assertEquals("2.5", run("x = 5\nx /= 2\nprint(x)"));
		assertEquals("1", run("x = 7\nx %= 3\nprint(x)"));
		assertEquals("[1, 2, 3] [1, 2, 3]", run("a = [1]\nb = a\na += [2, 3]\nprint(a, b)"));
		assertEquals("(1, 2) (1,)", run("a = (1,)\nb = a\na += (2,)\nprint(a, b)"));
		assertEquals("[1, 12, 3]", run("xs = [1, 2, 3]\nxs[1] *= 6\nprint(xs)"));
		assertEquals("{'n': 2}", run("d = {'n': 1}\nd['n'] += 1\nprint(d)"));
		assertEquals("i\n[0, 1]",
				run("xs = [0, 0]\ndef i():\n    print('i')\n    return 1\nxs[i()] += 1\nprint(xs)"));
	}

	@Test
	void ifElifElse() {
		String source = "def sign(n):\n    if n < 0:\n        return 'neg'\n    elif n == 0:\n        return 'zero'\n"
				+ "    else:\n        return 'pos'\nprint(sign(-3), sign(0), sign(5))";
		assertEquals("neg zero pos", run(source));
		assertEquals("", run("if []:\n    print('no')"));
	}

	@Test
	void whileLoops() {
		assertEquals("0\n1\n2", run("i = 0\nwhile i < 3:\n    print(i)\n    i += 1"));
		assertEquals("1\n3", run("i = 0\nwhile True:\n    i += 1\n    if i == 2:\n        continue\n"
				+ "    if i > 3:\n        break\n    print(i)"));
	}

	@Test
	void forLoopsOverEveryIterable() {
		assertEquals("1 2", run("out = []\nfor x in [1, 2]:\n    out = out + [x]\nprint(out[0], out[1])"));
		assertEquals("a\nb", run("for x in ('a', 'b'):\n    print(x)"));
		assertEquals("h\ni", run("for c in 'hi':\n    print(c)"));
		assertEquals("a 1\nb 2", run("d = {'a': 1, 'b': 2}\nfor k in d:\n    print(k, d[k])"));
		assertEquals("0\n1\n2", run("for i in range(3):\n    print(i)"));
		assertEquals("5\n3", run("for i in range(5, 1, -2):\n    print(i)"));
		assertEquals("2\n3", run("for i in range(2, 4):\n    print(i)"));
		assertEquals("1 2\n3 4", run("for a, b in [(1, 2), (3, 4)]:\n    print(a, b)"));
		assertEquals("2", run("for x in []:\n    print(x)\nx = 2\nprint(x)"));
	}

	@Test
	void breakAndContinueInNestedLoops() {
		String source = "for i in range(3):\n    for j in range(3):\n        if j == 1:\n            continue\n"
				+ "        if j == 2:\n            break\n        print(i, j)\n    if i == 1:\n        break\nprint('end')";
		assertEquals("0 0\n1 0\nend", run(source));
		assertEquals("1\nend", run("while True:\n    for x in [1, 2]:\n        print(x)\n        break\n    break\nprint('end')"));
	}

	@Test
	void aListChangedWhileLoopingOverItIsReadAsItIsNow() {
		assertEquals("1\n2\n3", run("xs = [1, 2]\nfor x in xs:\n    if x == 1:\n        xs += [3]\n    print(x)"));
	}

	@Test
	void passDoesNothing() {
		assertEquals("ok", run("pass\nif True:\n    pass\nprint('ok')"));
	}

	@Test
	void functionsWithDefaultAndKeywordArguments() {
		String source = "def f(a, b=2, c=3):\n    return [a, b, c]\nprint(f(1))\nprint(f(1, 5))\nprint(f(1, c=9))\n"
				+ "print(f(c=0, a=7))";
		assertEquals("[1, 2, 3]\n[1, 5, 3]\n[1, 2, 9]\n[7, 2, 0]", run(source));
		assertEquals("None", run("def f():\n    pass\nprint(f())"));
		assertEquals("None", run("def f():\n    return\nprint(f())"));
		assertEquals("(1, 2)", run("def f():\n    return 1, 2\nprint(f())"));
	}

	@Test
	void defaultsAreEvaluatedOnceWhenTheFunctionIsDefined() {
		assertEquals("[1]\n[1, 1]", run("def f(xs=[]):\n    xs += [1]\n    return xs\nprint(f())\nprint(f())"));
		assertEquals("1", run("n = 1\ndef f(x=n):\n    return x\nn = 2\nprint(f())"));
	}

	@Test
	void recursion() {
		assertEquals("120", run("def fact(n):\n    return 1 if n <= 1 else n * fact(n - 1)\nprint(fact(5))"));
		assertEquals("55", run("def fib(n):\n    if n < 2:\n        return n\n    return fib(n - 1) + fib(n - 2)\nprint(fib(10))"));
	}

	@Test
	void localsGlobalsAndGlobalStatement() {
		assertEquals("1 2", run("x = 1\ndef f():\n    x = 2\n    return x\nprint(x, f())"));
		assertEquals("2", run("x = 1\ndef f():\n    global x\n    x = 2\nf()\nprint(x)"));
		assertEquals("5", run("x = 5\ndef f():\n    return x\nprint(f())"));
		assertEquals("3", run("def f():\n    global y\n    y = 3\nf()\nprint(y)"));
		assertEquals("1", run("def f():\n    def g():\n        return 1\n    return g()\nprint(f())"));
	}

	@Test
	void assertPassesWhenTrue() {
		assertEquals("ok", run("assert 1 < 2\nassert [0], 'never'\nprint('ok')"));
	}

	@Test
	void printSeparatesWithSpacesOrSep() {
		assertEquals("1 a None [1.5]", run("print(1, 'a', None, [1.5])"));
		assertEquals("1-2", run("print(1, 2, sep='-')"));
		assertEquals("", run("print()"));
	}
}
