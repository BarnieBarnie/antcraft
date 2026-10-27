package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import io.github.barniebarnie.antcraft.script.lang.Vm;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.Test;

/** A bot's {@code memory} (docs/scripting.md, Memory): plain data only, saved with the bot. */
class MemoryTest {
	/** Runs {@code source} with {@code memory} as the global {@code memory}; gives what it printed, or its error. */
	private static String run(PyDict memory, String source) {
		List<String> printed = new ArrayList<>();
		Natives natives = Natives.standard(Output.lines(printed::add));
		Pos.register(natives);
		Vm vm = new Vm(Compiler.compile(Parser.parse("t.py", source)), natives);
		vm.setGlobal("memory", memory);
		vm.setGlobal("p", new Pos(1, 2, 3));
		if (vm.run(100_000) == Vm.Status.FAILED) {
			return "error: " + vm.error().getMessage();
		}
		return String.join("\n", printed);
	}

	/** Saves {@code memory} as the bot's save data does, and loads it back. */
	private static PyDict roundTrip(PyDict memory, List<String> problems) {
		TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
		Memory.save(memory, output, problems::add);
		CompoundTag saved = output.buildResult();
		return Memory.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, saved));
	}

	@Test
	void roundTripsThroughTheBotsSaveData() {
		PyDict memory = Memory.create();
		assertEquals("", run(memory, """
			memory['n'] = 3
			memory['big'] = -9223372036854775807 - 1
			memory['f'] = 0.1
			memory['s'] = 'chest'
			memory['yes'] = True
			memory['nothing'] = None
			memory['home'] = p
			memory['path'] = [p, p.up(), (1, 'a', [2.5])]
			memory['by_pos'] = {p: 'ore', 7: [], True: {}}
			memory[p.down()] = ('t',)
			"""));
		List<String> problems = new ArrayList<>();
		PyDict loaded = roundTrip(memory, problems);
		assertEquals(List.of(), problems);
		assertEquals(Values.repr(memory), Values.repr(loaded));
		assertEquals("{'n': 3, 'big': -9223372036854775808, 'f': 0.1, 's': 'chest', 'yes': True, 'nothing': None, "
			+ "'home': Pos(1, 2, 3), 'path': [Pos(1, 2, 3), Pos(1, 3, 3), (1, 'a', [2.5])], "
			+ "'by_pos': {Pos(1, 2, 3): 'ore', 7: [], True: {}}, Pos(1, 1, 3): ('t',)}", Values.repr(loaded));
		// The loaded memory still only takes plain data.
		assertEquals("error: t.py:1: memory holds only plain data (numbers, strings, booleans, None, Pos, and lists, "
			+ "tuples and dicts of these), not 'builtin_function_or_method'", run(loaded, "memory['f'] = len\n"));
	}

	@Test
	void anEmptyMemoryLoadsEmpty() {
		assertEquals("{}", Values.repr(roundTrip(Memory.create(), new ArrayList<>())));
		assertEquals("{}", Values.repr(Memory.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, new CompoundTag()))));
	}

	@Test
	void storingAFunctionFailsAtTheLineOfTheStore() {
		PyDict memory = Memory.create();
		assertEquals("error: t.py:3: memory holds only plain data (numbers, strings, booleans, None, Pos, and lists, "
			+ "tuples and dicts of these), not 'function'", run(memory, "def f():\n    pass\nmemory['f'] = f\n"));
		assertEquals("{}", Values.repr(memory));
	}

	@Test
	void storingAContainerHoldingANonPlainValueFailsAtTheLineOfTheStore() {
		PyDict memory = Memory.create();
		assertEquals("error: t.py:2: memory holds only plain data (numbers, strings, booleans, None, Pos, and lists, "
			+ "tuples and dicts of these), not 'builtin_function_or_method'", run(memory, "x = 1\nmemory['l'] = [1, {'k': (len,)}]\n"));
		assertEquals("error: t.py:1: memory holds only plain data (numbers, strings, booleans, None, Pos, and lists, "
			+ "tuples and dicts of these), not 'range'", run(memory, "memory.setdefault('r', range(3))\n"));
		assertEquals("{}", Values.repr(memory));
	}

	@Test
	void aListHoldingItselfCannotBeStored() {
		PyDict memory = Memory.create();
		assertEquals("error: t.py:3: memory cannot hold a list or dict that contains itself", run(memory, "l = []\nl.append(l)\nmemory['l'] = l\n"));
	}

	@Test
	void aNonPlainValueAddedAfterTheStoreIsLeftOutOfTheSaveAndReported() {
		PyDict memory = Memory.create();
		assertEquals("", run(memory, "memory['ok'] = 1\nmemory['l'] = []\nmemory['l'].append(len)\n"));
		List<String> problems = new ArrayList<>();
		PyDict loaded = roundTrip(memory, problems);
		assertEquals("{'ok': 1}", Values.repr(loaded));
		assertEquals(List.of("memory['l'] was not saved: memory holds only plain data (numbers, strings, booleans, None, "
			+ "Pos, and lists, tuples and dicts of these), not 'builtin_function_or_method'"), problems);
	}

	@Test
	void checksPlainData() {
		Memory.checkPlain(new Pos(1, 2, 3));
		ScriptError error = assertThrows(ScriptError.class, () -> Memory.checkPlain(new Stack("minecraft:dirt", 3)));
		assertEquals("memory holds only plain data (numbers, strings, booleans, None, Pos, and lists, tuples and dicts of these), not 'Stack'",
			error.getMessage());
	}
}
