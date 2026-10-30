package io.github.barniebarnie.antcraft.script.lang;

import static io.github.barniebarnie.antcraft.script.lang.Scripts.fails;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code try}, {@code except}, {@code else}, {@code finally} and {@code raise} (docs/language/errors.md), as in Python. */
class TryTest {
	@Test
	void anExceptCatchesARuntimeErrorAndTheScriptGoesOn() {
		assertEquals("caught\nafter", run("""
				try:
				    [][1]
				    print('not here')
				except IndexError:
				    print('caught')
				print('after')
				"""));
	}

	@Test
	void theErrorsOfTheVmHavePythonsTypes() {
		String[][] cases = {
				{"[][0]", "IndexError"},
				{"{}['k']", "KeyError"},
				{"1 // 0", "ZeroDivisionError"},
				{"int('x')", "ValueError"},
				{"1 + 'a'", "TypeError"},
				{"nope", "NameError"},
				{"(1).nope", "AttributeError"},
				{"[].pop()", "IndexError"},
				{"9223372036854775807 + 1", "OverflowError"},
		};
		for (String[] c : cases) {
			assertEquals(c[1], run("try:\n    " + c[0] + "\nexcept " + c[1] + ":\n    print('" + c[1] + "')\n"), c[0]);
			assertEquals("Exception", run("try:\n    " + c[0] + "\nexcept Exception:\n    print('Exception')\n"), c[0]);
		}
		assertEquals("AssertionError", run("try:\n    assert False\nexcept AssertionError:\n    print('AssertionError')\n"));
		assertEquals("RecursionError\nRuntimeError", run("""
				def f():
				    f()
				try:
				    f()
				except RecursionError:
				    print('RecursionError')
				try:
				    f()
				except RuntimeError:
				    print('RuntimeError')
				"""));
		assertEquals("ImportError", run("try:\n    import nothing\nexcept ImportError:\n    print('ImportError')\n"));
	}

	@Test
	void strOfACaughtErrorIsItsMessage() {
		assertEquals("list index out of range|IndexError('list index out of range')|True|True|False", run("""
				try:
				    [][3]
				except Exception as e:
				    print(str(e), f'{e!r}', isinstance(e, IndexError), isinstance(e, Exception), isinstance(e, KeyError), sep='|')
				"""));
	}

	@Test
	void aHandlerThatDoesNotMatchLetsTheErrorThrough() {
		assertEquals("t.py:2: division by zero", fails("try:\n    1 / 0\nexcept KeyError:\n    pass\n"));
		assertEquals("zero", run("""
				try:
				    try:
				        1 / 0
				    except KeyError:
				        print('key')
				except ZeroDivisionError:
				    print('zero')
				"""));
	}

	@Test
	void theFirstMatchingClauseRunsAndATupleMatchesAnyOfItsTypes() {
		assertEquals("lookup", run("""
				try:
				    {}['x']
				except ValueError:
				    print('value')
				except (IndexError, KeyError) as e:
				    print('lookup')
				except Exception:
				    print('other')
				"""));
		assertEquals("bare", run("try:\n    nope\nexcept ValueError:\n    print('v')\nexcept:\n    print('bare')\n"));
	}

	@Test
	void elseRunsOnlyWithoutAnError() {
		assertEquals("body\nelse\nend", run("""
				try:
				    print('body')
				except Exception:
				    print('except')
				else:
				    print('else')
				print('end')
				"""));
		assertEquals("except", run("try:\n    1 / 0\nexcept Exception:\n    print('except')\nelse:\n    print('else')\n"));
	}

	@Test
	void raiseRaisesABuiltinExceptionWithItsMessage() {
		assertEquals("ValueError('bad')|bad|ValueError()", run("""
				try:
				    raise ValueError('bad')
				except ValueError as e:
				    print(f'{e!r}', str(e), f'{ValueError()!r}', sep='|')
				"""));
		assertEquals("caught ", run("try:\n    raise KeyError\nexcept KeyError as e:\n    print('caught', e)\n"));
		assertEquals("t.py:1: ValueError: bad", fails("raise ValueError('bad')\n"));
		assertEquals("t.py:1: TypeError", fails("raise TypeError\n"));
		assertEquals("t.py:1: exceptions must derive from BaseException", fails("raise 5\n"));
	}

	@Test
	void aBareRaiseRaisesTheHandledErrorAgainWithItsLine() {
		assertEquals("t.py:2: list index out of range", fails("""
				try:
				    [][1]
				except IndexError:
				    print('seen')
				    raise
				"""));
		assertEquals("seen\nouter list index out of range", run("""
				try:
				    try:
				        [][1]
				    except IndexError:
				        print('seen')
				        raise
				except Exception as e:
				    print('outer', e)
				"""));
	}

	@Test
	void aBareRaiseOutsideAnExceptIsACompileError() {
		CompileError error = assertThrows(CompileError.class, () -> Compiler.compile(Parser.parse("t.py", "raise\n")));
		assertEquals("t.py:1:1: a bare 'raise' is only supported inside an 'except' block", error.getMessage());
	}

