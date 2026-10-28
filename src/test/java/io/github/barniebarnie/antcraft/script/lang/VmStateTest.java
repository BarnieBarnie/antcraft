package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Saving a running VM and loading it again (docs/scripting.md, Save and resume): a script saved and loaded part way, at
 * any instruction, goes on to the same end as one that ran without a break.
 */
class VmStateTest {
	/** Modules by name, compiled from source on every load, as a library read again after a restart is. */
	private record Library(Map<String, String> sources) implements ModuleLoader {
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

	/** A host value of the test, saved as a tuple. */
	private record Thing(long n) implements HostValue {
		@Override
		public String typeName() {
			return "Thing";
		}

		@Override
		public Object attribute(String name) {
			return name.equals("n") ? n : null;
		}

		@Override
		public String toString() {
			return "Thing(" + n + ")";
		}
	}

	private static final VmState.HostCodec THINGS = new VmState.HostCodec() {
		@Override
		public Object save(Object value) {
			return value instanceof Thing thing ? PyTuple.of("Thing", thing.n()) : null;
		}

		@Override
		public Object load(Object saved) {
			return new Thing((Long) ((PyTuple) saved).items().get(1));
		}
	};

	private static Natives natives(List<String> printed) {
		Natives natives = Natives.standard(Output.lines(printed::add));
		natives.register("thing", call -> new Thing((Long) call.args().getFirst()));
		return natives;
	}

	private static Code compile(String source) {
		return Compiler.compile(Parser.parse("t.py", source));
	}

	private static Vm vm(String source, Map<String, String> modules, List<String> printed) {
		Vm vm = new Vm("t", compile(source), natives(printed), new Library(modules));
		vm.setGlobal("ARGS", new PyList(new ArrayList<>(List.of("a", "b"))));
		vm.setBuiltin("memory", checkedDict());
		return vm;
	}

	/** A dict that takes no functions, as a bot's memory checks what it stores. */
	private static PyDict checkedDict() {
		return new PyDict((key, value) -> {
			if (value instanceof PyFunction) {
				throw new ScriptError("no functions in memory");
			}
		});
	}

	/** Saves {@code vm} and loads it again, as a new VM with new natives that print to {@code printed}. */
	private static Vm reload(Vm vm, String source, Map<String, String> modules, List<String> printed) {
		try {
			byte[] saved = VmState.save(vm, THINGS);
			return VmState.load(saved, compile(source), natives(printed), new Library(modules), Vm.DEFAULT_MAX_DEPTH, THINGS,
					VmStateTest::checkedDict);
		} catch (VmState.NotSaveable | VmState.Unloadable e) {
			throw new AssertionError(e);
		}
	}

	private static String finish(Vm vm, List<String> printed) {
		Vm.Status status = vm.run(1_000_000);
		if (status == Vm.Status.FAILED) {
			throw vm.error();
		}
		assertEquals(Vm.Status.FINISHED, status);
		return String.join("\n", printed);
	}

	/** What the script prints when it runs without a break, and how many instructions that takes. */
	private static String uninterrupted(String source, Map<String, String> modules) {
		List<String> printed = new ArrayList<>();
		return finish(vm(source, modules, printed), printed);
	}

	private static long instructions(String source, Map<String, String> modules) {
		Vm vm = vm(source, modules, new ArrayList<>());
		vm.run(1_000_000);
		return vm.executed();
	}

	/** Runs the script {@code every} instructions at a time, saving and loading it between every two runs. */
	private static String savedEvery(int every, String source, Map<String, String> modules) {
		List<String> printed = new ArrayList<>();
		Vm vm = vm(source, modules, printed);
		while (vm.run(every) == Vm.Status.RUNNING) {
			vm = reload(vm, source, modules, printed);
		}
		if (vm.status() == Vm.Status.FAILED) {
			throw vm.error();
		}
		return String.join("\n", printed);
	}

