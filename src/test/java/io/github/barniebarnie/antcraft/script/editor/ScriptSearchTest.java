package io.github.barniebarnie.antcraft.script.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The bots screen's script picker search (docs/features/scripts.md, The bots screen). */
class ScriptSearchTest {
	@Test
	void anyPartOfTheNameFindsItInAnyCaseThoseStartingWithItFirst() {
		Set<String> names = Set.of("jobs.wood_farm", "wood", "woodcutter", "miner", "jobs.Woodland");
		assertEquals(List.of("wood", "woodcutter", "jobs.wood_farm", "jobs.Woodland"), ScriptSearch.matching("wood", names));
		assertEquals(List.of("jobs.wood_farm"), ScriptSearch.matching("FARM", names));
		assertEquals(List.of(), ScriptSearch.matching("tree", names));
	}

	@Test
	void runnableScriptsComeBeforeLibraryModules() {
		Set<String> names = Set.of("lib.trees", "colony.common", "colony.lumberjack", "jobs.forester", "idle", "lib.treefarm");
		assertEquals(List.of("colony.lumberjack", "idle", "jobs.forester", "colony.common", "lib.treefarm", "lib.trees"),
			ScriptSearch.matching("", names));
		assertEquals(List.of("jobs.forester", "lib.trees"), ScriptSearch.matching("es", Set.of("lib.trees", "jobs.forester")));
		assertEquals(List.of("jobs.cook", "colony.common"), ScriptSearch.matching("co", Set.of("colony.common", "jobs.cook")));
	}

	@Test
	void theShownRangeIsCountedFromOneAndOnlyWhenNotAllMatchesFit() {
		assertArrayEquals(new int[] {1, 12}, ScriptSearch.shown(0, 12, 40));
		assertArrayEquals(new int[] {29, 40}, ScriptSearch.shown(28, 12, 40));
		assertNull(ScriptSearch.shown(0, 12, 12));
		assertNull(ScriptSearch.shown(0, 12, 3));
	}
}
