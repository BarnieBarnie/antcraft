package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A script run on a bot, one server tick at a time, with a host standing in for the bot. */
class ScriptRunTest {
	/** The bot: a position, the game time, and what reached its owner's chat. */
	private static final class FakeHost implements ScriptRun.Host {
		long time;
		Pos pos = new Pos(4, 64, -2);
		Level chatLevel = Level.ERROR;
		final List<String> chat = new ArrayList<>();

		@Override
		public long gameTime() {
			return time;
		}

		@Override
		public Pos pos() {
			return pos;
		}

		@Override
		public Level chatLevel() {
			return chatLevel;
		}

		@Override
		public void chat(Level level, String line) {
			chat.add(time + " " + level + " " + line);
		}

		@Override
		public void say(String text) {
			chat.add(time + " say " + text);
		}
	}

	private final FakeHost host = new FakeHost();
	private final BotLog log = new BotLog(100);

	private ScriptRun start(String source, String... args) {
		return new ScriptRun("t", Compiler.compile(Parser.parse("t.py", source)), List.of(args), host, log);
	}

	/** Runs one server tick at game time {@link FakeHost#time}, then moves the time on. */
	private void tick(ScriptRun run, long budget) {
		run.tick(budget);
		host.time++;
	}

	private List<String> logLines() {
		return log.last(100, Level.DEBUG).stream().map(BotLog.Entry::format).toList();
	}

	@Test
	void saysWaitsAndPrintsAtTheRightTicks() {
		ScriptRun run = start("say('hi')\nwait(3)\nprint(pos())\n");
		for (int i = 0; i < 3; i++) {
			tick(run, 2000);
			assertEquals(ScriptRun.State.RUNNING, run.state());
		}
		assertEquals(List.of("[0 print] hi"), logLines());
		tick(run, 2000);
		assertEquals(ScriptRun.State.FINISHED, run.state());
		assertEquals(List.of("[0 print] hi", "[3 print] Pos(4, 64, -2)"), logLines());
		assertEquals(List.of("0 say hi", "3 PRINT Pos(4, 64, -2)"), host.chat);
	}

	@Test
	void waitShowsAsTheCurrentActionAtItsLine() {
		ScriptRun run = start("x = 1\nwait(20)\n");
		tick(run, 2000);
		assertEquals("wait(20)", run.action());
		assertEquals(2, run.line());
	}

	@Test
	void anEndlessLoopUsesItsBudgetEachTickAndKeepsRunning() {
		ScriptRun run = start("while True:\n    pass\n");
		for (int i = 0; i < 3; i++) {
			tick(run, 500);
		}
		assertEquals(ScriptRun.State.RUNNING, run.state());
		assertEquals(1500, run.executed());
		assertNull(run.action());
	}

	@Test
	void aRuntimeErrorStopsTheScriptAndReachesTheLogAndTheOwner() {
		ScriptRun run = start("print('before')\nx = None\nx.y\nprint('after')\n");
		tick(run, 2000);
		assertEquals(ScriptRun.State.FAILED, run.state());
		assertEquals(List.of("[0 print] before", "[0 error] t.py:3: 'NoneType' object has no attribute 'y'"), logLines());
		assertEquals(List.of("0 PRINT before", "0 ERROR t.py:3: 'NoneType' object has no attribute 'y'"), host.chat);
		tick(run, 2000);
		assertEquals(2, logLines().size());
	}

	@Test
	void linesBelowTheChatLevelOnlyGoToTheLog() {
		ScriptRun run = start("debug('d')\ninfo('i')\nwarn('w')\nerror('e')\n");
		host.chatLevel = Level.WARN;
		tick(run, 2000);
		assertEquals(List.of("[0 debug] d", "[0 info] i", "[0 warn] w", "[0 error] e"), logLines());
		assertEquals(List.of("0 WARN w", "0 ERROR e"), host.chat);
		assertEquals(ScriptRun.State.FINISHED, run.state());
	}

	@Test
	void stopEndsTheScript() {
		ScriptRun run = start("print(1)\nstop()\nprint(2)\n");
		tick(run, 2000);
		assertEquals(ScriptRun.State.STOPPED, run.state());
		tick(run, 2000);
		assertEquals(List.of("[0 print] 1"), logLines());
	}

	@Test
	void stoppingFromOutsideEndsAWait() {
		ScriptRun run = start("wait(100)\nprint('late')\n");
		tick(run, 2000);
		run.stop();
		assertEquals(ScriptRun.State.STOPPED, run.state());
		assertNull(run.action());
		for (int i = 0; i < 200; i++) {
			tick(run, 2000);
		}
		assertEquals(List.of(), logLines());
	}

	@Test
	void argsAreAListOfStrings() {
		ScriptRun run = start("print(ARGS)\nprint(len(ARGS))\n", "oak", "3");
		tick(run, 2000);
		assertEquals(List.of("[0 print] ['oak', '3']", "[0 print] 2"), logLines());
	}

	@Test
	void waitTakesAWholeNumberOfTicks() {
		ScriptRun run = start("wait('soon')\n");
		tick(run, 2000);
		assertEquals(ScriptRun.State.FAILED, run.state());
		assertEquals(List.of("[0 error] t.py:1: wait() takes a number of ticks (int), not 'str'"), logLines());
		ScriptRun zero = start("wait(0)\nwait(-5)\nprint('now')\n");
		tick(zero, 2000);
		assertEquals(ScriptRun.State.FINISHED, zero.state());
	}

	@Test
	void memoryOutlivesTheRunThatFilledIt() {
		PyDict memory = Memory.create();
		ScriptRun first = new ScriptRun("t", Compiler.compile(Parser.parse("t.py", "memory['runs'] = memory.get('runs', 0) + 1\n")), List.of(), host, log, memory);
		tick(first, 2000);
		ScriptRun second = new ScriptRun("t", Compiler.compile(Parser.parse("t.py", "memory['runs'] = memory.get('runs', 0) + 1\nprint(memory)\n")), List.of(), host, log, memory);
		tick(second, 2000);
		assertEquals(ScriptRun.State.FINISHED, second.state());
		assertEquals(List.of("[1 print] {'runs': 2}"), logLines());
	}

	@Test
	void storingANonPlainValueInMemoryFailsTheScriptAtTheLine() {
		ScriptRun run = start("x = 1\nmemory['f'] = print\nprint('after')\n");
		tick(run, 2000);
		assertEquals(ScriptRun.State.FAILED, run.state());
		assertEquals(List.of("[0 error] t.py:2: memory holds only plain data (numbers, strings, booleans, None, Pos, and lists, "
			+ "tuples and dicts of these), not 'builtin_function_or_method'"), logLines());
	}
}
