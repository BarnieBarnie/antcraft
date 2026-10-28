package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.lang.Code;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A bot's running script saved with the bot and resumed when it loads (docs/scripting.md, Save and resume): it goes on
 * at the same instruction, inside the same action, unless its code changed, when it starts again from the top.
 */
class ScriptSaveTest {
	/** The bot: a position, the game time, and what it said. */
	private static final class FakeHost implements ScriptRun.Host {
		long time;
		final List<String> chat = new ArrayList<>();

		@Override
		public long gameTime() {
			return time;
		}

		@Override
		public Pos pos() {
			return new Pos(4, 64, -2);
		}

		@Override
		public Level chatLevel() {
			return Level.ERROR;
		}

		@Override
		public void chat(Level level, String line) {
			chat.add(time + " " + line);
		}

		@Override
		public void say(String text) {
			chat.add(time + " say " + text);
		}
	}

	@TempDir
	Path folder;

	private ScriptLibrary library(String... files) throws IOException {
		for (int i = 0; i < files.length; i += 2) {
			Path file = folder.resolve(files[i]);
			Files.createDirectories(file.getParent());
			Files.writeString(file, files[i + 1]);
		}
		return ScriptLibrary.load(List.of(folder));
	}

	private static List<String> lines(BotLog log) {
		return log.last(BotLog.CAPACITY, Level.DEBUG).stream().map(BotLog.Entry::format).toList();
	}

	private static final String SCRIPT = """
			import lib.walk
			from lib.walk import steps
			memory['runs'] = memory.get('runs', 0) + 1
			say('start')
			def height(q):
			    return -q.y
			seen = {}
			for i, p in enumerate(steps(pos(), 3)):
			    seen[p] = i
			    wait(2)
			    print(i, p, sorted(seen.keys(), key=height))
			r = wait(1)
			print(r, r.ok, [v for v in seen.values()], memory)
			""";

	private static final String WALK = "def steps(start, n):\n    return [start.up(i) for i in range(n)]\n";

	/** Runs the library's {@code miner} for {@code ticks} ticks, or to its end; gives the run. */
	private static ScriptRun runFor(ScriptRun run, FakeHost host, int ticks, long budget) {
		for (int i = 0; i < ticks && run.running(); i++) {
			run.tick(budget);
			host.time++;
		}
		return run;
	}

