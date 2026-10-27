package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Copying the mod's default scripts into the config script folder, never over a file the player changed. */
class DefaultScriptsTest {
	@TempDir
	Path folder;

	private String read(String name) throws IOException {
		return Files.readString(folder.resolve(name));
	}

	@Test
	void copiesMissingScripts() throws IOException {
		DefaultScripts.install(folder, Map.of("idle.py", "v1\n", "lib/trees.py", "trees\n"));
		assertEquals("v1\n", read("idle.py"));
		assertEquals("trees\n", read("lib/trees.py"));
	}

	@Test
	void updatesAScriptThePlayerLeftAsCopied() throws IOException {
		DefaultScripts.install(folder, Map.of("idle.py", "v1\n"));
		DefaultScripts.install(folder, Map.of("idle.py", "v2\n"));
		assertEquals("v2\n", read("idle.py"));
	}

	@Test
	void neverOverwritesAScriptThePlayerChanged() throws IOException {
		DefaultScripts.install(folder, Map.of("idle.py", "v1\n"));
		Files.writeString(folder.resolve("idle.py"), "mine\n");
		DefaultScripts.install(folder, Map.of("idle.py", "v1\n"));
		assertEquals("mine\n", read("idle.py"));
		DefaultScripts.install(folder, Map.of("idle.py", "v2\n"));
		assertEquals("mine\n", read("idle.py"));
	}

	@Test
	void neverOverwritesAScriptThePlayerWroteBeforeTheDefaultCame() throws IOException {
		Files.writeString(folder.resolve("idle.py"), "mine\n");
		DefaultScripts.install(folder, Map.of("idle.py", "v1\n"));
		assertEquals("mine\n", read("idle.py"));
	}

	@Test
	void putsBackAScriptThePlayerDeleted() throws IOException {
		DefaultScripts.install(folder, Map.of("idle.py", "v1\n"));
		Files.delete(folder.resolve("idle.py"));
		DefaultScripts.install(folder, Map.of("idle.py", "v1\n"));
		assertEquals("v1\n", read("idle.py"));
	}

	@Test
	void theBundledIdleScriptCompiles() {
		String idle = DefaultScripts.bundled().get(DefaultScripts.IDLE + ".py");
		assertNotNull(idle);
		Compiler.compile(Parser.parse("idle.py", idle));
	}
}
