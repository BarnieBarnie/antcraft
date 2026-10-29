package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The examples of the docs: every {@code ```python} block under docs/ compiles (an example that is wrong on purpose uses
 * another fence, such as {@code ```text}), and the "punch a tree, then make a wooden pickaxe" example of
 * docs/cookbook/index.md is the script the acceptance game test runs (CraftGameTest), kept as a copy in the game test
 * resources; this keeps the two the same.
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

	/** A python block: its fence's indent (inside an admonition), then its lines up to a closing fence at that indent. */
	private static final Pattern PYTHON_BLOCK = Pattern.compile("(?ms)^([ \\t]*)```python[ \\t]*\\n(.*?)^\\1```[ \\t]*$");

	@Test
	void everyPythonBlockInTheDocsCompiles() throws IOException {
		List<Path> docs;
		try (Stream<Path> files = Files.walk(Path.of("docs"))) {
			docs = files.filter(file -> file.toString().endsWith(".md")).sorted().toList();
		}
		List<String> failures = new ArrayList<>();
		int blocks = 0;
		for (Path doc : docs) {
			String text = Files.readString(doc);
			Matcher block = PYTHON_BLOCK.matcher(text);
			while (block.find()) {
				blocks++;
				String indent = block.group(1);
				String source = block.group(2).lines().map(line -> line.startsWith(indent) ? line.substring(indent.length()) : line)
						.collect(Collectors.joining("\n", "", "\n"));
				String where = doc + ":" + (text.substring(0, block.start()).split("\n", -1).length);
				try {
					Compiler.compile(Parser.parse(where, source));
				} catch (RuntimeException e) {
					failures.add(e.getMessage()); // it starts with where
				}
			}
		}
		assertTrue(blocks > 0, "no python blocks under docs/");
		assertEquals(List.of(), failures);
	}

	@Test
	void theExampleCompiles() throws IOException {
		Compiler.compile(Parser.parse("tree_to_pickaxe.py", Files.readString(COPY)));
	}
}
