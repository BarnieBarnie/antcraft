package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Each construct outside the subset (docs/scripting.md, "Not supported (v1)") is a compile error that names it and where it is. */
class SubsetTest {
	private static void rejects(String source, String message) {
		CompileError error = assertThrows(CompileError.class, () -> Parser.parse("bot.py", source));
		assertEquals(message, error.getMessage());
	}

	@Test
	void classIsNotSupported() {
		rejects("x = 1\nclass Ant:\n    pass", "bot.py:2:1: `class` is not supported");
	}

	@Test
	void tryIsNotSupported() {
		rejects("if x:\n    try:\n        y()\n    except:\n        pass", "bot.py:2:5: `try` is not supported");
	}

	@Test
	void exceptIsNotSupported() {
		rejects("except ValueError:\n    pass", "bot.py:1:1: `except` is not supported");
	}

	@Test
	void raiseIsNotSupported() {
		rejects("raise ValueError('x')", "bot.py:1:1: `raise` is not supported");
	}

	@Test
	void lambdaIsNotSupported() {
		rejects("f = lambda x: x", "bot.py:1:5: `lambda` is not supported");
		rejects("sorted(xs, key=lambda x: x)", "bot.py:1:16: `lambda` is not supported");
	}

	@Test
	void withIsNotSupported() {
		rejects("with open('f') as f:\n    pass", "bot.py:1:1: `with` is not supported");
	}

	@Test
	void yieldIsNotSupported() {
		rejects("def g():\n    yield 1", "bot.py:2:5: `yield` is not supported");
		rejects("def g():\n    x = yield", "bot.py:2:9: `yield` is not supported");
	}

	@Test
	void generatorExpressionsAreNotSupported() {
		rejects("s = sum(x for x in xs)", "bot.py:1:11: generator expressions are not supported");
		rejects("g = (x for x in xs)", "bot.py:1:8: generator expressions are not supported");
	}

	@Test
	void decoratorsAreNotSupported() {
		rejects("@cache\ndef f():\n    pass", "bot.py:1:1: decorators are not supported");
	}

	@Test
	void asyncIsNotSupported() {
		rejects("async def f():\n    pass", "bot.py:1:1: `async` is not supported");
	}

	@Test
	void awaitIsNotSupported() {
		rejects("def f():\n    await g()", "bot.py:2:5: `await` is not supported");
	}

	@Test
	void setLiteralsAreNotSupported() {
		rejects("s = {1, 2}", "bot.py:1:5: set literals are not supported");
		rejects("s = {x for x in xs}", "bot.py:1:5: set comprehensions are not supported");
	}

	@Test
	void setIsNotSupported() {
		rejects("s = set(xs)", "bot.py:1:5: `set` is not supported");
	}

	@Test
	void starArgsAreNotSupported() {
		rejects("def f(*args):\n    pass", "bot.py:1:7: `*args` is not supported");
		rejects("f(*xs)", "bot.py:1:3: `*args` is not supported");
	}

	@Test
	void doubleStarKwargsAreNotSupported() {
		rejects("def f(**kwargs):\n    pass", "bot.py:1:7: `**kwargs` is not supported");
		rejects("f(**d)", "bot.py:1:3: `**kwargs` is not supported");
	}

	@Test
	void closuresAreNotSupported() {
		rejects("def outer():\n    n = 0\n    def inner():\n        return n + 1\n    return inner()",
				"bot.py:4:16: closures are not supported (`inner` uses `n` of `outer`)");
		rejects("def outer(n):\n    if n:\n        def inner():\n            return [x * n for x in xs]\n",
				"bot.py:4:25: closures are not supported (`inner` uses `n` of `outer`)");
	}

	@Test
	void aNestedFunctionMayUseGlobalsAndItsOwnVariables() {
		Parser.parse("bot.py", "n = 1\ndef outer():\n    global n\n    n = 2\n    def inner(m):\n        k = m\n        return k + n\n    return inner(n)");
	}

	@Test
	void nonlocalIsNotSupported() {
		rejects("def f():\n    nonlocal x", "bot.py:2:5: `nonlocal` is not supported");
	}

	@Test
	void isIsOnlySupportedForNone() {
		rejects("if a is b:\n    pass", "bot.py:1:6: `is` is not supported except with `None`");
	}

	@Test
	void otherOperatorsOutsideTheSubsetAreNotSupported() {
		rejects("if a & b:\n    pass", "bot.py:1:6: `&` is not supported");
		rejects("x = a | b", "bot.py:1:7: `|` is not supported");
		rejects("x = ~a", "bot.py:1:5: `~` is not supported");
		rejects("x = a << 2", "bot.py:1:7: `<<` is not supported");
		rejects("x = a @ b", "bot.py:1:7: `@` is not supported");
		rejects("if (n := 3):\n    pass", "bot.py:1:7: `:=` is not supported");
		rejects("x |= 1", "bot.py:1:3: `|=` is not supported");
	}

	@Test
	void otherStatementsOutsideTheSubsetAreNotSupported() {
		rejects("del x", "bot.py:1:1: `del` is not supported");
		rejects("import lib.trees as t", "bot.py:1:18: `import ... as` is not supported");
		rejects("from m import *", "bot.py:1:15: `from ... import *` is not supported");
		rejects("for x in xs:\n    pass\nelse:\n    pass", "bot.py:3:1: `else` on a loop is not supported");
		rejects("x: int = 1", "bot.py:1:2: annotations are not supported");
		rejects("d = {k: v for k, v in xs}", "bot.py:1:5: dict comprehensions are not supported");
		rejects("a, *b = xs", "bot.py:1:4: `*` unpacking is not supported");
		rejects("x = b'abc'", "bot.py:1:5: bytes literals are not supported");
		rejects("x = 1j", "bot.py:1:5: complex numbers are not supported");
		rejects("x = ...", "bot.py:1:5: `...` is not supported");
	}
}
