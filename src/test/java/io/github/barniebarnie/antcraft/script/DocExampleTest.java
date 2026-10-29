package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The "punch a tree, then make a wooden pickaxe" example of docs/cookbook/index.md is the script the acceptance game test
 * runs (CraftGameTest), kept as a copy in the game test resources; this keeps the two the same.
 */
class DocExampleTest {
	private static final Path DOC = Path.of("docs/cookbook/index.md");
	private static final Path COPY = Path.of("src/gametest/resources/antcraft-gametest/scripts/tree_to_pickaxe.py");
	private static final String HEADING = "Punch a tree, then make a wooden pickaxe:";

	/** The first python code block after {@code heading} in {@code doc}. */
	private static String example(String doc, String heading) {
		int at = doc.indexOf(heading);
		assertTrue(at >= 0, "no '" + heading + "' in " + DOC);
		int start = doc.indexOf("```python\n", at) + "```python\n".length();
		int end = doc.indexOf("```", start);
		return doc.substring(start, end);
	}

	@Test
	void theAcceptanceScriptIsTheDocsExample() throws IOException {
		assertEquals(example(Files.readString(DOC), HEADING), Files.readString(COPY));
	}

	@Test
	void theExampleCompiles() throws IOException {
		Compiler.compile(Parser.parse("tree_to_pickaxe.py", Files.readString(COPY)));
	}
}
