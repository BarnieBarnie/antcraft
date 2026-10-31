package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Each construct outside the subset (docs/language/index.md, "Not supported (v1)") is a compile error that names it and where it is. */
class SubsetTest {
	private static void rejects(String source, String message) {
		CompileError error = assertThrows(CompileError.class, () -> Parser.parse("bot.py", source));
		assertEquals(message, error.getMessage());
	}

	@Test
	void classFeaturesOutsideTheSubsetAreNotSupported() {
		rejects("class A(B, C):\n    pass", "bot.py:1:12: multiple inheritance is not supported");
		rejects("class A(metaclass=M):\n    pass", "bot.py:1:9: metaclasses and class keywords are not supported");
		rejects("class A:\n    @staticmethod\n    def f():\n        pass", "bot.py:2:5: decorators are not supported");
		rejects("class A:\n    @property\n    def f(self):\n        pass", "bot.py:2:5: decorators are not supported");
		rejects("class A:\n    __slots__ = ('x',)", "bot.py:2:5: `__slots__` is not supported");
		rejects("class A:\n    def __add__(self, o):\n        pass",
				"bot.py:2:5: special methods other than `__init__` are not supported (`__add__`)");
		rejects("class A:\n    def __str__(self):\n        pass",
				"bot.py:2:5: special methods other than `__init__` are not supported (`__str__`)");
		rejects("class A:\n    class B:\n        pass",
				"bot.py:2:5: a class body may only hold methods, attribute assignments, `pass` and a docstring");
		rejects("class A:\n    if x:\n        y = 1",
				"bot.py:2:5: a class body may only hold methods, attribute assignments, `pass` and a docstring");
		rejects("class A:\n    x = 1\n    y = x + 1", "bot.py:3:9: a class attribute cannot use another one (`x`)");
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
	void aNestedFunctionMayUseGlobalsAndItsOwnVariables() {
		Parser.parse("bot.py", "n = 1\ndef outer():\n    global n\n    n = 2\n    def inner(m):\n        k = m\n        return k + n\n    return inner(n)");
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
