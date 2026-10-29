package io.github.barniebarnie.antcraft.script.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code import} and {@code from ... import} of modules of the script library (docs/language/index.md, Modules). */
class ImportTest {
	/** Modules by name, compiled from source on import; a name's file is its path, {@code lib.trees} in {@code lib/trees.py}. */
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

	private static Vm vm(String source, Map<String, String> modules, List<String> printed) {
		return new Vm("t", Compiler.compile(Parser.parse("t.py", source)), Natives.standard(Output.lines(printed::add)),
				new Library(modules));
	}

	private static String run(String source, Map<String, String> modules) {
		List<String> printed = new ArrayList<>();
		Vm vm = vm(source, modules, printed);
		Vm.Status status = vm.run(1_000_000);
		if (status == Vm.Status.FAILED) {
			throw vm.error();
		}
		assertEquals(Vm.Status.FINISHED, status);
		return String.join("\n", printed);
	}

	private static String fails(String source, Map<String, String> modules) {
		Vm vm = vm(source, modules, new ArrayList<>());
		assertEquals(Vm.Status.FAILED, vm.run(1_000_000));
		return vm.error().getMessage();
	}

	@Test
	void importBindsTheModuleWithItsNames() {
		Map<String, String> modules = Map.of("trees", "count = 3\ndef double(n):\n    return 2 * n\n");
		assertEquals("3 6 <module 'trees'>", run("import trees\nprint(trees.count, trees.double(3), trees)", modules));
	}

	@Test
	void fromImportBindsTheNamesGiven() {
		Map<String, String> modules = Map.of("trees", "count = 3\ndef double(n):\n    return 2 * n\n");
		assertEquals("3 8", run("from trees import count, double\nprint(count, double(4))", modules));
	}

	@Test
	void nestedModulesAreReachedThroughTheirPackage() {
		Map<String, String> modules = Map.of("lib.trees", "count = 3\n", "lib.shaft", "depth = 12\n");
		assertEquals("3", run("import lib.trees\nprint(lib.trees.count)", modules));
		assertEquals("3", run("from lib.trees import count\nprint(count)", modules));
		assertEquals("12 3", run("from lib import shaft, trees\nprint(shaft.depth, trees.count)", modules));
		assertEquals("3 12", run("import lib.trees, lib.shaft\nprint(lib.trees.count, lib.shaft.depth)", modules));
	}

	@Test
	void anImportInAFunctionBindsALocal() {
		Map<String, String> modules = Map.of("trees", "count = 3\n");
		String source = "def f():\n    import trees\n    return trees.count\nprint(f())\n";
		assertEquals("3", run(source, modules));
		assertEquals("t.py:5: name 'trees' is not defined", fails(source + "print(trees)\n", modules));
	}

	@Test
	void eachModuleHasItsOwnGlobalsAndAFunctionSeesThoseOfItsModule() {
		Map<String, String> modules = Map.of("counter", """
				x = 1
				def get():
				    return x
				def set(value):
				    global x
				    x = value
				""");
		assertEquals("1\n5 5 99", run("""
				x = 99
				import counter
				print(counter.get())
				counter.set(5)
				print(counter.get(), counter.x, x)
				""", modules));
	}

	@Test
	void aModuleSeesTheNativesAndTheBuiltinValuesOfTheRun() {
		List<String> printed = new ArrayList<>();
		Vm vm = vm("import helper\nhelper.show()", Map.of("helper", "def show():\n    print(len(memory), memory['a'])\n"),
				printed);
		PyDict memory = new PyDict();
		memory.put("a", 1L);
		vm.setBuiltin("memory", memory);
		assertEquals(Vm.Status.FINISHED, vm.run(1_000_000));
		assertEquals(List.of("1 1"), printed);
	}

	@Test
	void aModulesTopLevelRunsOncePerRun() {
		Map<String, String> modules = Map.of("a", "print('loading a')\nvalue = 1\n", "b", "import a\nvalue = a.value + 1\n");
		assertEquals("loading a\n2 1", run("import a\nimport b\nimport a\nfrom a import value\nprint(b.value, value)", modules));
	}

	@Test
	void theRunKeepsEachModuleAndItsGlobalsByName() {
		List<String> printed = new ArrayList<>();
		Vm vm = vm("import lib.trees\nx = 2", Map.of("lib.trees", "count = 3\n"), printed);
		assertEquals(Vm.Status.FINISHED, vm.run(1_000_000));
		assertEquals(List.of("t", "lib", "lib.trees"), List.copyOf(vm.modules().keySet()));
		assertEquals(3L, vm.modules().get("lib.trees").globals().get("count"));
		assertSame(vm.modules().get("lib.trees"), vm.modules().get("lib").globals().get("trees"));
		assertEquals(2L, vm.globals().get("x"));
	}

	@Test
	void aCircularImportFailsNamingTheCycle() {
		Map<String, String> modules = Map.of("a", "import b\n", "b", "x = 1\nimport a\n");
		assertEquals("b.py:2: circular import: a -> b -> a", fails("import a", modules));
		assertEquals("a.py:1: circular import: t -> a -> t", fails("import a", Map.of("a", "import t\n")));
	}

	@Test
	void aMissingModuleFailsAtTheImportsLine() {
		assertEquals("t.py:2: No module named 'nothing'", fails("x = 1\nimport nothing", Map.of()));
		assertEquals("t.py:1: No module named 'lib.nothing'", fails("import lib.nothing", Map.of("lib.trees", "")));
		assertEquals("t.py:1: cannot import name 'nope' from 'trees'", fails("from trees import nope",
				Map.of("trees", "count = 1\n")));
	}

	@Test
	void withoutALibraryEveryImportIsMissing() {
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", "x = 1\nimport lib.trees")), Natives.standard((level, line) -> {
		}));
		assertEquals(Vm.Status.FAILED, vm.run(1000));
		assertEquals("t.py:2: No module named 'lib'", vm.error().getMessage());
	}

	@Test
	void aModuleThatDoesNotCompileFailsWithItsOwnFileAndLine() {
		Map<String, String> modules = Map.of("lib.bad", "x = 1\nif True\n    pass\n");
		assertEquals("lib/bad.py:2: expected ':' after 'if' condition", fails("x = 1\n\nimport lib.bad", modules));
	}

	@Test
	void anErrorInAModuleIsPlacedInItsFile() {
		Map<String, String> modules = Map.of("a", "def f():\n    return 1 / 0\n", "b", "x = 1\ny = [][0]\n");
		assertTrue(fails("import a\na.f()", modules).startsWith("a.py:2: "), fails("import a\na.f()", modules));
		assertTrue(fails("import b", modules).startsWith("b.py:2: "), fails("import b", modules));
		assertEquals("t.py:2: module 'a' has no attribute 'g'", fails("import a\na.g()", modules));
	}
}