	@Test
	void anErrorInAFunctionIsCaughtByTheCaller() {
		assertEquals("caught 2", run("""
				def deep(n):
				    if n == 0:
				        return [][0]
				    return deep(n - 1)
				def safe():
				    try:
				        deep(3)
				    except IndexError:
				        return 2
				print('caught', safe())
				"""));
	}

	@Test
	void finallyRunsOnEveryWayOut() {
		assertEquals("normal\nf1", run("try:\n    print('normal')\nfinally:\n    print('f1')\n"));
		assertEquals("f2\nresult 1", run("""
				def f():
				    try:
				        return 1
				    finally:
				        print('f2')
				print('result', f())
				"""));
		assertEquals("0\nf3\n1\nf3\nf3\ndone", run("""
				for i in range(5):
				    try:
				        if i == 2:
				            break
				        print(i)
				    finally:
				        print('f3')
				print('done')
				"""));
		assertEquals("f4\n1\nf4\nf4", run("""
				for i in range(3):
				    try:
				        if i != 1:
				            continue
				        print(i)
				    finally:
				        print('f4')
				"""));
		assertEquals("f5\ncaught division by zero", run("""
				try:
				    try:
				        1 / 0
				    finally:
				        print('f5')
				except ZeroDivisionError as e:
				    print('caught', e)
				"""));
		assertEquals("t.py:2: division by zero", fails("try:\n    1 / 0\nfinally:\n    pass\n"));
	}

	@Test
	void finallyRunsAfterAHandlerAndAfterAnErrorInAHandler() {
		assertEquals("except\nfinally", run("""
				try:
				    1 / 0
				except ZeroDivisionError:
				    print('except')
				finally:
				    print('finally')
				"""));
		List<String> printed = new ArrayList<>();
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", """
				try:
				    1 / 0
				except ZeroDivisionError:
				    [][0]
				finally:
				    print('finally')
				""")), Natives.standard(Output.lines(printed::add)));
		assertEquals(Vm.Status.FAILED, vm.run(1000));
		assertEquals(List.of("finally"), printed);
		assertEquals("t.py:4: list index out of range", vm.error().getMessage());
	}

	@Test
	void returnsAndBreaksRunEachFinallyOutwardsInOrder() {
		assertEquals("inner\nouter\nr 1", run("""
				def f():
				    try:
				        try:
				            return 1
				        finally:
				            print('inner')
				    finally:
				        print('outer')
				print('r', f())
				"""));
		assertEquals("r 2", run("""
				def f():
				    try:
				        return 1
				    finally:
				        return 2
				print('r', f())
				"""));
		assertEquals("in except\nfinally\nr 3", run("""
				def f():
				    try:
				        [][0]
				    except IndexError:
				        print('in except')
				        return 3
				    finally:
				        print('finally')
				print('r', f())
				"""));
		assertEquals("2", run("""
				def f():
				    for i in range(3):
				        try:
				            return i
				        finally:
				            if i < 2:
				                continue
				    return 9
				print(f())
				"""));
	}

	@Test
	void theExceptVariableIsLocalInAFunction() {
		assertEquals("x", run("""
				e = 'x'
				def f():
				    try:
				        [][0]
				    except IndexError as e:
				        return e
				f()
				print(e)
				"""));
	}

	@Test
	void aFunctionDefinedInATryWorks() {
		assertEquals("4", run("try:\n    def g(x):\n        return x * 2\nexcept Exception:\n    pass\nprint(g(2))\n"));
	}

	@Test
	void aFailedImportCanBeTriedAgain() {
		assertEquals("No module named 'nothing'\nNo module named 'nothing'", run("""
				for i in range(2):
				    try:
				        import nothing
				    except ImportError as e:
				        print(e)
				"""));
	}

	@Test
	void aJavaExceptionOfTheHostIsNotCaught() {
		Natives natives = Natives.standard(Output.lines(line -> {
		}));
		natives.register("boom", call -> {
			throw new IllegalStateException("host bug");
		});
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "try:\n    boom()\nexcept:\n    pass\n")), natives);
		assertThrows(IllegalStateException.class, () -> vm.run(100));
	}

	@Test
	void aNonExceptionInAnExceptClauseIsATypeError() {
		assertEquals("t.py:3: catching classes that do not inherit from BaseException is not allowed",
				fails("try:\n    1 / 0\nexcept 5:\n    pass\n"));
	}

	@Test
	void theStackOfALoopIsKeptWhenAnErrorIsCaughtInIt() {
		assertEquals("0\n1 err\n2\n3", run("""
				total = 0
				for i in range(3):
				    try:
				        if i == 1:
				            print(i, [][0])
				        print(i)
				    except IndexError:
				        print(i, 'err')
				    total += i
				print(total)
				"""));
	}
}
