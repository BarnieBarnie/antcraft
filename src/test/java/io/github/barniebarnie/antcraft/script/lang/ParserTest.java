package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest {
	/** The source's statements, dumped as s-expressions, one statement per line. */
	private static String parse(String source) {
		return AstDump.dump(Parser.parse("t.py", source));
	}

	/** One expression, dumped. */
	private static String expr(String source) {
		return parse(source);
	}

	@Test
	void literalsAndNames() {
		assertEquals("x", expr("x"));
		assertEquals("42", expr("42"));
		assertEquals("1.5", expr("1.5"));
		assertEquals("'a\\'b'", expr("\"a'b\""));
		assertEquals("True", expr("True"));
		assertEquals("False", expr("False"));
		assertEquals("None", expr("None"));
		assertEquals("'ab'", expr("'a' \"b\""));
	}

	@Test
	void containerLiterals() {
		assertEquals("(list)", expr("[]"));
		assertEquals("(list 1 2)", expr("[1, 2,]"));
		assertEquals("(tuple)", expr("()"));
		assertEquals("(tuple 1)", expr("(1,)"));
		assertEquals("(tuple 1 2)", expr("1, 2"));
		assertEquals("1", expr("(1)"));
		assertEquals("(dict)", expr("{}"));
		assertEquals("(dict ('a' 1) (x (list)))", expr("{'a': 1, x: []}"));
	}

	@Test
	void operatorPrecedence() {
		assertEquals("(+ 1 (* 2 3))", expr("1 + 2 * 3"));
		assertEquals("(- (- 1 2) 3)", expr("1 - 2 - 3"));
		assertEquals("(* (+ 1 2) 3)", expr("(1 + 2) * 3"));
		assertEquals("(** 2 (** 3 2))", expr("2 ** 3 ** 2"));
		assertEquals("(neg (** 2 2))", expr("-2 ** 2"));
		assertEquals("(** 2 (neg 1))", expr("2 ** -1"));
		assertEquals("(+ (% (// (/ a b) c) d) (pos e))", expr("a / b // c % d + +e"));
		assertEquals("(or a (and b (not c)))", expr("a or b and not c"));
		assertEquals("(not (cmp a == b))", expr("not a == b"));
		assertEquals("(and (cmp (+ a 1) < b) c)", expr("a + 1 < b and c"));
		assertEquals("(neg (. a b))", expr("-a.b"));
	}

	@Test
	void comparisons() {
		assertEquals("(cmp a < b)", expr("a < b"));
		assertEquals("(cmp a < b <= c)", expr("a < b <= c"));
		assertEquals("(cmp a == b != c > d >= e)", expr("a == b != c > d >= e"));
		assertEquals("(cmp a in b)", expr("a in b"));
		assertEquals("(cmp a not-in b)", expr("a not in b"));
		assertEquals("(cmp a is None)", expr("a is None"));
		assertEquals("(cmp None is-not a)", expr("None is not a"));
	}

	@Test
	void conditionalExpressions() {
		assertEquals("(if c a b)", expr("a if c else b"));
		assertEquals("(if c a (if d b e))", expr("a if c else b if d else e"));
		assertEquals("(if (or c d) (+ a 1) b)", expr("a + 1 if c or d else b"));
	}

	@Test
	void callsAttributesAndIndexing() {
		assertEquals("(call f)", expr("f()"));
		assertEquals("(call f 1 (kw near 2))", expr("f(1, near=2)"));
		assertEquals("(call f 1 (* xs) 2 (kw near 2))", expr("f(1, *xs, 2, near=2)"));
		assertEquals("(call f 1 (* xs) (kw k 2) (** d) (kw j 3) (** e))", expr("f(1, *xs, k=2, **d, j=3, **e)"));
		assertEquals("(call (. (. lib shaft) dig_down) (kw to_y 16))", expr("lib.shaft.dig_down(to_y=16)"));
		assertEquals("(index a 0)", expr("a[0]"));
		assertEquals("(index (index a 0) (neg 1))", expr("a[0][-1]"));
		assertEquals("(index a (slice 1 2 _))", expr("a[1:2]"));
		assertEquals("(index a (slice _ _ _))", expr("a[:]"));
		assertEquals("(index a (slice _ _ (neg 1)))", expr("a[::-1]"));
		assertEquals("(index a (slice x _ 2))", expr("a[x::2]"));
		assertEquals("(index d (tuple 1 2))", expr("d[1, 2]"));
		assertEquals("(call (. (call pos) north))", expr("pos().north()"));
	}

	@Test
	void listComprehensions() {
		assertEquals("(listcomp (* x 2) (for x xs))", expr("[x * 2 for x in xs]"));
		assertEquals("(listcomp x (for x xs (if (cmp x > 0)) (if y)))", expr("[x for x in xs if x > 0 if y]"));
		assertEquals("(listcomp (tuple a b) (for (tuple a b) pairs) (for c a))", expr("[(a, b) for a, b in pairs for c in a]"));
		assertEquals("(listcomp (if c a b) (for a xs))", expr("[a if c else b for a in xs]"));
	}

	@Test
	void fStrings() {
		assertEquals("(fstr 'no pickaxe: ' (fmt (. r reason)))", expr("f\"no pickaxe: {r.reason}\""));
		assertEquals("(fstr (fmt x r '>5') ' {' (fmt (+ a 1)))", expr("f'{x!r:>5} {{{a + 1}'"));
		assertEquals("(fstr 'a' (fmt x) 'b')", expr("'a' f'{x}' 'b'"));
		assertEquals("(fstr (fmt (index d 'k')))", expr("f\"{d['k']}\""));
	}

	@Test
	void fStringFieldsKnowTheirPosition() {
		Ast.Module module = Parser.parse("t.py", "\nx = f'ab{y}'");
		Ast.Assign assign = assertInstanceOf(Ast.Assign.class, module.body().getFirst());
		Ast.FString fstring = assertInstanceOf(Ast.FString.class, assign.value());
		Ast.FString.Value value = assertInstanceOf(Ast.FString.Value.class, fstring.parts().get(1));
		assertEquals(2, value.expr().line());
		assertEquals(10, value.expr().col());
	}

	@Test
	void assignments() {
		assertEquals("(= x 1)", parse("x = 1"));
		assertEquals("(= a b 1)", parse("a = b = 1"));
		assertEquals("(= (tuple a b) (tuple b a))", parse("a, b = b, a"));
		assertEquals("(= (list a b) c)", parse("[a, b] = c"));
		assertEquals("(= (tuple a b) c)", parse("(a, b) = c"));
		assertEquals("(= (index memory 'chest') (call find_nearest 'chest'))", parse("memory[\"chest\"] = find_nearest(\"chest\")"));
		assertEquals("(= (. a b) 1)", parse("a.b = 1"));
		assertEquals("(+= x 1)\n(-= x 1)\n(*= x 1)\n(/= x 1)\n(//= x 1)\n(%= x 1)\n(**= x 1)",
				parse("x += 1\nx -= 1\nx *= 1\nx /= 1\nx //= 1\nx %= 1\nx **= 1"));
		assertEquals("(+= (index a 0) 1)", parse("a[0] += 1"));
	}

	@Test
	void simpleStatements() {
		assertEquals("(call f)\npass\n(global a b)", parse("f(); pass\nglobal a, b"));
		assertEquals("(import trees)\n(import lib.shaft os)", parse("import trees\nimport lib.shaft, os"));
		assertEquals("(from lib.trees fell plant)\n(from m a)", parse("from lib.trees import fell, plant\nfrom m import (a,)"));
	}

	@Test
	void assertStatements() {
		assertEquals("(assert (call mine (tuple 1 1 1)))", parse("assert mine((1, 1, 1))"));
		assertEquals("(assert (cmp x == 1) 'x is not one')", parse("assert x == 1, 'x is not one'"));
		Ast.Assert statement = assertInstanceOf(Ast.Assert.class, Parser.parse("t.py", "pass\n  \nassert x").body().get(1));
		assertEquals(3, statement.line());
		assertEquals(1, statement.col());
	}

	@Test
	void ifElifElse() {
		assertEquals("(if a [x] [(if b [y] [z])])", parse("if a:\n    x\nelif b:\n    y\nelse:\n    z\n"));
		assertEquals("(if a [x y] [])", parse("if a: x; y"));
	}

	@Test
	void loops() {
		assertEquals("(while True [(if x [break] [continue])])", parse("while True:\n    if x:\n        break\n    else:\n        continue"));
		assertEquals("(for ore (call scan 'x') [(call mine ore)])", parse("for ore in scan('x'):\n    mine(ore)"));
		assertEquals("(for (tuple i x) (call enumerate xs) [pass])", parse("for i, x in enumerate(xs): pass"));
	}

	@Test
	void functions() {
		assertEquals("(def f () [return])", parse("def f():\n    return"));
		assertEquals("(def dig (to_y (ladders True)) [(return (+ to_y 1))])",
				parse("def dig(to_y, ladders=True):\n    return to_y + 1"));
		assertEquals("(def f (a) [(return (tuple a 1))])", parse("def f(a,):\n    return a, 1"));
		assertEquals("(def f (a (b 1) (* args) (** kwargs)) [pass])", parse("def f(a, b=1, *args, **kwargs):\n    pass"));
		assertEquals("(def f ((** kw)) [pass])", parse("def f(**kw,):\n    pass"));
	}

	@Test
	void argumentUnpackingFollowsPythonsRules() {
		assertEquals("t.py:1:8: positional argument follows keyword argument unpacking", syntaxError("f(**d, 1)"));
		assertEquals("t.py:1:8: iterable argument unpacking follows keyword argument unpacking", syntaxError("f(**d, *xs)"));
		assertEquals("t.py:1:11: * argument may appear only once", syntaxError("def f(*a, *b):\n    pass"));
		assertEquals("t.py:1:12: arguments cannot follow var-keyword argument", syntaxError("def f(**k, a):\n    pass"));
		assertEquals("t.py:1:11: duplicate parameter 'a'", syntaxError("def f(a, *a):\n    pass"));
		assertEquals("t.py:1:11: keyword-only parameters are not supported", syntaxError("def f(*a, b):\n    pass"));
		assertEquals("t.py:1:7: keyword-only parameters are not supported", syntaxError("def f(*, b):\n    pass"));
	}

	@Test
	void everyNodeKnowsItsPosition() {
		Ast.Module module = Parser.parse("t.py", "x = 1\nif x:\n    y = a + b\n");
		Ast.If statement = assertInstanceOf(Ast.If.class, module.body().get(1));
		assertEquals(2, statement.line());
		assertEquals(1, statement.col());
		Ast.Assign assign = assertInstanceOf(Ast.Assign.class, statement.body().getFirst());
		assertEquals(List.of(3, 5), List.of(assign.line(), assign.col()));
		Ast.BinOp sum = assertInstanceOf(Ast.BinOp.class, assign.value());
		assertEquals(List.of(3, 11), List.of(sum.line(), sum.col()));
		assertEquals(List.of(3, 13), List.of(sum.right().line(), sum.right().col()));
	}

	@Test
	void theExampleScriptsOfTheDesignParse() {
		String punch = """
				tree = find_nearest("#minecraft:logs")
				if not tree:
				    say("no tree near")
				    stop()
				goto(tree, near=2)
				while count("#minecraft:logs") < 3:
				    log = find_nearest("#minecraft:logs", radius=6)
				    if not log or not mine(log):
				        break
				pick_up("#minecraft:logs")
				craft("oak_planks", 3)
				table_spot = pos().north()
				craft("crafting_table") and place(table_spot, "crafting_table")
				r = craft("wooden_pickaxe", table=table_spot)
				say("pickaxe!" if r else f"no pickaxe: {r.reason}")
				""";
		assertEquals(10, Parser.parse("punch.py", punch).body().size());
		String miner = """
				import lib.shaft

				chest = memory.get("chest") or find_nearest("chest")
				memory["chest"] = chest
				while True:
				    r = lib.shaft.dig_down(to_y=16, ladders=True)
				    if not r:
				        say(f"shaft stopped: {r.reason}")
				        break
				    for ore in scan("#c:ores/iron", radius=8):
				        mine(ore)
				    goto(chest, near=2)
				    put(chest, "#c:raw_materials")
				""";
		assertEquals(4, Parser.parse("miner.py", miner).body().size());
	}

	@Test
	void aNestedFunctionThatCapturesNothingIsFine() {
		assertEquals("(def f (a) [(def g (b) [(return (+ b c))]) (return (call g a))])",
				parse("c = 1\ndef f(a):\n    def g(b):\n        return b + c\n    return g(a)").lines().toList().get(1));
	}

	@Test
	void tryStatements() {
		assertEquals("(try [(call f)] (except _ [pass]))", parse("try:\n    f()\nexcept:\n    pass"));
		assertEquals("(try [(call f)] (except ValueError [pass]) (except (tuple A B) e [(call g e)]) (else [x]) (finally [y]))",
				parse("try:\n    f()\nexcept ValueError:\n    pass\nexcept (A, B) as e:\n    g(e)\nelse:\n    x\nfinally:\n    y"));
		assertEquals("(try [x] (finally [y]))", parse("try: x\nfinally: y"));
		assertEquals("raise\n(raise (call ValueError 'x'))", parse("raise\nraise ValueError('x')"));
	}

	@Test
	void classStatements() {
		assertEquals("(class Miner _ [pass])", parse("class Miner:\n    pass"));
		assertEquals("(class Miner _ [pass])", parse("class Miner():\n    pass"));
		assertEquals("(class Tunneler Miner [(= depth 1) (def dig (self) [(return (. self depth))])])",
				parse("class Tunneler(Miner):\n    depth = 1\n    def dig(self):\n        return self.depth"));
		assertEquals("(class NoOre (. lib Error) [pass])", parse("class NoOre(lib.Error): pass"));
	}

	@Test
	void malformedTryStatements() {
		assertEquals("t.py:3:1: expected 'except' or 'finally' block", syntaxError("try:\n    x\ny = 1"));
		assertEquals("t.py:3:1: default 'except:' must be last", syntaxError("try:\n    x\nexcept:\n    pass\nexcept ValueError:\n    pass"));
		assertEquals("t.py:3:1: expected 'except' or 'finally' block", syntaxError("try:\n    x\nelse:\n    pass"));
		assertEquals("t.py:1:9: `raise ... from` is not supported", syntaxError("raise X from Y"));
	}

	private static String syntaxError(String source) {
		return org.junit.jupiter.api.Assertions.assertThrows(CompileError.class, () -> Parser.parse("t.py", source)).getMessage();
	}
}
