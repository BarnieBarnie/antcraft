package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runtime errors stop the script and name its file and line. */
class VmErrorTest {
	/** Runs a script that must fail and gives its error message. */
	private static String fails(String source) {
		List<String> printed = new ArrayList<>();
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(printed::add)));
		assertEquals(Vm.Status.FAILED, vm.run(1_000_000));
		assertEquals(Vm.Status.FAILED, vm.run(1_000_000));
		return vm.error().getMessage();
	}

	@Test
	void attributeOfNone() {
		assertEquals("t.py:2: 'NoneType' object has no attribute 'pos'", fails("chest = None\nprint(chest.pos)"));
	}

	@Test
	void indexOutOfRange() {
		assertEquals("t.py:2: list index out of range", fails("xs = [1, 2]\nxs[2]"));
		assertEquals("t.py:1: tuple index out of range", fails("(1,)[-2]"));
		assertEquals("t.py:1: string index out of range", fails("'ab'[5]"));
		assertEquals("t.py:1: list assignment index out of range", fails("xs = []; xs[0] = 1"));
		assertEquals("t.py:1: key 'b' is not in the dict", fails("{'a': 1}['b']"));
	}

	@Test
	void starArgumentThatIsNotIterable() {
		assertEquals("t.py:3: f() argument after * must be an iterable, not int", fails("def f(a):\n    pass\nf(*1)"));
		assertEquals("t.py:1: print() argument after * must be an iterable, not NoneType", fails("print(*None)"));
	}

	@Test
	void doubleStarArgumentErrors() {
		String f = "def f(a=1):\n    pass\n";
		assertEquals("t.py:3: f() argument after ** must be a mapping, not int", fails(f + "f(**1)"));
		assertEquals("t.py:3: keywords must be strings", fails(f + "f(**{1: 2})"));
		assertEquals("t.py:3: f() got multiple values for keyword argument 'a'", fails(f + "f(a=1, **{'a': 2})"));
		assertEquals("t.py:3: f() got multiple values for keyword argument 'a'", fails(f + "f(**{'a': 1}, **{'a': 2})"));
		assertEquals("t.py:3: f() got an unexpected keyword argument 'z'", fails(f + "f(**{'z': 1})"));
		assertEquals("t.py:3: g() got an unexpected keyword argument 'a'", fails("def g(*args):\n    pass\ng(a=1)"));
		assertEquals("t.py:3: g() got multiple values for argument 'a'", fails("def g(a, **kw):\n    pass\ng(1, a=2)"));
		assertEquals("t.py:3: g() missing 1 required positional argument: 'a'", fails("def g(a, *args):\n    pass\ng()"));
	}

	@Test
	void unknownName() {
		assertEquals("t.py:3: name 'find_nearst' is not defined", fails("x = 1\n\ny = find_nearst('chest')"));
	}

	@Test
	void aLocalReadBeforeItIsSet() {
		assertEquals("t.py:3: cannot access local variable 'x' where it is not associated with a value",
				fails("x = 1\ndef f():\n    print(x)\n    x = 2\nf()"));
	}

	@Test
	void intOverflow() {
		assertEquals("t.py:1: int overflow: the result does not fit in 64 bits", fails("x = 9223372036854775807 + 1"));
		assertEquals("t.py:1: int overflow: the result does not fit in 64 bits", fails("x = 2 ** 64"));
		assertEquals("t.py:1: int overflow: the result does not fit in 64 bits", fails("x = 3037000500 * 3037000500"));
		assertEquals("t.py:2: int overflow: the result does not fit in 64 bits",
				fails("x = -9223372036854775807 - 1\ny = -x"));
	}

	@Test
	void divisionByZero() {
		assertEquals("t.py:2: division by zero", fails("x = 0\ny = 1 / x"));
		assertEquals("t.py:1: division by zero", fails("1 // 0"));
		assertEquals("t.py:1: division by zero", fails("1 % 0"));
		assertEquals("t.py:1: division by zero", fails("1.0 // 0"));
		assertEquals("t.py:1: division by zero", fails("1.0 % 0.0"));
	}

	@Test
	void tooDeepRecursion() {
		assertEquals("t.py:2: maximum recursion depth exceeded", fails("def f(n):\n    return f(n + 1)\nf(0)"));
	}

	@Test
	void anErrorInAFunctionNamesTheLineInTheFunction() {
		assertEquals("t.py:3: unsupported operand type(s) for +: 'int' and 'str'",
				fails("def f(a):\n    b = 1\n    return b + a\n\nf('x')"));
	}

	@Test
	void typeErrors() {
		assertEquals("t.py:1: '<' not supported between instances of 'str' and 'int'", fails("'a' < 1"));
		assertEquals("t.py:1: 'int' object is not callable", fails("x = 1; x()"));
		assertEquals("t.py:1: 'int' object is not iterable", fails("for x in 5: pass"));
		assertEquals("t.py:1: list indices must be integers or slices, not str", fails("[1]['a']"));
		assertEquals("t.py:1: cannot use 'list' as a dict key (unhashable type: 'list')", fails("d = {[1]: 2}"));
		assertEquals("t.py:1: 'tuple' object does not support item assignment", fails("t = (1,); t[0] = 2"));
		assertEquals("t.py:1: argument of type 'int' is not a container or iterable", fails("1 in 2"));
		assertEquals("t.py:1: slice step cannot be zero", fails("[1][::0]"));
	}

	@Test
	void unpackingTheWrongNumberOfValues() {
		assertEquals("t.py:1: not enough values to unpack (expected 3, got 2)", fails("a, b, c = 1, 2"));
		assertEquals("t.py:1: too many values to unpack (expected 2, got 3)", fails("a, b = [1, 2, 3]"));
	}

	@Test
	void callsWithTheWrongArguments() {
		String f = "def f(a, b=1):\n    pass\n";
		assertEquals("t.py:3: f() missing 1 required positional argument: 'a'", fails(f + "f()"));
		assertEquals("t.py:3: f() takes from 1 to 2 positional arguments but 3 were given", fails(f + "f(1, 2, 3)"));
		assertEquals("t.py:3: f() got an unexpected keyword argument 'c'", fails(f + "f(1, c=2)"));
		assertEquals("t.py:3: f() got multiple values for argument 'a'", fails(f + "f(1, a=2)"));
		assertEquals("t.py:3: g() takes 0 positional arguments but 1 was given",
				fails("def g():\n    pass\ng(1)"));
	}

	@Test
	void aDictChangedWhileLoopingOverIt() {
		assertEquals("t.py:2: dictionary changed size during iteration",
				fails("d = {'a': 1}\nfor k in d:\n    d['b'] = 2"));
	}

	@Test
	void failedAssertNamesTheLineAndTheMessage() {
		assertEquals("t.py:2: assertion failed", fails("x = 0\nassert x"));
		assertEquals("t.py:1: assertion failed: no chest near", fails("assert None, 'no chest near'"));
	}

	@Test
	void aNativeErrorNamesTheLineOfTheCall() {
		Natives natives = Natives.standard((level, line) -> {
		});
		natives.register("boom", call -> {
			throw new ScriptError("no tree near");
		});
		Vm vm = new Vm(Compiler.compile(Parser.parse("bot.py", "x = 1\nboom()")), natives);
		assertEquals(Vm.Status.FAILED, vm.run(100));
		assertEquals("bot.py:2: no tree near", vm.error().getMessage());
		assertEquals("bot.py", vm.error().file());
		assertEquals(2, vm.error().line());
		assertEquals("no tree near", vm.error().detail());
	}

	@Test
	void resumingAScriptThatIsNotSuspendedIsAMistakeOfTheHost() {
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "pass")), Natives.standard((level, line) -> {
		}));
		assertThrows(IllegalStateException.class, () -> vm.resume(1L));
	}
}
