package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code goto}, {@code home} and {@code set_home} and their {@code Result}s, with a body standing in for the bot. */
class MoveApiTest {
	/** A bot whose path follower and chunks the test drives. */
	private static final class FakeBot implements ScriptRun.Host, MoveApi.Body {
		long time;
		Pos pos = new Pos(0, 64, 0);
		Pos home = new Pos(0, 64, 0);
		/** The chunks up to this x are loaded; those east of it not. */
		int loadedUpToX = Integer.MAX_VALUE;
		/** Chunks asked to load, in order. */
		final List<String> loadRequests = new ArrayList<>();
		final List<String> paths = new ArrayList<>();
		MoveApi.PathStatus pathStatus = MoveApi.PathStatus.RUNNING;
		boolean pathStopped;
		/** Plans asked for, as target and near. */
		final List<String> plans = new ArrayList<>();
		/** How many nodes the next plan expands before it is done, and whether it then found a path. */
		int planNodes = 10;
		boolean planFound = true;

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
			return Level.ERROR;
		}

		@Override
		public void chat(Level level, String line) {
		}

		@Override
		public void say(String text) {
		}

		@Override
		public MoveApi.Body body() {
			return this;
		}

		@Override
		public Pos home() {
			return home;
		}

		@Override
		public void setHome(Pos home) {
			this.home = home;
		}

		@Override
		public boolean chunkLoaded(int x, int z) {
			return x <= loadedUpToX;
		}

		@Override
		public void loadChunk(int x, int z) {
			loadRequests.add(x + "," + z);
		}

		@Override
		public void startPath(Pos target, int near) {
			paths.add(target + " near " + near);
		}

		@Override
		public void startReach(Pos target) {
			paths.add(target + " in reach");
		}

		@Override
		public boolean inReach(Pos target) {
			return false;
		}

		@Override
		public MoveApi.PathStatus tickPath() {
			return pathStatus;
		}

		@Override
		public void stopPath() {
			pathStopped = true;
		}