	/** Runs the script {@code first} instructions, saves and loads it, and runs it to its end. */
	private static String savedOnceAfter(long first, String source, Map<String, String> modules) {
		List<String> printed = new ArrayList<>();
		Vm vm = vm(source, modules, printed);
		if (vm.run(first) != Vm.Status.RUNNING) {
			return String.join("\n", printed);
		}
		return finish(reload(vm, source, modules, printed), printed);
	}

	/** A script of the tests, with the modules it imports. */
	private record Case(String name, String source, Map<String, String> modules) {
	}

	static Stream<Case> scripts() {
		return Stream.of(
				new Case("for loops", """
						total = 0
						for i in range(3, 20, 4):
						    total += i
						for c in 'héllo':
						    print(c)
						for x in [1, 2, 3]:
						    for y in (4, 5):
						        print(x * y)
						for i, w in enumerate(['a', 'b'], 1):
						    print(i, w)
						for a, b in zip([1, 2, 3], 'xy'):
						    print(a, b)
						for x in reversed([7, 8, 9]):
						    print(x)
						i = 0
						while i < 5:
						    i += 1
						    if i == 2:
						        continue
						    print('w', i)
						print(total, ARGS)
						""", Map.of()),
				new Case("nested calls", """
						def fib(n):
						    if n < 2:
						        return n
						    return fib(n - 1) + fib(n - 2)
						def greet(name, greeting='hi'):
						    return greeting + ' ' + name
						def outer(xs, scale=2):
						    return [fib(x) * scale for x in xs]
						print(fib(10), outer([3, 4, 5]), greet('bob'), greet('al', greeting='yo'))
						""", Map.of()),
				new Case("comprehensions", """
						xs = [x * y for x in range(5) for y in range(3) if (x + y) % 2 == 0]
						pairs = [(k, v) for k, v in zip('abc', [1, 2, 3]) if v != 2]
						nested = [[j for j in range(i)] for i in range(4)]
						print(xs, pairs, nested, sum([x for x in range(10)]))
						""", Map.of()),
				new Case("sorted with a key", """
						def neg(x):
						    return -x
						words = ['pear', 'fig', 'banana', 'kiwi']
						print(sorted(words, key=len), sorted([3, 1, 2], key=neg), min(words, key=len), max(words, key=len))
						print(sorted(words, key=len, reverse=True))
						""", Map.of()),
				new Case("dict views", """
						d = {'a': 1, 'b': 2}
						keys = d.keys()
						items = d.items()
						d['c'] = 3
						for k, v in d.items():
						    print(k, v)
						for v in d.values():
						    print(v)
						print(list(keys), list(items), len(keys), 'c' in keys)
						""", Map.of()),
				new Case("shared and self-referencing values", """
						a = [1]
						b = a
						t = (a, a)
						a.append(a)
						d = {'self': None}
						d['self'] = d
						b.append(2)
						t[0].append(3)
						print(len(a), len(b), len(t[1]), len(a[1]), len(d['self']['self']), a[0], a[2], a[3])
						""", Map.of()),
				new Case("imports", """
						import lib.trees
						from lib.words import word, COUNT
						print(lib.trees.grow(3), word(2), COUNT, lib.trees.SIZE)
						for i in range(3):
						    print(lib.trees.grow(i), word(i))
						""", Map.of(
						"lib.trees", "SIZE = 4\ndef grow(n):\n    return [SIZE * i for i in range(n)]\n",
						"lib.words", "from lib.trees import grow\nCOUNT = len(grow(5))\ndef word(n):\n    return 'w' * n + str(grow(n))\n")),
				new Case("host values, memory and slices", """
						x = thing(4)
						memory['n'] = 1
						xs = list(range(10))
						for i in range(3):
						    memory['n'] += i
						    print(xs[i:7:2], x.n, x, memory['n'])
						""", Map.of()));
	}

