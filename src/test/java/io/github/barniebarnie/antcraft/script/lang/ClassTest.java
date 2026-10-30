package io.github.barniebarnie.antcraft.script.lang;

import static io.github.barniebarnie.antcraft.script.lang.Scripts.fails;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Classes (docs/language/classes.md), as in Python. */
class ClassTest {
	@Test
	void aClassDefinitionMakesAClass() {
		assertEquals("<class 'Miner'> Miner", run("""
				class Miner:
				    pass
				print(Miner, Miner.__name__)
				"""));
	}

	@Test
	void callingAClassMakesAnInstanceAndRunsInit() {
		assertEquals("<Miner object> 3 True False", run("""
				class Miner:
				    def __init__(self, depth):
				        self.depth = depth
				m = Miner(3)
				n = Miner(4)
				print(m, m.depth, m == m, m == n)
				"""));
	}

	@Test
	void aClassWithoutInitTakesNoArguments() {
		assertEquals("t.py:3: Miner() takes no arguments", fails("class Miner:\n    pass\nMiner(1)\n"));
		assertEquals("t.py:4: __init__() should return None, not 'int'",
				fails("class Miner:\n    def __init__(self):\n        return 1\nMiner()\n"));
	}

	@Test
	void methodsGetTheInstanceAsSelf() {
		assertEquals("1 2 12", run("""
				class Counter:
				    def __init__(self, start=0):
				        self.n = start
				    def inc(self, by=1):
				        self.n += by
				        return self.n
				c = Counter()
				print(c.inc(), c.inc())
				inc = Counter(10).inc
				print(inc(by=2))
				""").replace("\n", " "));
	}

	@Test
	void classAttributesAreSharedAndAnInstanceAttributeHidesThem() {
		assertEquals("0\n2 2\n5 2", run("""
				class Ant:
				    count = 0
				    def __init__(self):
				        Ant.count += 1
				print(Ant.count)
				a = Ant()
				b = Ant()
				print(Ant.count, a.count)
				a.count = 5
				print(a.count, b.count)
				"""));
	}

	@Test
	void aMissingAttributeIsAnAttributeError() {
		assertEquals("t.py:3: 'Miner' object has no attribute 'depth'", fails("class Miner:\n    pass\nMiner().depth\n"));
		assertEquals("t.py:3: type object 'Miner' has no attribute 'depth'", fails("class Miner:\n    pass\nMiner.depth\n"));
		assertEquals("caught", run("class Miner:\n    pass\ntry:\n    Miner().x\nexcept AttributeError:\n    print('caught')\n"));
	}

	@Test
	void aSubclassInheritsAndOverridesMethods() {
		assertEquals("dig 1|tunnel 1|dig 2\nTrue True False", run("""
				class Miner:
				    def __init__(self, depth):
				        self.depth = depth
				    def dig(self):
				        return 'dig ' + str(self.depth)
				    def name(self):
				        return self.dig()
				class Tunneler(Miner):
				    def dig(self):
				        return 'tunnel ' + str(self.depth)
				t = Tunneler(1)
				print(Miner(1).name(), t.name(), Miner.dig(Miner(2)), sep='|')
				print(isinstance(t, Miner), isinstance(t, (int, Tunneler)), isinstance(Miner(1), Tunneler))
				"""));
	}

	@Test
	void superCallsTheBaseClassesMethods() {
		assertEquals("3 4 base+sub", run("""
				class Base:
				    def __init__(self, a):
				        self.a = a
				    def who(self):
				        return 'base'
				class Sub(Base):
				    def __init__(self, a, b):
				        super().__init__(a)
				        self.b = b
				    def who(self):
				        return super().who() + '+sub'
				s = Sub(3, 4)
				print(s.a, s.b, s.who())
				"""));
		assertEquals("ok", run("class A:\n    def __init__(self):\n        super().__init__()\nA()\nprint('ok')\n"));
	}

	@Test
	void aClassInAFunctionAndAFunctionUsingAClass() {
		assertEquals("2", run("""
				def make():
				    class Box:
				        def __init__(self, v):
				            self.v = v
				    return Box(2)
				print(make().v)
				"""));
	}

	@Test
	void aUserExceptionIsRaisedAndCaught() {
		assertEquals("no ore here|NoOre('no ore here')|True|True|True", run("""
				class NoOre(Exception):
				    pass
				try:
				    raise NoOre('no ore here')
				except NoOre as e:
				    print(str(e), f'{e!r}', isinstance(e, NoOre), isinstance(e, Exception), e.args == ('no ore here',), sep='|')
				"""));
		assertEquals("caught by Exception", run("""
				class NoOre(Exception):
				    pass
				try:
				    raise NoOre
				except ValueError:
				    print('wrong')
				except Exception:
				    print('caught by Exception')
				"""));
	}

