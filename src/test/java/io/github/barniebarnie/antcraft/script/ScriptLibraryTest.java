package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading scripts from the config and world script folders (docs/scripting.md, Files and commands). */
class ScriptLibraryTest {
	@TempDir
	Path temp;

	private static void write(Path file, String source) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, source);
	}

	@Test
	void loadsEveryPyFileByNameWithFoldersAsDots() throws IOException {
		Path config = temp.resolve("config");
		write(config.resolve("miner.py"), "print('miner')\n");
		write(config.resolve("lib/trees.py"), "x = 1\n");
		write(config.resolve("notes.txt"), "not a script\n");
		ScriptLibrary library = ScriptLibrary.load(List.of(config));
		assertEquals(List.of("lib.trees", "miner"), library.scripts().stream().map(ScriptLibrary.Script::name).toList());
		ScriptLibrary.Script miner = library.get("miner").orElseThrow();
		assertNotNull(miner.code());
		assertNull(miner.error());
		assertEquals("miner.py", miner.code().file());
		assertEquals("lib/trees.py", library.get("lib.trees").orElseThrow().code().file());
	}

	@Test
	void theWorldFolderWinsOnANameClash() throws IOException {
		Path config = temp.resolve("config");
		Path world = temp.resolve("world");
		write(config.resolve("miner.py"), "print('config')\n");
		write(config.resolve("farmer.py"), "print('farmer')\n");
		write(world.resolve("miner.py"), "print('world')\n");
		ScriptLibrary library = ScriptLibrary.load(List.of(config, world));
		assertEquals(List.of("farmer", "miner"), library.scripts().stream().map(ScriptLibrary.Script::name).toList());
		assertEquals("print('world')\n", library.get("miner").orElseThrow().source());
		assertEquals(world.resolve("miner.py"), library.get("miner").orElseThrow().path());
	}

	@Test
	void missingFoldersAreSkipped() {
		ScriptLibrary library = ScriptLibrary.load(List.of(temp.resolve("nothing"), temp.resolve("also_nothing")));
		assertTrue(library.scripts().isEmpty());
	}

	@Test
	void compileErrorsAreKeptAndListed() throws IOException {
		Path config = temp.resolve("config");
		write(config.resolve("good.py"), "print(1)\n");
		write(config.resolve("bad.py"), "if True\n    pass\n");
		write(config.resolve("classy.py"), "class A:\n    pass\n");
		ScriptLibrary library = ScriptLibrary.load(List.of(config));
		assertEquals(3, library.scripts().size());
		ScriptLibrary.Script bad = library.get("bad").orElseThrow();
		assertNull(bad.code());
		assertEquals("bad.py:1:8: expected ':' after 'if' condition", bad.error());
		assertEquals(List.of("bad", "classy"), library.errors().stream().map(ScriptLibrary.Script::name).toList());
		assertTrue(library.get("classy").orElseThrow().error().startsWith("classy.py:1:1: "), library.get("classy").orElseThrow().error());
	}

	@Test
	void unknownNamesAreEmpty() {
		assertTrue(ScriptLibrary.load(List.of()).get("miner").isEmpty());
	}
}
