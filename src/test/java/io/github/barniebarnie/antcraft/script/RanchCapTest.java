package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The adult cap of the bundled {@code jobs/ranch.py} (docs/cookbook/ranch.md, The pen; #855): as many adults of each kind
 * as one bot tends between two breedings of a parent, by the measured ticks a round spends on each adult, and no more
 * than the kinds and their babies can share the pen's floor, a block an animal; at least a pair.
 */
class RanchCapTest {
	@TempDir
	Path folder;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private List<String> logged(String source) throws IOException {
		// The ranch alone, its library modules left empty: some read item tags as they load, which a unit test has not bound.
		DefaultScripts.install(folder, Map.of("jobs/ranch.py", DefaultScripts.bundled().get("jobs/ranch.py"), "lib/borrow.py", "",
			"lib/depot.py", "", "lib/stock.py", "", "t.py", source));
		ScriptLibrary library = ScriptLibrary.load(List.of(folder));
		ScriptRun.Host host = new ScriptRun.Host() {
			@Override
			public long gameTime() {
				return 0;
			}

			@Override
			public Pos pos() {
				return new Pos(0, 64, 0);
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
		};
		BotLog log = new BotLog(100);
		ScriptRun run = new ScriptRun("t", library.get("t").orElseThrow().code(), List.of(), host, log, Memory.create(), library);
		run.tick(100_000);
		// What it printed, oldest first, with any warning or error among it.
		return log.last(100, Level.WARN).stream().map(BotLog.Entry::text).toList();
	}

	@Test
	void aBigPenHoldsWhatOneBotTendsInABreedingCooldown() throws IOException {
		// (6000 - 100) // (4 kinds * 62 ticks an adult) = 23
		assertEquals(List.of("23 62"), logged("import jobs.ranch\nprint(jobs.ranch.default_cap(Pos(0, 64, 0), Pos(29, 64, 29)), jobs.ranch.TEND)\n"));
	}

	@Test
	void aSmallPenHoldsABlockAnAnimalBabiesTooAndAtLeastAPair() throws IOException {
		// Up to as many babies as adults (#864): an 8 by 8 pen, 64 // (2 * 4 kinds) = 8; a 6 by 3 pen 18 // 8 = 2.
		assertEquals(List.of("8", "2", "2"), logged("""
			import jobs.ranch
			print(jobs.ranch.default_cap(Pos(0, 64, 0), Pos(7, 64, 7)))
			print(jobs.ranch.default_cap(Pos(1, 64, 4), Pos(6, 64, 6)))
			print(jobs.ranch.default_cap(Pos(0, 64, 0), Pos(1, 64, 1)))
			"""));
	}
}
