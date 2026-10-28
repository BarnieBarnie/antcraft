package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** idle.py keeps its own copy of lib/home.py's portal code (#352); the copies must stay the same, docstrings too. */
class IdleCopiesTest {
	@Test
	void idleAndHomeShareTheSamePortalCode() throws IOException {
		String idle = read("idle.py");
		String home = read("lib/home.py");
		for (String start : new String[] {"NETHER_PORTAL_WORLDS =", "def portal_out(", "def to_home_dimension("}) {
			assertEquals(block(home, start), block(idle, start), "idle.py and lib/home.py differ in " + start);
		}
	}

	/** From the line starting with start up to the next top-level line that is not blank or a comment. */
	static String block(String source, String start) {
		StringBuilder block = null;
		for (String line : source.split("\n", -1)) {
			if (block == null) {
				if (line.startsWith(start)) block = new StringBuilder(line).append('\n');
			} else if (line.isEmpty() || line.startsWith("#") || Character.isWhitespace(line.charAt(0))) {
				block.append(line).append('\n');
			} else {
				break;
			}
		}
		assertNotNull(block, "no " + start);
		return block.toString().strip();
	}

	private static String read(String name) throws IOException {
		try (InputStream in = IdleCopiesTest.class.getResourceAsStream("/antcraft/scripts/" + name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
