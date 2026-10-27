package io.github.barniebarnie.antcraft.script.lang;

import static io.github.barniebarnie.antcraft.script.lang.Scripts.error;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.eval;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.fails;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The builtins of docs/scripting.md, each checked against what CPython 3.14 gives, errors included. */
class BuiltinsTest {
	@Test
	void printedListsDictsAndFloatsMatchPython() {
		assertEquals("[1, 'a'] {'a': [1.5, None]} (1,) 0.1 1e+20 1e+16 1e+16 1.0 100.0 0.3333333333333333 -0.0 2e-05 True None",
				eval("[1, 'a'], {'a': [1.5, None]}, (1,), 0.1, 1e20, 1e+16, 1e16, 1.0, 100.0, 1/3, -0.0, 2e-5, True, None"));
		assertEquals("0.1 1e+20 [0.1, \"x's\"]", eval("str(0.1), str(1e20), str([0.1, \"x's\"])"));
	}

	@Test
	void len() {
		assertEquals("5 2 1 10", eval("len('héllo'), len([1, 2]), len({'a': 1}), len(range(10))"));
		assertEquals("object of type 'int' has no len()", error("len(5)"));
	}

	@Test
	void abs() {
		assertEquals("3 2.5 1", eval("abs(-3), abs(-2.5), abs(True)"));
		assertEquals("bad operand type for abs(): 'str'", error("abs('x')"));
		assertEquals("int overflow: the result does not fit in 64 bits", error("abs(-9223372036854775807 - 1)"));
	}

	@Test
	void round() {
		assertEquals("2 4 0 2.67 1200 1200 2.0 7", eval("round(2.5), round(3.5), round(-0.5), round(2.675, 2), "
				+ "round(1234, -2), round(1250, -2), round(1.5, 0), round(7, 1)"));
		assertEquals("type str doesn't define __round__ method", error("round('x')"));
		assertEquals("cannot convert float infinity to integer", error("round(1e308 * 10)"));
	}

	@Test
	void sum() {
		assertEquals("6 3.5 [1, 2] 0", eval("sum([1, 2, 3]), sum([1, 2.5]), sum([[1], [2]], []), sum([])"));
		assertEquals("sum() can't sum strings [use ''.join(seq) instead]", error("sum(['a', 'b'], '')"));
		assertEquals("'int' object is not iterable", error("sum(5)"));
	}

	@Test
	void minAndMax() {
		assertEquals("1 1 c 7 3", eval("min([3, 1, 2]), min(3, 1, 2), max('abc'), max([], default=7), "
				+ "max([1, 2, 3], key=None)"));
		assertEquals("min() iterable argument is empty", error("min([])"));
		assertEquals("'int' object is not iterable", error("min(1)"));
		assertEquals("min expected at least 1 argument, got 0", error("min()"));
		assertEquals("'<' not supported between instances of 'str' and 'int'", error("min([1, 'a'])"));
		assertEquals("Cannot specify a default for max() with multiple positional arguments",
				error("max(1, 2, default=3)"));
	}

	@Test
	void sorted() {
		assertEquals("[1, 2, 3] [3, 2, 1] ['a', 'b', 'c']", eval("sorted([3, 1, 2]), sorted([3, 1, 2], reverse=True), "
				+ "sorted('bca')"));
		assertEquals("'<' not supported between instances of 'str' and 'int'", error("sorted([1, 'a'])"));
		assertEquals("sorted expected 1 argument, got 0", error("sorted()"));
	}