	private static ScriptRun.Saved roundTrip(ScriptRun.Saved saved) {
		TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
		saved.store(output);
		return ScriptRun.Saved.read(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, output.buildResult()))
				.orElseThrow();
	}

	@Test
	void aRunSavedAndResumedAtAnyTickEndsAsOneWithoutABreak() throws IOException {
		ScriptLibrary library = library("miner.py", SCRIPT, "lib/walk.py", WALK);
		Code code = library.get("miner").orElseThrow().code();
		for (long budget : new long[] {5, 2000}) {
			FakeHost host = new FakeHost();
			BotLog log = new BotLog(100);
			runFor(new ScriptRun("miner", code, List.of(), host, log, Memory.create(), library), host, 100, budget);
			List<String> expected = lines(log);
			long end = host.time;
			assertTrue(expected.getLast().contains("Result(ok=True) True [0, 1, 2] {'runs': 1}"), expected.toString());
			for (int at = 0; at < end; at++) {
				FakeHost resumedHost = new FakeHost();
				BotLog resumedLog = new BotLog(100);
				PyDict memory = Memory.create();
				ScriptRun first = runFor(new ScriptRun("miner", code, List.of(), resumedHost, resumedLog, memory, library),
						resumedHost, at, budget);
				ScriptRun.Saved saved = roundTrip(first.save());
				assertNull(saved.problem(), saved.problem());
				ScriptLibrary reloaded = ScriptLibrary.load(List.of(folder));
				ScriptRun resumed = ScriptRun.resume(saved, reloaded.get("miner").orElseThrow().code(), resumedHost, resumedLog,
						Memory.create(), reloaded);
				runFor(resumed, resumedHost, 100, budget);
				assertEquals(ScriptRun.State.FINISHED, resumed.state(), "saved at tick " + at + ": " + lines(resumedLog));
				assertEquals(expected, lines(resumedLog), "saved at tick " + at + " with a budget of " + budget);
				assertEquals(end, resumedHost.time, "saved at tick " + at);
			}
		}
	}

	@Test
	void aResumedRunKeepsTheMemoryItHadWithItsVariables() throws IOException {
		ScriptLibrary library = library("keep.py", "l = [1]\nmemory['l'] = l\nwait(5)\nl.append(2)\nprint(memory)\n");
		FakeHost host = new FakeHost();
		BotLog log = new BotLog(100);
		ScriptRun run = runFor(new ScriptRun("keep", library.load("keep"), List.of(), host, log, Memory.create(), library), host, 2, 2000);
		ScriptRun resumed = ScriptRun.resume(roundTrip(run.save()), library.load("keep"), host, log, Memory.create(), library);
		runFor(resumed, host, 10, 2000);
		assertEquals("[5 print] {'l': [1, 2]}", lines(log).getLast());
		assertEquals("{'l': [1, 2]}", Values.repr(resumed.memory()));
	}

	@Test
	void aChangedScriptStartsAgainFromTheTopKeepingMemory() throws IOException {
		ScriptLibrary library = library("count.py", "memory['n'] = memory.get('n', 0) + 1\nprint('one', memory['n'])\nwait(10)\nprint('end')\n");
		FakeHost host = new FakeHost();
		BotLog log = new BotLog(100);
		PyDict memory = Memory.create();
		ScriptRun run = runFor(new ScriptRun("count", library.load("count"), List.of(), host, log, memory, library), host, 3, 2000);
		ScriptRun.Saved saved = roundTrip(run.save());
		ScriptLibrary changed = library("count.py", "memory['n'] = memory.get('n', 0) + 1\nprint('two', memory['n'])\nwait(10)\nprint('end')\n");
		ScriptRun resumed = ScriptRun.resume(saved, changed.load("count"), host, log, memory, changed);
		runFor(resumed, host, 1, 2000);
		assertEquals(List.of("[0 print] one 1", "[3 info] count.py changed since the save: count starts again from the top",
				"[3 print] two 2"), lines(log));
		assertTrue(resumed.running());
	}

	@Test
	void aChangedModuleStartsTheScriptAgainFromTheTop() throws IOException {
		ScriptLibrary library = library("m.py", "import lib.a\nprint('start')\nwait(10)\n", "lib/a.py", "x = 1\n");
		FakeHost host = new FakeHost();
		BotLog log = new BotLog(100);
		ScriptRun run = runFor(new ScriptRun("m", library.load("m"), List.of(), host, log, Memory.create(), library), host, 3, 2000);
		ScriptRun.Saved saved = roundTrip(run.save());
		ScriptLibrary changed = library("lib/a.py", "x = 2\n");
		runFor(ScriptRun.resume(saved, changed.load("m"), host, log, Memory.create(), changed), host, 1, 2000);
		assertEquals(List.of("[0 print] start", "[3 info] lib/a.py changed since the save: m starts again from the top",
				"[3 print] start"), lines(log));
	}

	@Test
	void aSaveWithoutTheScriptsStateStartsItFromTheTop() throws IOException {
		ScriptLibrary library = library("w.py", "print(ARGS)\nwait(10)\n");
		FakeHost host = new FakeHost();
		BotLog log = new BotLog(100);
		ScriptRun.Saved old = new ScriptRun.Saved("w", List.of("a", "b"), null, 0, null, null);
		ScriptRun run = ScriptRun.resume(roundTrip(old), library.load("w"), host, log, Memory.create(), library);
		runFor(run, host, 1, 2000);
		assertEquals(List.of("[0 info] w starts again from the top", "[0 print] ['a', 'b']"), lines(log));
		assertEquals(List.of("a", "b"), run.args());
	}

	@Test
	void waitGoesOnWithTheTicksItHadLeft() throws IOException {
		ScriptLibrary library = library("w.py", "wait(10)\nprint('done')\n");
		FakeHost host = new FakeHost();
		BotLog log = new BotLog(100);
		ScriptRun run = runFor(new ScriptRun("w", library.load("w"), List.of(), host, log, Memory.create(), library), host, 4, 2000);
		assertEquals("wait(10)", run.action());
		ScriptRun resumed = ScriptRun.resume(roundTrip(run.save()), library.load("w"), host, log, Memory.create(), library);
		assertEquals(1, resumed.line());
		runFor(resumed, host, 20, 2000);
		assertEquals(List.of("[10 print] done"), lines(log));
	}

	@Test
	void aResumedRunThatIsSavedAgainBeforeItTicksKeepsItsAction() throws IOException {
		ScriptLibrary library = library("w.py", "wait(10)\nprint('done')\n");
		FakeHost host = new FakeHost();
		BotLog log = new BotLog(100);
		ScriptRun run = runFor(new ScriptRun("w", library.load("w"), List.of(), host, log, Memory.create(), library), host, 4, 2000);
		ScriptRun once = ScriptRun.resume(roundTrip(run.save()), library.load("w"), host, log, Memory.create(), library);
		ScriptRun twice = ScriptRun.resume(roundTrip(once.save()), library.load("w"), host, log, Memory.create(), library);
		runFor(twice, host, 20, 2000);
		assertEquals(List.of("[10 print] done"), lines(log));
	}

	@Test
	void valuesOfTheBotApiAreSavedAsPlainData() {
		SavedValues values = new SavedValues(uuid -> null);
		for (Object value : List.of(new Pos(1, -2, 3), new Stack("minecraft:dirt", 3),
				new BlockInfo("minecraft:oak_log", new Pos(1, 2, 3), false, false, List.of("#minecraft:logs")),
				Result.ok(4L), Result.fail(Result.UNREACHABLE))) {
			Object saved = values.save(value);
			assertNotNull(saved, value.toString());
			assertEquals(value, values.load(saved));
		}
		Pos nether = (Pos) values.load(values.save(new Pos(1, 2, 3, "minecraft:the_nether")));
		assertEquals("minecraft:the_nether", nether.dimension());
		assertEquals(new Pos(1, 2, 3), values.load(PyTuple.of(Pos.TYPE, 1L, 2L, 3L)), "a Pos saved before #349");
		assertNull(((Pos) values.load(PyTuple.of(Pos.TYPE, 1L, 2L, 3L))).dimension());
		UUID id = UUID.randomUUID();
		EntityInfo gone = (EntityInfo) values.load(values.save(EntityInfo.saved(id, "minecraft:cow", uuid -> null)));
		assertFalse(gone.valid());
		assertEquals(id.toString(), gone.attribute("id"));
		assertEquals("minecraft:cow", gone.attribute("type"));
		assertEquals(EntityInfo.saved(id, "minecraft:pig", uuid -> null), gone);
	}
}
