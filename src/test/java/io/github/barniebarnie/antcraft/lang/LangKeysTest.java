package io.github.barniebarnie.antcraft.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.entity.BotRole;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Scans the mod's sources for lang keys and checks that {@code en_us.json} has them all. */
class LangKeysTest {
	private static final Path LANG = Path.of("src/main/resources/assets/antcraft/lang/en_us.json");
	private static final List<Path> SOURCES = List.of(Path.of("src/main/java"), Path.of("src/client/java"));
	private static final Path SCREEN = Path.of("src/client/java/io/github/barniebarnie/antcraft/client/ColonyStatusScreen.java");
	/** A string literal such as {@code "commands.antcraft.status"}; one ending in a dot is the prefix of a key family. */
	private static final Pattern KEY = Pattern.compile("\"((?:[a-z0-9_]+\\.)+antcraft(?:\\.[a-z0-9_]+)*\\.?)\"");
	/** Key families built at runtime from a prefix and an enum value. */
	private static final Map<String, List<String>> FAMILIES = Map.of(
		"screen.antcraft.colony_status.role.", Stream.of(BotRole.values()).map(BotRole::id).toList(),
		"screen.antcraft.colony_status.purpose.", Stream.of(Colony.OrderPurpose.values()).map(Colony.OrderPurpose::getSerializedName).toList());

	@Test
	void everyKeyInTheSourcesIsInTheLangFile() throws IOException {
		Set<String> lang = JsonParser.parseString(Files.readString(LANG)).getAsJsonObject().keySet();
		Set<String> missing = new TreeSet<>();
		Set<String> scanned = scannedKeys();
		assertFalse(scanned.isEmpty(), "found no keys; is the working directory the project root?");
		for (String key : scanned) {
			if (key.endsWith(".")) {
				assertTrue(FAMILIES.containsKey(key), "unknown key family " + key + "; add it to FAMILIES");
				FAMILIES.get(key).stream().map(value -> key + value).filter(full -> !lang.contains(full)).forEach(missing::add);
			} else if (!lang.contains(key)) {
				missing.add(key);
			}
		}
		assertEquals(Set.of(), missing);
	}

	@Test
	void everyKeyFamilyIsUsed() {
		Set<String> scanned = scannedKeys();
		FAMILIES.keySet().forEach(prefix -> assertTrue(scanned.contains(prefix), "key family not used: " + prefix));
	}

	@Test
	void langFileHasNoEmptyValues() throws IOException {
		JsonObject lang = JsonParser.parseString(Files.readString(LANG)).getAsJsonObject();
		lang.entrySet().forEach(entry -> assertFalse(entry.getValue().getAsString().isEmpty(), entry.getKey()));
	}

	/** Text the colony screen shows comes from lang keys, never from string literals in the code. */
	@Test
	void colonyScreenShowsNoStringLiterals() throws IOException {
		String source = Files.readString(SCREEN);
		Matcher literal = Pattern.compile("(?:literal|append|join)\\(\\s*\"[^\"]*\"|\"[^\"]*\"\\s*\\+|\\+\\s*\"[^\"]*\"").matcher(source);
		List<String> found = new ArrayList<>();
		while (literal.find()) {
			found.add(literal.group());
		}
		assertEquals(List.of(), found);
	}

	private static Set<String> scannedKeys() {
		Set<String> keys = new TreeSet<>();
		for (Path root : SOURCES) {
			try (Stream<Path> files = Files.walk(root)) {
				files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
					try {
						Matcher key = KEY.matcher(Files.readString(file));
						while (key.find()) {
							keys.add(key.group(1));
						}
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return keys;
	}
}