	@Test
	void aUserExceptionCanHaveItsOwnInitAndAttributes() {
		assertEquals("deep 5|deep|True", run("""
				class TooDeep(ValueError):
				    def __init__(self, depth):
				        super().__init__('deep ' + str(depth))
				        self.depth = depth
				    def short(self):
				        return 'deep'
				try:
				    raise TooDeep(5)
				except ValueError as e:
				    print(e, e.short(), e.depth == 5, sep='|')
				"""));
	}

	@Test
	void anUncaughtUserExceptionFailsTheScriptWithItsTypeAndMessage() {
		assertEquals("t.py:3: NoOre: none left", fails("class NoOre(Exception):\n    pass\nraise NoOre('none left')\n"));
		assertEquals("t.py:6: NoOre", fails("class NoOre(Exception):\n    pass\ntry:\n    x = 1\nfinally:\n    raise NoOre()\n"));
	}

	@Test
	void aBareRaiseOfAUserExceptionKeepsItsLine() {
		assertEquals("t.py:4: NoOre: x", fails("""
				class NoOre(Exception):
				    pass
				try:
				    raise NoOre('x')
				except NoOre:
				    raise
				"""));
	}

	@Test
	void onlyExceptionClassesAreRaisedOrCaught() {
		assertEquals("t.py:3: exceptions must derive from BaseException", fails("class A:\n    pass\nraise A()\n"));
		assertEquals("t.py:5: catching classes that do not inherit from BaseException is not allowed",
				fails("class A:\n    pass\ntry:\n    1 / 0\nexcept A:\n    pass\n"));
		assertEquals("t.py:4: raise E(...): the class has an __init__",
				fails("class E(Exception):\n    def __init__(self, x):\n        pass\nraise E\n"));
		assertEquals("t.py:3: object.__init__() takes exactly one argument (the instance to initialize)",
				fails("class A:\n    def __init__(self):\n        super().__init__(1)\nA()\n"));
	}

	@Test
	void aClassMayOnlyDeriveFromAClassOrAnExceptionType() {
		assertEquals("t.py:1: a class can only derive from another class or an exception type, not 'int'",
				fails("class A(1):\n    pass\n"));
		assertEquals("t.py:1: a class can only derive from another class or an exception type, not 'builtin_function_or_method'",
				fails("class A(len):\n    pass\n"));
	}

	@Test
	void superOnlyWorksInAMethod() {
		CompileError error = assertThrows(CompileError.class, () -> Compiler.compile(Parser.parse("t.py", "def f():\n    super().x()\n")));
		assertEquals("t.py:2:10: `super()` is only supported without arguments, in a method of a class", error.getMessage());
		CompileError withArgs = assertThrows(CompileError.class,
				() -> Compiler.compile(Parser.parse("t.py", "class A:\n    def f(self):\n        super(A, self).f()\n")));
		assertEquals("t.py:3:14: `super()` is only supported without arguments, in a method of a class", withArgs.getMessage());
	}

	@Test
	void instancesAndClassesOfAnImportedModuleWork() {
		List<String> printed = new ArrayList<>();
		Map<String, String> modules = Map.of("lib.miner", """
				DEPTH = 7
				class NoOre(Exception):
				    pass
				class Miner:
				    def __init__(self):
				        self.depth = DEPTH
				    def dig(self):
				        raise NoOre('at ' + str(self.depth))
				""");
		Vm vm = new Vm("t", Compiler.compile(Parser.parse("t.py", """
				from lib.miner import Miner, NoOre
				m = Miner()
				try:
				    m.dig()
				except NoOre as e:
				    print(e, isinstance(m, Miner))
				""")), Natives.standard(Output.lines(printed::add)), new ImportTestLibrary(modules));
		assertEquals(Vm.Status.FINISHED, vm.run(1_000_000));
		assertEquals(List.of("at 7 True"), printed);
	}

	@Test
	void aMethodCallCountsItsInstructionsLikeAFunctionCall() {
		long method = instructions("class A:\n    def f(self):\n        return 1\na = A()\nx = 0\nx = a.f()\n")
				- instructions("class A:\n    def f(self):\n        return 1\na = A()\nx = 0\n");
		long function = instructions("def f(a):\n    return 1\na = 0\nx = 0\nx = f(a)\n")
				- instructions("def f(a):\n    return 1\na = 0\nx = 0\n");
		assertEquals(function, method); // LOAD_ATTR in place of LOAD_GLOBAL for the callee, then the same call
	}

	private static long instructions(String source) {
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(line -> {
		})));
		assertEquals(Vm.Status.FINISHED, vm.run(1_000_000));
		return vm.executed();
	}

	/** Modules by name, compiled from source on import. */
	private record ImportTestLibrary(Map<String, String> sources) implements ModuleLoader {
		@Override
		public Code load(String name) {
			String source = sources.get(name);
			return source == null ? null : Compiler.compile(Parser.parse(name.replace('.', '/') + ".py", source));
		}

		@Override
		public boolean isPackage(String name) {
			return sources.keySet().stream().anyMatch(module -> module.startsWith(name + "."));
		}
	}
}
