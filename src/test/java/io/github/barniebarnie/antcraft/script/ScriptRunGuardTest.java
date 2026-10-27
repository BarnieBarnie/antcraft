package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A Java exception inside a native or an action stops the script with an error, rather than escaping the bot's tick. */
class ScriptRunGuardTest {
	private static final class BrokenHost implements ScriptRun.Host {
		final List<String> chat = new ArrayList<>();
		boolean brokenPos = true;

		@Override
		public long gameTime() {
			return 7;
		}

		@Override
		public Pos pos() {
			if (brokenPos) {
				throw new IllegalStateException("no world");
			}
			return new Pos(0, 0, 0);
		}

		@Override
		public Level chatLevel() {
			return Level.ERROR;
		}

		@Override
		public void chat(Level level, String line) {
			chat.add(line);
		}

		@Override
		public void say(String text) {
		}

		@Override
		public MoveApi.Body body() {
			// Every call fails: the action, not the native, runs into it.
			return (MoveApi.Body) java.lang.reflect.Proxy.newProxyInstance(MoveApi.Body.class.getClassLoader(), new Class<?>[] {MoveApi.Body.class},
				(proxy, method, args) -> {
					throw new UnsupportedOperationException("no legs");
				});
		}
	}

	private final BrokenHost host = new BrokenHost();
	private final BotLog log = new BotLog(100);

	private List<String> errors() {
		return log.last(100, Level.ERROR).stream().map(BotLog.Entry::text).toList();
	}

	@Test
	void anExceptionInANativeStopsTheScriptAtItsLine() {
		ScriptRun run = new ScriptRun("t", Compiler.compile(Parser.parse("t.py", "x = 1\np = pos()\nprint('after')\n")), List.of(), host, log);
		run.tick(2000);
		assertEquals(ScriptRun.State.FAILED, run.state());
		List<String> expected = List.of("t.py:2: internal error: java.lang.IllegalStateException: no world");
		assertEquals(expected, errors());
		assertEquals(expected, host.chat);
	}

	@Test
	void anExceptionInAnActionStopsTheScriptAtItsLine() {
		host.brokenPos = false;
		ScriptRun run = new ScriptRun("t", Compiler.compile(Parser.parse("t.py", "x = 1\n\ngoto((1, 2, 3))\nprint('after')\n")), List.of(), host, log);
		run.tick(2000);
		run.tick(2000);
		assertEquals(ScriptRun.State.FAILED, run.state());
		assertEquals(List.of("t.py:3: internal error: java.lang.UnsupportedOperationException: no legs"), errors());
	}
}
