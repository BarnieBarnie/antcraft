package io.github.barniebarnie.antcraft.script;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The mod's default scripts (docs/scripting.md, Files and commands), bundled under {@code /antcraft/scripts/} and copied
 * into the config script folder when the server starts. A file the player changed is never overwritten: the folder keeps
 * a record ({@link #RECORD}) of what was last copied, and a file that no longer matches it is the player's.
 */
public final class DefaultScripts {
	/** The script a bot runs when it has nothing else to do: walk home and wait. */
	public static final String IDLE = "idle";
	/** The bundled scripts, by path in the script folder; the library modules scripts import live under {@code lib/}. */
	private static final List<String> FILES = List.of(IDLE + ".py", "lib/home.py");
	/** Holds, per script, the hash of the version last copied. */
	static final String RECORD = ".antcraft-defaults";

	private DefaultScripts() {
	}

	/** The bundled default scripts, by path in the script folder. */
	public static Map<String, String> bundled() {
		Map<String, String> scripts = new TreeMap<>();
		for (String file : FILES) {
			try (InputStream in = DefaultScripts.class.getResourceAsStream("/antcraft/scripts/" + file)) {
				if (in == null) {
					throw new IllegalStateException("default script " + file + " is missing from the mod");
				}
				scripts.put(file, new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return scripts;
	}

	/**
	 * Copies {@code scripts} (by path in {@code folder}) into {@code folder}: a script that is missing, and one still as
	 * last copied, gets the version given; one the player changed, or wrote before the mod had a default of that name, is
	 * left as it is.
	 */
	public static void install(Path folder, Map<String, String> scripts) throws IOException {
		Path recordFile = folder.resolve(RECORD);
		Properties record = new Properties();
		if (Files.exists(recordFile)) {
			try (Reader reader = Files.newBufferedReader(recordFile)) {
				record.load(reader);
			}
		}
		for (Map.Entry<String, String> script : scripts.entrySet()) {
			Path file = folder.resolve(script.getKey());
			String copied = record.getProperty(script.getKey());
			if (Files.exists(file)) {
				String current = hash(Files.readString(file));
				if (current.equals(hash(script.getValue()))) {
					record.setProperty(script.getKey(), current); // already the default
					continue;
				}
				if (!current.equals(copied)) {
					continue; // the player's
				}
			}
			Files.createDirectories(file.getParent());
			Files.writeString(file, script.getValue());
			record.setProperty(script.getKey(), hash(script.getValue()));
		}
		Files.createDirectories(folder);
		try (Writer writer = Files.newBufferedWriter(recordFile)) {
			record.store(writer, "The default scripts AntCraft last copied here, so it never overwrites one you changed.");
		}
	}

	private static String hash(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