	@TestFactory
	Stream<DynamicTest> savedAndLoadedAfterEveryFewInstructionsGoesOnToTheSameEnd() {
		return scripts().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
			String expected = uninterrupted(c.source(), c.modules());
			for (int every = 1; every <= 7; every++) {
				assertEquals(expected, savedEvery(every, c.source(), c.modules()), "saved every " + every + " instructions");
			}
		}));
	}

	@TestFactory
	Stream<DynamicTest> savedOnceAtAnyInstructionGoesOnToTheSameEnd() {
		return scripts().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
			String expected = uninterrupted(c.source(), c.modules());
			long total = instructions(c.source(), c.modules());
			for (long first = 1; first < total; first++) {
				assertEquals(expected, savedOnceAfter(first, c.source(), c.modules()), "saved after " + first + " instructions");
			}
		}));
	}

	@Test
	void aLoadedScriptKeepsItsCountsAndMemory() throws Exception {
		List<String> printed = new ArrayList<>();
		String source = "memory['x'] = [1]\nfor i in range(100):\n    pass\n";
		Vm vm = vm(source, Map.of(), printed);
		vm.run(50);
		Vm loaded = reload(vm, source, Map.of(), printed);
		assertEquals(50, loaded.executed());
		assertEquals(vm.line(), loaded.line());
		PyDict memory = (PyDict) loaded.builtins.get("memory");
		assertTrue(memory.checked(), "memory checks what it stores after a load");
		assertEquals("[1]", Values.repr(memory.get("x")));
		assertEquals(Vm.Status.FINISHED, loaded.run(1_000_000));
	}

	@Test
	void aChangedScriptDoesNotLoad() throws Exception {
		Vm vm = vm("for i in range(10):\n    print(i)\n", Map.of(), new ArrayList<>());
		vm.run(5);
		byte[] saved = VmState.save(vm, THINGS);
		VmState.Unloadable e = assertThrows(VmState.Unloadable.class, () -> VmState.load(saved,
				compile("for i in range(10):\n    print(i + 1)\n"), natives(new ArrayList<>()), new Library(Map.of()),
				Vm.DEFAULT_MAX_DEPTH, THINGS, VmStateTest::checkedDict));
		assertEquals("t.py changed since the save", e.getMessage());
	}

	@Test
	void aChangedOrMissingModuleDoesNotLoad() throws Exception {
		String source = "import m\nfor i in range(10):\n    print(m.f(i))\n";
		Map<String, String> modules = Map.of("m", "def f(x):\n    return x\n");
		Vm vm = vm(source, modules, new ArrayList<>());
		vm.run(30);
		byte[] saved = VmState.save(vm, THINGS);
		VmState.Unloadable changed = assertThrows(VmState.Unloadable.class, () -> VmState.load(saved, compile(source),
				natives(new ArrayList<>()), new Library(Map.of("m", "def f(x):\n    return x + 1\n")), Vm.DEFAULT_MAX_DEPTH,
				THINGS, VmStateTest::checkedDict));
		assertEquals("m.py changed since the save", changed.getMessage());
		VmState.Unloadable gone = assertThrows(VmState.Unloadable.class, () -> VmState.load(saved, compile(source),
				natives(new ArrayList<>()), new Library(Map.of()), Vm.DEFAULT_MAX_DEPTH, THINGS, VmStateTest::checkedDict));
		assertEquals("module m is gone", gone.getMessage());
		VmState.Unloadable broken = assertThrows(VmState.Unloadable.class, () -> VmState.load(saved, compile(source),
				natives(new ArrayList<>()), new Library(Map.of("m", "def f(x):\n    return (\n")), Vm.DEFAULT_MAX_DEPTH,
				THINGS, VmStateTest::checkedDict));
		assertTrue(broken.getMessage().startsWith("m.py:"), broken.getMessage());
	}

	@Test
	void aBrokenSaveDoesNotLoad() {
		VmState.Unloadable e = assertThrows(VmState.Unloadable.class, () -> VmState.load(new byte[] {1, 2, 3},
				compile("pass"), natives(new ArrayList<>()), new Library(Map.of()), Vm.DEFAULT_MAX_DEPTH, THINGS,
				VmStateTest::checkedDict));
		assertTrue(e.getMessage().startsWith("the save cannot be read"), e.getMessage());
	}

	@Test
	void aValueTheHostCannotSaveKeepsTheScriptFromBeingSaved() {
		Vm vm = new Vm("t", compile("x = thing(1)\nwhile True:\n    pass\n"), natives(new ArrayList<>()), ModuleLoader.NONE);
		vm.run(10);
		VmState.NotSaveable e = assertThrows(VmState.NotSaveable.class, () -> VmState.save(vm, VmState.HostCodec.NONE));
		assertEquals("a 'Thing' value cannot be saved", e.getMessage());
	}

	@Test
	void aScriptSuspendedInANativeCallMakesTheCallAgainOnceLoaded() throws Exception {
		List<List<Object>> calls = new ArrayList<>();
		Map<String, String> modules = new HashMap<>();
		String source = "r = pause(3, 'x', key=[1])\nprint(r)\n";
		List<String> printed = new ArrayList<>();
		Natives natives = natives(printed);
		natives.register("pause", call -> {
			calls.add(List.of(call.args(), call.kwargs()));
			return call.suspend();
		});
		Vm vm = new Vm("t", compile(source), natives, new Library(modules));
		assertEquals(Vm.Status.SUSPENDED, vm.run(100));
		byte[] saved = VmState.save(vm, THINGS);
		Vm loaded = VmState.load(saved, compile(source), natives, new Library(modules), Vm.DEFAULT_MAX_DEPTH, THINGS,
				VmStateTest::checkedDict);
		assertEquals(Vm.Status.SUSPENDED, loaded.status());
		assertEquals(Vm.Status.SUSPENDED, loaded.recall());
		assertEquals(2, calls.size());
		assertEquals(Values.repr(new PyList(new ArrayList<>(calls.get(0)))), Values.repr(new PyList(new ArrayList<>(calls.get(1)))));
		loaded.resume("done");
		assertEquals("done", finish(loaded, printed));
	}

	@Test
	void aNativeThatAnswersAtOnceWhenCalledAgainResumesTheScript() throws Exception {
		int[] calls = {0};
		String source = "r = pause()\nprint(r)\n";
		List<String> printed = new ArrayList<>();
		Natives natives = natives(printed);
		natives.register("pause", call -> calls[0]++ == 0 ? call.suspend() : "at once");
		Vm vm = new Vm("t", compile(source), natives, ModuleLoader.NONE);
		vm.run(100);
		Vm loaded = VmState.load(VmState.save(vm, THINGS), compile(source), natives, ModuleLoader.NONE,
				Vm.DEFAULT_MAX_DEPTH, THINGS, VmStateTest::checkedDict);
		assertEquals(Vm.Status.RUNNING, loaded.recall());
		assertEquals("at once", finish(loaded, printed));
	}

	@Test
	void aLoadedModuleIsANewOneWithTheSameGlobals() throws Exception {
		String source = "import m\nm.x.append(1)\nwhile True:\n    pass\n";
		Map<String, String> modules = Map.of("m", "x = []\n");
		Vm vm = vm(source, modules, new ArrayList<>());
		vm.run(40);
		Vm loaded = reload(vm, source, modules, new ArrayList<>());
		assertEquals(List.of("t", "m"), List.copyOf(loaded.modules().keySet()));
		PyModule m = loaded.modules().get("m");
		assertNotSame(vm.modules().get("m"), m);
		assertTrue(m.loaded());
		assertSame(m, loaded.globals().get("m"));
		assertInstanceOf(PyList.class, m.globals().get("x"));
		assertEquals("[1]", Values.repr(m.globals().get("x")));
	}

	@Test
	void savingTwiceGivesTheSameBytes() throws Exception {
		Vm vm = vm("d = {'a': [1, 2]}\nfor k in d:\n    while True:\n        pass\n", Map.of(), new ArrayList<>());
		vm.run(30);
		assertArrayEquals(VmState.save(vm, THINGS), VmState.save(vm, THINGS));
	}
}
