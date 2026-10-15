package io.github.barniebarnie.antcraft.config;

import io.github.barniebarnie.antcraft.AntCraft;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * Server-side settings, stored in config/antcraft.properties.
 *
 * <p>Bots are always allowed in singleplayer and LAN worlds. A dedicated server
 * must opt in explicitly, so the mod cannot be used on a public server unless
 * its owner decides to allow it.
 */
public final class AntCraftConfig {
	private static final String FILE_NAME = "antcraft.properties";

	private static boolean enableOnDedicatedServer = false;
	private static int maxBotsPerLevel = 64;

	private AntCraftConfig() {
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties props = new Properties();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				props.load(reader);
			} catch (IOException e) {
				AntCraft.LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		}

		enableOnDedicatedServer = Boolean.parseBoolean(props.getProperty("enableOnDedicatedServer", "false"));
		try {
			maxBotsPerLevel = Math.max(0, Integer.parseInt(props.getProperty("maxBotsPerLevel", "64").trim()));
		} catch (NumberFormatException e) {
			maxBotsPerLevel = 64;
		}

		props.setProperty("enableOnDedicatedServer", Boolean.toString(enableOnDedicatedServer));
		props.setProperty("maxBotsPerLevel", Integer.toString(maxBotsPerLevel));
		try (Writer writer = Files.newBufferedWriter(path)) {
			props.store(writer, "AntCraft settings. Bots are disabled on dedicated servers unless enableOnDedicatedServer=true.");
		} catch (IOException e) {
			AntCraft.LOGGER.error("Failed to write {}", path, e);
		}
	}

	public static boolean botsAllowed(MinecraftServer server) {
		return !server.isDedicatedServer() || enableOnDedicatedServer;
	}

	public static int maxBotsPerLevel() {
		return maxBotsPerLevel;
	}
}