		@Override
		public MoveApi.Plan plan(Pos target, int near) {
			plans.add(target + " near " + near);
			int nodes = planNodes;
			boolean found = planFound;
			return new MoveApi.Plan() {
				int expanded;

				/** A tick's share of the path budget: all of it, as no other bot plans. */
				@Override
				public int run() {
					int ran = Math.min(PathBudget.NODES_PER_TICK, nodes - expanded);
					expanded += ran;
					return ran;
				}

				@Override
				public boolean done() {
					return expanded >= nodes;
				}

				@Override
				public boolean found() {
					return found;
				}
			};
		}
	}

	private final FakeBot bot = new FakeBot();
	private final BotLog log = new BotLog(100);

	@AfterEach
	void restoreConfig() {
		AntCraftConfig.setValues(new AntCraftConfig.Values(false, 64));
	}

	private ScriptRun start(String source) {
		return new ScriptRun("t", Compiler.compile(Parser.parse("t.py", source)), List.of(), bot, log);
	}

	private void tick(ScriptRun run) {
		run.tick(2000);
		bot.time++;
	}

	private List<String> printed() {
		return log.last(100, Level.PRINT).stream().map(BotLog.Entry::text).toList();
	}

	private static void loadChunks(boolean on) {
		AntCraftConfig.Values d = new AntCraftConfig.Values(true, 64);
		AntCraftConfig.setValues(new AntCraftConfig.Values(d.enableOnDedicatedServer(), d.maxBotsPerLevel(), d.colonyActionsRequireOp(),
			d.scriptInstructionsPerTick(), d.scriptChatLevel(), on));
	}

	@Test
	void resultsPrintAndTestAsTheirOutcome() {
		ScriptRun run = start("""
			r = wait(1)
			print(r, r.ok, r.reason, r.value, bool(r))
			if not goto((0, 70, 0)):
			    print('failed')
			""");
		bot.pathStatus = MoveApi.PathStatus.FAILED;
		for (int i = 0; i < 4; i++) {
			tick(run);
		}
		assertEquals(ScriptRun.State.FINISHED, run.state());
		assertEquals(List.of("Result(ok=True) True None None True", "failed"), printed());
	}

	@Test
	void gotoArrivesAndReturnsOk() {
		ScriptRun run = start("r = goto((3, 64, 4), near=1)\nprint(r.ok, r.reason)\n");
		tick(run);
		assertEquals("goto(Pos(3, 64, 4), near=1)", run.action());
		tick(run);
		assertEquals(List.of("Pos(3, 64, 4) near 1"), bot.paths);
		assertEquals(ScriptRun.State.RUNNING, run.state());
		bot.pathStatus = MoveApi.PathStatus.ARRIVED;
		tick(run);
		assertEquals(List.of("True None"), printed());
	}

	@Test
	void gotoWherePathsGiveUpIsUnreachableAndLoggedAtDebug() {
		ScriptRun run = start("r = goto((5, 64, 5))\nprint(r.reason)\n");
		bot.pathStatus = MoveApi.PathStatus.FAILED;
		tick(run);
		tick(run);
		assertEquals(List.of("unreachable"), printed());
		assertTrue(log.last(100, Level.DEBUG).stream().anyMatch(e -> e.level() == Level.DEBUG && e.text().equals("goto(Pos(5, 64, 5)) -> unreachable")),
			"log " + log.last(100, Level.DEBUG));
	}

	/** {@code botsLoadChunks = false}: the gate on loading chunks, tested here because game tests must not toggle global config. */
	@Test
	void withoutChunkLoadingGotoRefusesAnUnloadedChunk() {
		loadChunks(false);
		bot.loadedUpToX = 0;
		ScriptRun run = start("r = goto((100, 64, 0))\nprint(r.ok, r.reason)\n");
		for (int i = 0; i < 3; i++) {
			tick(run);
		}
		assertEquals(List.of("False not_loaded"), printed());
		assertEquals(List.of(), bot.loadRequests);
		assertEquals(List.of(), bot.paths);
	}

	@Test
	void withChunkLoadingGotoLoadsTheNextStretchBeforePlanning() {
		loadChunks(true);
		bot.loadedUpToX = 0;
		ScriptRun run = start("r = goto((100, 64, 0))\nprint(r.ok, r.reason)\n");
		tick(run);
		tick(run);
		// The next stretch, up to 48 blocks on, crosses chunks 0 to 3; with those within 3 of them, chunks 1 to 6 east are
		// asked to load, and no path until they are.
		assertTrue(bot.loadRequests.containsAll(List.of("1,0", "3,0", "6,3", "6,-3")), "asked " + bot.loadRequests);
		assertTrue(bot.loadRequests.stream().noneMatch(c -> c.startsWith("7,") || c.endsWith(",4") || c.endsWith(",-4")),
			"asked beyond the stretch: " + bot.loadRequests);
		assertEquals(List.of(), bot.paths);
		bot.loadedUpToX = 6;
		tick(run);
		assertEquals(List.of("Pos(100, 64, 0) near 0"), bot.paths);
		bot.pathStatus = MoveApi.PathStatus.ARRIVED;
		tick(run);
		assertEquals(List.of("True None"), printed());
	}

	@Test
	void aChunkThatNeverLoadsEndsGotoWithNotLoaded() {
		loadChunks(true);
		bot.loadedUpToX = 0;
		ScriptRun run = start("print(goto((100, 64, 0)).reason)\n");
		for (int i = 0; i <= MoveApi.LOAD_WAIT_TICKS + 2; i++) {
			tick(run);
		}
		assertEquals(List.of("not_loaded"), printed());
	}

	@Test
	void gotoThatGetsNoCloserTimesOut() {
		ScriptRun run = start("print(goto((5, 64, 5)).reason)\n");
		for (int i = 0; i <= MoveApi.STALL_TICKS + 2; i++) {
			tick(run);
		}
		assertEquals(List.of("timeout"), printed());
		assertTrue(bot.pathStopped, "path not stopped");
	}

	@Test
	void stoppingTheScriptStopsTheWalk() {
		ScriptRun run = start("goto((5, 64, 5))\n");
		tick(run);
		tick(run);
		run.stop();
		assertTrue(bot.pathStopped, "path not stopped");
	}

	@Test
	void homeAndSetHome() {
		bot.home = new Pos(1, 2, 3);
		ScriptRun run = start("print(home())\nprint(set_home((4, 5, 6)))\nprint(home())\n");
		tick(run);
		assertEquals(List.of("Pos(1, 2, 3)", "None", "Pos(4, 5, 6)"), printed());
		assertEquals(new Pos(4, 5, 6), bot.home);
	}

	@Test
	void gotoChecksItsArguments() {
		ScriptRun run = start("goto((1, 2, 3), near=-1)\n");
		tick(run);
		assertEquals(ScriptRun.State.FAILED, run.state());
		assertEquals(List.of("t.py:1: goto() near must be at least 0, not -1"), log.last(100, Level.ERROR).stream().map(BotLog.Entry::text).toList());
	}

	@Test
	void reachablePlansWithoutWalking() {
		ScriptRun run = start("print(reachable((3, 64, 4)), reachable((5, 64, 5), near=2))\n");
		tick(run);
		assertEquals(List.of("True True"), printed());
		assertEquals(List.of("Pos(3, 64, 4) near 0", "Pos(5, 64, 5) near 2"), bot.plans);
		assertEquals(List.of(), bot.paths);
	}

	@Test
	void reachableIsFalseWhereThePlanFindsNoWay() {
		bot.planFound = false;
		ScriptRun run = start("print(reachable((3, 64, 4)))\n");
		tick(run);
		assertEquals(List.of("False"), printed());
	}

	/** An unloaded position is not reachable, and reachable loads no chunks to find out. */
	@Test
	void reachableIsFalseForAnUnloadedPosition() {
		loadChunks(true);
		bot.loadedUpToX = 0;
		ScriptRun run = start("print(reachable((100, 64, 0)))\n");
		tick(run);
		assertEquals(List.of("False"), printed());
		assertEquals(List.of(), bot.plans);
		assertEquals(List.of(), bot.loadRequests);
	}

	/**
	 * A plan longer than a tick's share of the server's path budget goes on over the next ticks, pausing the script, and
	 * charges the script an instruction per node.
	 */
	@Test
	void aLongPlanPausesTheScriptAndCostsInstructions() {
		bot.planNodes = PathBudget.NODES_PER_TICK * 2 + 100;
		ScriptRun run = start("print(reachable((3, 64, 4)))\n");
		tick(run);
		assertEquals(ScriptRun.State.RUNNING, run.state());
		assertTrue(run.action() != null && run.action().startsWith("reachable"), "action " + run.action());
		tick(run);
		assertEquals(List.of(), printed());
		assertTrue(run.action() != null && run.action().startsWith("reachable"), "action " + run.action());
		tick(run);
		assertTrue(run.action() == null, "still planning: " + run.action());
		// The charged instructions use up this tick's budget and the next ones' before the script goes on.
		for (int i = 0; i < 5 && run.state() == ScriptRun.State.RUNNING; i++) {
			tick(run);
		}
		assertEquals(List.of("True"), printed());
		assertTrue(run.executed() >= bot.planNodes, run.executed() + " instructions");
	}

	@Test
	void reachableChecksItsArguments() {
		ScriptRun run = start("reachable((1, 2, 3), near=-1)\n");
		tick(run);
		assertEquals(ScriptRun.State.FAILED, run.state());
		assertEquals(List.of("t.py:1: reachable() near must be at least 0, not -1"), log.last(100, Level.ERROR).stream().map(BotLog.Entry::text).toList());
	}
}
