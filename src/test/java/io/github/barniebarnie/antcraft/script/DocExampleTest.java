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
 * resources; this keeps the two the same. So is the recipe of docs/cookbook/stock-chest.md, which CraftGameTest runs too,
 * and those of farm.md, recover-items.md and memory.md, which CookbookGameTest runs.
 */
class DocExampleTest {
	private static final Path DOC = Path.of("docs/cookbook/index.md");
	private static final Path COPY = Path.of("src/gametest/resources/antcraft-gametest/scripts/tree_to_pickaxe.py");
	private static final String HEADING = "Punch a tree, then make a wooden pickaxe:";

	/** The first python code block after {@code heading} in {@code doc}. */
	private static String example(String doc, String heading) {
		int at = doc.indexOf(heading);
		assertTrue(at >= 0, "no '" + heading + "'");
		int start = doc.indexOf("```python\n", at) + "```python\n".length();
		int end = doc.indexOf("```", start);
		return doc.substring(start, end);
	}

	@Test
	void theAcceptanceScriptIsTheDocsExample() throws IOException {
		assertEquals(example(Files.readString(DOC), HEADING), Files.readString(COPY));
	}

	@Test
	void theStockChestScriptIsTheRecipe() throws IOException {
		assertEquals(example(Files.readString(Path.of("docs/cookbook/stock-chest.md")), "Keep a chest stocked from a barrel:"),
			Files.readString(Path.of("src/gametest/resources/antcraft-gametest/scripts/stock_chest.py")));
	}

	@Test
	void theCookbookGameTestScriptsAreTheRecipes() throws IOException {
		for (String recipe : List.of("farm", "recover-items", "memory")) {
			assertEquals(example(Files.readString(Path.of("docs/cookbook/" + recipe + ".md")), "```python"),
				Files.readString(Path.of("src/gametest/resources/antcraft-gametest/scripts/" + recipe.replace('-', '_') + ".py")), recipe);
		}
	}

	/** The bundled job {@code name}, below its header comment. */
	private static String job(String name) throws IOException {
		return Files.readString(Path.of("src/main/resources/antcraft/scripts/jobs/" + name.replace('-', '_') + ".py")).lines()
			.dropWhile(line -> line.startsWith("#") || line.isBlank()).collect(Collectors.joining("\n", "", "\n"));
	}

	/**
	 * Each job's cookbook page shows the bundled job, below its header comment: docs/cookbook/get.md shows jobs/get.py,
	 * which GetJobGameTest runs, and so on for the jobs BootstrapGameTest, GiantTreeGameTest, StripMineGameTest,
	 * BuildGameTest, ExploreGameTest and StorageGameTest run. farm.md shows its job under "The bundled job", after the
	 * recipe CookbookGameTest runs.
	 */
	@Test
	void theJobRecipesAreTheBundledJobs() throws IOException {
		for (String page : List.of("get", "bootstrap", "giant-tree", "strip-mine", "build", "explore", "store", "farm")) {
			String heading = page.equals("farm") ? "## The bundled job" : "```python";
			assertEquals(example(Files.readString(Path.of("docs/cookbook/" + page + ".md")), heading), job(page), page);
		}
	}

	/** docs/cookbook/settle.md shows the steps of jobs/settle.py, as they stand in its run(...), one indent in. */
	@Test
	void theSettleStepsAreTheJobs() throws IOException {
		String steps = example(Files.readString(Path.of("docs/cookbook/settle.md")), "The steps,");
		String indented = steps.lines().map(line -> "    " + line).collect(Collectors.joining("\n", "", "\n"));
		assertTrue(job("settle").contains(indented), "settle.md's steps are not in jobs/settle.py:\n" + steps);
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
