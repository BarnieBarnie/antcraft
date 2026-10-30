package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checking a script the in-game editor sends before the server writes it (docs/features/scripts.md, Editing in game). */
class ScriptEditsTest {
	@TempDir
	Path world;

	@Test
	void aDottedNameIsAFileInTheWorldFolderWithFoldersForDots() {
		assertEquals(world.resolve("miner.py"), ScriptEdits.check(world, "miner", "print('hi')\n"));
		assertEquals(world.resolve("mypack").resolve("lib").resolve("trees.py"), ScriptEdits.check(world, "mypack.lib.trees", "x = 1\n"));
		assertEquals(world.resolve("chop-trees_2.py"), ScriptEdits.check(world, "chop-trees_2", ""));
	}

	@Test
	void aNameThatIsNotDottedWordsIsRefused() {
		for (String name : List.of("", "..", "../evil", "a..b", ".a", "a.", "/etc/passwd", "C:\\evil", "\\evil", "a/b", "a\\b", "~", "a b",
			"a\u0000b", "mypack:lib", "naïve", "a.*", "con.py..")) {
			assertThrows(IllegalArgumentException.class, () -> ScriptEdits.check(world, name, "x = 1\n"), name);
		}
	}

	@Test
	void aVeryLongNameIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> ScriptEdits.check(world, "a".repeat(ScriptEdits.MAX_NAME_LENGTH + 1), ""));
		ScriptEdits.check(world, "a".repeat(ScriptEdits.MAX_NAME_LENGTH), "");
	}

	@Test
	void aSourceOverTheSizeLimitIsRefusedCountingBytes() {
		ScriptEdits.check(world, "big", "#" + "x".repeat(ScriptEdits.MAX_SOURCE_BYTES - 1));
		IllegalArgumentException tooBig = assertThrows(IllegalArgumentException.class,
			() -> ScriptEdits.check(world, "big", "#" + "x".repeat(ScriptEdits.MAX_SOURCE_BYTES)));
		assertTrue(tooBig.getMessage().contains("too large"), tooBig.getMessage());
		// Two bytes each in UTF-8: half as many characters fill the limit.
		assertThrows(IllegalArgumentException.class, () -> ScriptEdits.check(world, "big", "#" + "é".repeat(ScriptEdits.MAX_SOURCE_BYTES / 2)));
	}

	@Test
	void aSourceThatDoesNotCompileIsRefusedWithTheErrorAndItsLine() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> ScriptEdits.check(world, "lib.broken", "x = 1\nif x\n    pass\n"));
		assertTrue(error.getMessage().startsWith("lib/broken.py:2"), error.getMessage());
		assertEquals(2, ScriptEdits.errorLine(error));
	}

	@Test
	void aRefusalForAnythingButACompileErrorHasNoLine() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ScriptEdits.check(world, "..", "x = 1\n"));
		assertEquals(0, ScriptEdits.errorLine(error));
	}
}
