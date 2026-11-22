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
 * The slot order of the bundled {@code lib/depot.py} (docs/reference/modules/lib/depot.md, slot; #1021): a pure function
 * of origin, facing, width and index. Without a width one endless row towards facing; with one, a row of width chests,
 * then the next row two blocks to the right of facing, a walkway between.
 */
class DepotSlotTest {
	private static final String SLOTS = """
		import lib.depot
		def show(facing, width, ns):
		    print(" ".join([str(lib.depot.slot(Pos(10, 64, 10), facing, width, n)) for n in ns]))
		""";

	@TempDir
	Path folder;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private List<String> logged(String source) throws IOException {
		// The depot alone, the modules below it left empty but for lib.dig's FACING: some read item tags as they load.
		DefaultScripts.install(folder, Map.of("lib/depot.py", DefaultScripts.bundled().get("lib/depot.py"),
			"lib/result.py", DefaultScripts.bundled().get("lib/result.py"), "lib/get.py", "", "lib/storage.py", "",
			"lib/dig.py", "FACING = {\"north\": (0, 0, -1), \"south\": (0, 0, 1), \"east\": (1, 0, 0), \"west\": (-1, 0, 0)}\n",
			"t.py", source));
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
		return log.last(100, Level.WARN).stream().map(BotLog.Entry::text).toList();
	}

	@Test
	void withoutAWidthTheRowNeverWraps() throws IOException {
		assertEquals(List.of("Pos(10, 64, 10) Pos(11, 64, 10) Pos(19, 64, 10) Pos(70, 64, 10)"),
			logged(SLOTS + "show(\"east\", None, [0, 1, 9, 60])\n"));
	}

	@Test
	void theNinthChestOfAWidthEightDepotStartsTheNextRowTwoBlocksToTheRight() throws IOException {
		assertEquals(List.of(
				"Pos(10, 64, 10) Pos(17, 64, 10) Pos(10, 64, 12) Pos(11, 64, 12) Pos(17, 64, 12) Pos(10, 64, 14)",
				"Pos(10, 64, 10) Pos(10, 64, 3) Pos(12, 64, 10) Pos(12, 64, 3)",
				"Pos(10, 64, 10) Pos(3, 64, 10) Pos(10, 64, 8) Pos(3, 64, 8)",
				"Pos(10, 64, 10) Pos(10, 64, 17) Pos(8, 64, 10) Pos(8, 64, 17)"),
			logged(SLOTS + """
				show("east", 8, [0, 7, 8, 9, 15, 16])
				show("north", 8, [0, 7, 8, 15])
				show("west", 8, [0, 7, 8, 15])
				show("south", 8, [0, 7, 8, 15])
				"""));
	}
}