	@Test
	void keyFunctionsAreScriptFunctionsOrBuiltins() {
		assertEquals("[3, 2, 1] 3 1 bb ['a', 'bb', 'ccc']", run("""
				def neg(x):
				    return -x
				xs = ['bb', 'a', 'ccc']
				xs.sort(key=len)
				print(sorted([1, 3, 2], key=neg), min([1, 3, 2], key=neg), max([1, 3, 2], key=neg), max('a', 'bb', key=len), xs)
				"""));
		assertEquals("[(2, 'a'), (1, 'b'), (1, 'c')] [(1, 'c'), (1, 'b'), (2, 'a')] (1, 'c')", run("""
				def second(p):
				    return p[1]
				ps = [(1, 'c'), (2, 'a'), (1, 'b')]
				print(sorted(ps, key=second), sorted(ps, key=second, reverse=True), max(ps, key=second))
				"""));
	}

	@Test
	void aKeyFunctionMaySuspendTheScript() {
		java.util.List<String> printed = new java.util.ArrayList<>();
		Natives natives = Natives.standard(Output.lines(printed::add));
		natives.register("ask", call -> call.suspend());
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", """
				def weight(x):
				    return ask(x)
				print(sorted(['a', 'b', 'c'], key=weight))
				""")), natives);
		java.util.List<Long> answers = java.util.List.of(3L, 1L, 2L);
		for (long answer : answers) {
			assertEquals(Vm.Status.SUSPENDED, vm.run(1000));
			assertEquals(2, vm.line());
			vm.resume(answer);
		}
		assertEquals(Vm.Status.FINISHED, vm.run(1000));
		assertEquals(java.util.List.of("['b', 'c', 'a']"), printed);
	}

	@Test
	void aScriptPausedInsideABuiltinIsAtTheLineThatCalledIt() {
		Natives natives = Natives.standard(Output.lines(line -> {
		}));
		natives.register("ask", call -> call.suspend());
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "x = 1\nsorted(['a'], key=ask)")), natives);
		assertEquals(Vm.Status.SUSPENDED, vm.run(1000));
		assertEquals(2, vm.line());
	}

	@Test
	void aSortWithAKeyRunsTheSameInSlicesOfOneInstruction() {
		String source = "def neg(x):\n    return -x\nxs = [5, 3, 9, 1]\nxs.sort(key=neg)\nprint(xs, min(xs, key=neg))";
		java.util.List<String> printed = new java.util.ArrayList<>();
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(printed::add)));
		while (vm.run(1) == Vm.Status.RUNNING) {
		}
		assertEquals(Vm.Status.FINISHED, vm.status());
		assertEquals(run(source), String.join("\n", printed));
		assertEquals("[9, 5, 3, 1] 9", run(source));
	}

	@Test
	void aKeyFunctionWithTheWrongParametersIsAnErrorAtTheCall() {
		assertEquals("t.py:3: two() missing 1 required positional argument: 'b'",
				fails("def two(a, b):\n    return a\nsorted([1, 2], key=two)"));
	}

	@Test
	void anErrorInAKeyFunctionNamesItsLine() {
		assertEquals("t.py:2: division by zero", fails("def bad(x):\n    return 1 / 0\nsorted([1, 2], key=bad)"));
		assertEquals("t.py:3: 'int' object is not callable", fails("x = 1\n\nsorted([1, 2], key=5)"));
		assertEquals("t.py:3: '<' not supported between instances of 'str' and 'int'",
				fails("def same(x):\n    return x\nsorted([1, 'a'], key=same)"));
	}

	@Test
	void reversedEnumerateAndZip() {
		assertEquals("[3, 2, 1] ['b', 'a'] [(0, 'a'), (1, 'b')] [(1, 'a'), (2, 'b')] [(1, 'a'), (2, 'b')] []",
				eval("list(reversed([1, 2, 3])), list(reversed('ab')), list(enumerate('ab')), list(enumerate('ab', 1)), "
						+ "list(zip([1, 2, 3], 'ab')), list(zip())"));
		assertEquals("0 a\n1 b", run("for i, c in enumerate('ab'):\n    print(i, c)"));
		assertEquals("'int' object is not reversible", error("reversed(5)"));
		assertEquals("'int' object is not iterable", error("enumerate(5)"));
	}

	@Test
	void str() {
		assertEquals("|1.0|[1, 'a']|None|True", eval("str(), str(1.0), str([1, 'a']), str(None), str(True), sep='|'"));
	}

	@Test
	void intConverts() {
		assertEquals("0 12 -12 1000 2 -2 1 255 31", eval("int(), int('12'), int(' -12 '), int('1_000'), int(2.9), "
				+ "int(-2.9), int(True), int('ff', 16), int('0x1f', 0)"));
		assertEquals("invalid literal for int() with base 10: 'x'", error("int('x')"));
		assertEquals("invalid literal for int() with base 10: '1.5'", error("int('1.5')"));
		assertEquals("int() can't convert non-string with explicit base", error("int(5, 10)"));
		assertEquals("cannot convert float infinity to integer", error("int(1e308 * 10)"));
		assertEquals("cannot convert float NaN to integer", error("int(1e308 * 10 - 1e308 * 10)"));
		assertEquals("int overflow: the result does not fit in 64 bits", error("int(1e30)"));
		assertEquals("int() argument must be a string, a bytes-like object or a real number, not 'NoneType'",
				error("int(None)"));
	}

	@Test
	void floatConverts() {
		assertEquals("0.0 1.5 2.0 inf -inf nan 3.0 10.5", eval("float(), float('1.5'), float(' 2 '), float('inf'), "
				+ "float('-Infinity'), float('nan'), float(3), float('1_0.5')"));
		assertEquals("could not convert string to float: 'x'", error("float('x')"));
		assertEquals("float() argument must be a string or a real number, not 'NoneType'", error("float(None)"));
	}

	@Test
	void boolListDictAndTupleConvert() {
		assertEquals("False False True False", eval("bool(), bool(0), bool([0]), bool('')"));
		assertEquals("[] ['a', 'b'] [1, 2] ['a'] [0, 1, 2] (1,) ()", eval("list(), list('ab'), list((1, 2)), "
				+ "list({'a': 1}), list(range(3)), tuple([1]), tuple()"));
		assertEquals("'int' object is not iterable", error("list(5)"));
		assertEquals("{} {'a': 1, 'b': 2} {'a': 1, 'b': 2} {'a': 1, 'b': 2}", eval("dict(), dict(a=1, b=2), "
				+ "dict([('a', 1), ['b', 2]]), dict({'a': 1}, b=2)"));
		assertEquals("'int' object is not iterable", error("dict([1])"));
		assertEquals("dictionary update sequence element #0 has length 3; 2 is required", error("dict([(1, 2, 3)])"));
	}

	@Test
	void isinstanceKnowsTheBuiltinTypes() {
		assertEquals("True True False True True True True True True False True",
				eval("isinstance(1, int), isinstance(True, int), isinstance(1, float), isinstance(1.0, float), "
						+ "isinstance('a', str), isinstance([], list), isinstance({}, dict), isinstance((), tuple), "
						+ "isinstance(True, bool), isinstance(1, bool), isinstance(1, (str, int))"));
		assertEquals("isinstance() arg 2 must be a type, a tuple of types, or a union", error("isinstance(1, 5)"));
		assertEquals("isinstance() arg 2 must be a type, a tuple of types, or a union", error("isinstance(1, len)"));
	}

	@Test
	void anyAndAll() {
		assertEquals("False True True False", eval("any([]), any([0, '', 3]), all([]), all([1, 0])"));
		assertEquals("'int' object is not iterable", error("all(5)"));
	}

	@Test
	void truthinessIsPythons() {
		assertEquals("False False False False False False False True True True True",
				eval("bool(0), bool(0.0), bool(''), bool([]), bool({}), bool(None), bool(False), bool(' '), bool([0]), "
						+ "bool({0: 0}), bool(-1)"));
		assertEquals("empty", run("if not []:\n    print('empty')"));
	}

	@Test
	void builtinsMayBeShadowed() {
		assertEquals("3", run("def len(x):\n    return 3\nprint(len([]))"));
	}
}
