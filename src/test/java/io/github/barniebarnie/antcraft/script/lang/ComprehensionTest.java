package io.github.barniebarnie.antcraft.script.lang;

import static io.github.barniebarnie.antcraft.script.lang.Scripts.fails;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** List comprehensions, each checked against what CPython 3.14 gives. */
class ComprehensionTest {
	@Test
	void buildsAList() {
		assertEquals("[0, 2, 4] [1, 3] [(1, 'a'), (1, 'b'), (2, 'a'), (2, 'b')] [2, 3] [] [[0], [0, 1]]", run("""
				print([x * 2 for x in range(3)], [x for x in range(5) if x % 2 if x < 4],
				      [(n, c) for n in [1, 2] for c in 'ab'], [b for a, b in [(1, 2), (2, 3)]], [x for x in []],
				      [[y for y in range(x)] for x in range(1, 3)])
				"""));
	}

	@Test
	void laterClausesSeeEarlierVariables() {
		assertEquals("[1, 2, 2]", run("print([y for x in [[1], [2, 2]] for y in x])"));
	}

	@Test
	void theVariableDoesNotLeakAtTheTopLevel() {
		assertEquals("t.py:2: name 'x' is not defined", fails("xs = [x for x in range(3)]\nprint(x)"));
	}

	@Test
	void theVariableDoesNotLeakInAFunction() {
		assertEquals("t.py:3: name 'x' is not defined", fails("def f():\n    xs = [x for x in range(3)]\n    return x\nf()"));
	}

	@Test
	void theVariableDoesNotOverwriteOneOfTheSameName() {
		assertEquals("10 [0, 1] 10", run("x = 10\nprint(x, [x for x in range(2)], x)"));
		assertEquals("10 [0, 1] 10", run("def f():\n    x = 10\n    print(x, [x for x in range(2)], x)\nf()"));
	}

	@Test
	void theBodySeesTheVariablesAroundIt() {
		assertEquals("[3, 4]", run("def f(n):\n    return [x + n for x in range(2)]\nprint(f(3))"));
		assertEquals("[3, 4]", run("n = 3\nprint([x + n for x in range(2)])"));
	}

	@Test
	void aNestedFunctionMayUseItsOwnComprehensionVariable() {
		run("def outer():\n    x = 1\n    def inner():\n        return [x for x in range(2)]\n    return inner()\nouter()");
	}

	@Test
	void aComprehensionInANestedFunctionStillMayNotCaptureAVariable() {
		CompileError error = assertThrows(CompileError.class, () -> Parser.parse("bot.py",
				"def outer(n):\n    def inner():\n        return [x for x in range(n)]\n    return inner()"));
		assertEquals("bot.py:3:34: closures are not supported (`inner` uses `n` of `outer`)", error.getMessage());
	}

	@Test
	void anErrorInsideNamesItsLine() {
		assertEquals("t.py:2: division by zero", fails("xs = [1, 0]\nys = [1 / x for x in xs]"));
	}

	@Test
	void runsInAPausedScriptAsInOne() {
		String source = "print([x * x for x in range(20) if x % 3])";
		String once = run(source);
		java.util.List<String> printed = new java.util.ArrayList<>();
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(printed::add)));
		while (vm.run(1) == Vm.Status.RUNNING) {
		}
		assertEquals(Vm.Status.FINISHED, vm.status());
		assertEquals(once, String.join("\n", printed));
	}
}
