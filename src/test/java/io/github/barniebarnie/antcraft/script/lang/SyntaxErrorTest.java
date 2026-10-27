package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Syntax errors name the file, line and column: {@code file:line:col: message}. */
class SyntaxErrorTest {
	private static CompileError error(String source) {
		return assertThrows(CompileError.class, () -> Parser.parse("miner.py", source));
	}

	private static void rejects(String source, String message) {
		assertEquals(message, error(source).getMessage());
	}

	@Test
	void theErrorKnowsItsParts() {
		CompileError error = error("x = 1\nif x\n    pass");
		assertEquals("miner.py", error.file());
		assertEquals(2, error.line());
		assertEquals(5, error.column());
		assertEquals("expected ':' after 'if' condition", error.detail());
	}

	@Test
	void missingColon() {
		rejects("x = 1\nif x\n    pass", "miner.py:2:5: expected ':' after 'if' condition");
		rejects("if x:\n    pass\nelif y\n    pass", "miner.py:3:7: expected ':' after 'elif' condition");
		rejects("if x:\n    pass\nelse\n    pass", "miner.py:3:5: expected ':' after 'else'");
		rejects("while x > 1\n    pass", "miner.py:1:12: expected ':' after 'while' condition");
		rejects("for x in xs\n    pass", "miner.py:1:12: expected ':' after 'for' clause");
		rejects("def f(a)\n    pass", "miner.py:1:9: expected ':' after function signature");
	}

	@Test
	void badIndentation() {
		rejects("x = 1\n    y = 2", "miner.py:2:5: unexpected indent");
		rejects("if x:\npass", "miner.py:2:1: expected an indented block after 'if' statement on line 1");
		rejects("def f():\n\nx = 1", "miner.py:3:1: expected an indented block after function definition on line 1");
		rejects("if x:\n        a\n    b", "miner.py:3:5: unindent does not match any outer indentation level");
		rejects("if x:\n    a\n        b", "miner.py:3:9: unexpected indent");
		rejects("if x:", "miner.py:1:6: expected an indented block after 'if' statement on line 1");
		rejects("while x:\n    if y:\n    z", "miner.py:3:5: expected an indented block after 'if' statement on line 2");
	}

	@Test
	void unclosedBracket() {
		rejects("x = foo(1,\n  [2, 3]\ny = 2", "miner.py:1:8: '(' was never closed");
		rejects("x = [1, (2]", "miner.py:1:11: closing ']' does not match opening '('");
		rejects("x = 1)", "miner.py:1:6: unmatched ')'");
	}

	@Test
	void otherSyntaxErrors() {
		rejects("x = = 1", "miner.py:1:5: invalid syntax");
		rejects("x = (1 + )", "miner.py:1:10: invalid syntax");
		rejects("1 = x", "miner.py:1:1: cannot assign to literal");
		rejects("f() = x", "miner.py:1:1: cannot assign to function call");
		rejects("x = a if b", "miner.py:1:11: expected 'else' after 'if' expression");
		rejects("return 1", "miner.py:1:1: 'return' outside function");
		rejects("break", "miner.py:1:1: 'break' outside loop");
		rejects("def f():\n    continue", "miner.py:2:5: 'continue' outside loop");
		rejects("f(a=1, 2)", "miner.py:1:8: positional argument follows keyword argument");
		rejects("def f(a=1, b):\n    pass", "miner.py:1:12: parameter without a default follows parameter with a default");
		rejects("x = 1 $ 2", "miner.py:1:7: invalid character '$'");
		rejects("x = 012", "miner.py:1:5: leading zeros in decimal integer literals are not permitted");
		rejects("s = f'{}'", "miner.py:1:7: f-string: empty expression not allowed");
		rejects("s = f'a}'", "miner.py:1:8: f-string: single '}' is not allowed");
		rejects("s = f'{x + }'", "miner.py:1:12: invalid syntax");
		rejects("x = 1 \\ 2", "miner.py:1:7: unexpected character after line continuation character");
	}
}
