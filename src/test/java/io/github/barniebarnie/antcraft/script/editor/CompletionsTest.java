package io.github.barniebarnie.antcraft.script.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Name suggestions while typing in the script editor (docs/features/scripts.md, Editing in game). */
class CompletionsTest {
	private static List<String> at(String marked, Set<String> names) {
		String source = marked.replace("|", "");
		return Completions.suggest(source, marked.indexOf('|'), Highlighter.spans(source, Set.of()), names);
	}

	@Test
	void namesStartingWithTheWordAreSuggestedExactCaseFirstThenShortestThenAlphabetical() {
		Set<String> names = Set.of("print", "pr", "Prompt", "prune", "sprint", "process", "Print");
		assertEquals(List.of("print", "prune", "process", "Print", "Prompt"), at("x = pr|", names));
		assertEquals(List.of(), at("x = print|", Set.of("print")));
	}

	@Test
	void theWordIsTheNameBeforeTheCaretWithDotsForModules() {
		assertEquals("pri", Completions.prefix("x = pri", 7));
		assertEquals("lib.tr", Completions.prefix("import lib.tr", 13));
		assertEquals("", Completions.prefix("x = 12", 6));
		assertEquals("", Completions.prefix("foo(", 4));
		assertEquals("", Completions.prefix("x = .a", 6));
		assertEquals(List.of("lib.trees"), at("import lib.t|", Set.of("lib.trees", "miner")));
	}

	@Test
	void nothingIsSuggestedInStringsAndComments() {
		Set<String> names = Set.of("print");
		assertEquals(List.of(), at("x = 'pr|'", names));
		assertEquals(List.of(), at("x = 'pr|", names));
		assertEquals(List.of(), at("x = 1 # pr|", names));
		assertEquals(List.of("print"), at("x = 'a' + pr|", names));
	}

	@Test
	void namesTheScriptDefinesAreSuggestedButNotKeywordsItUses() {
		String source = "def chop(tree):\n    count = 1\n    return co";
		Set<String> names = Highlighter.names(source);
		assertTrue(names.containsAll(Set.of("chop", "tree", "count")), names.toString());
		assertTrue(!names.contains("def") && !names.contains("return"), names.toString());
		assertEquals(List.of("count"), Completions.suggest(source, source.length(), Highlighter.spans(source, Set.of()), names));
	}

	@Test
	void theBotsScreenSearchOrdersScriptNamesAsCompletionDoesWithTheNameItself() {
		Set<String> names = Set.of("miner", "Mine", "mi", "lib.mine", "farm", "minerals");
		assertEquals(List.of("mi", "miner", "minerals", "Mine"), Completions.matching("mi", names));
		assertEquals(List.of("farm"), Completions.matching("FARM", names));
		// Nothing typed: every name, alphabetically.
		assertEquals(List.of("Mine", "farm", "lib.mine", "mi", "miner", "minerals"), Completions.matching("", names));
	}
}
