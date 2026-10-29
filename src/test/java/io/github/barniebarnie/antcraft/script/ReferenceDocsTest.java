package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The API reference (docs/reference/) keeps up with the natives a script can call (docs/reference/template.md): each has
 * its page, docs/reference/{@code <category>}/{@code <name>}.md, and a row in the index, and each page is a native's.
 * A native whose page is still to be written is in {@link #TO_WRITE} instead, and its index row has no link.
 */
class ReferenceDocsTest {
	private static final Path REFERENCE = Path.of("docs/reference");
	private static final Path INDEX = REFERENCE.resolve("index.md");
	private static final Path TO_WRITE = Path.of("src/test/resources/reference-pages-to-write.txt");
	/** The pages of the types (Pos, Result, selectors, ...), which are no natives. */
	private static final String TYPES = "types";
	/** An index row: its first cell is a native's name, linked to its page or not. */
	private static final Pattern ROW = Pattern.compile("(?m)^\\| (?:\\[`(\\w+)`\\]\\(([^)]+)\\)|`(\\w+)`) \\|");

	/** The functions every script on a bot can call: the language's builtins and the bot API. */
	private static Set<String> natives() {
		Natives natives = Natives.standard(Output.lines(line -> {
		}));
		BotApi.register(natives, null);
		Set<String> names = new TreeSet<>();
		// Methods are named type.name; $-names are the compiler's helpers, which no script names.
		natives.names().stream().filter(name -> !name.contains(".") && !name.startsWith("$")).forEach(names::add);
		return names;
	}

	private static List<String> toWrite() throws IOException {
		return Files.readAllLines(TO_WRITE).stream().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.toList();
	}

	@Test
	void everyNativeHasAPageAndAnIndexRowAndEveryPageIsANatives() throws IOException {
		Set<String> natives = natives();
		List<String> toWrite = toWrite();
		List<String> failures = new ArrayList<>();

		Map<String, String> pages = new HashMap<>();
		List<Path> files;
		try (Stream<Path> walk = Files.walk(REFERENCE, 2)) {
			files = walk.filter(file -> file.getNameCount() == REFERENCE.getNameCount() + 2 && file.toString().endsWith(".md"))
					.filter(file -> !file.getName(REFERENCE.getNameCount()).toString().equals(TYPES)).sorted().toList();
		}
		for (Path file : files) {
			String page = REFERENCE.relativize(file).toString().replace('\\', '/');
			String name = file.getFileName().toString().replaceFirst("\\.md$", "");
			if (!natives.contains(name)) {
				failures.add(page + " names no native");
			}
			if (toWrite.contains(name)) {
				failures.add(name + " has its page " + page + ": take it out of " + TO_WRITE);
			}
			if (!Files.readString(file).startsWith("# `" + name + "`")) {
				failures.add(page + " does not start with the heading # `" + name + "`");
			}
			String other = pages.put(name, page);
			if (other != null) {
				failures.add(name + " has two pages, " + other + " and " + page);
			}
		}

		Set<String> listed = new TreeSet<>();
		for (String name : toWrite) {
			if (!natives.contains(name)) {
				failures.add(TO_WRITE + " lists " + name + ", which is no native");
			}
			if (!listed.add(name)) {
				failures.add(TO_WRITE + " lists " + name + " twice");
			}
		}

		Map<String, Integer> rows = new HashMap<>();
		Matcher row = ROW.matcher(Files.readString(INDEX));
		while (row.find()) {
			String name = row.group(1) != null ? row.group(1) : row.group(3);
			rows.merge(name, 1, Integer::sum);
			if (!natives.contains(name)) {
				failures.add("the index has a row for " + name + ", which is no native");
			} else if (pages.containsKey(name) && !pages.get(name).equals(row.group(2))) {
				failures.add("the index row of " + name + " does not link to its page " + pages.get(name));
			} else if (!pages.containsKey(name) && row.group(2) != null) {
				failures.add("the index row of " + name + " links to " + row.group(2) + ", which is not written yet");
			}
		}

		for (String name : natives) {
			if (!pages.containsKey(name) && !listed.contains(name)) {
				failures.add(name + " has no page docs/reference/<category>/" + name + ".md (see docs/reference/template.md)");
			}
			if (rows.getOrDefault(name, 0) != 1) {
				failures.add(name + " has " + rows.getOrDefault(name, 0) + " rows in the index, not 1");
			}
		}
		assertEquals(List.of(), failures);
	}
}
