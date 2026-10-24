package io.github.barniebarnie.antcraft.config;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
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
	private static final int DEFAULT_MAX_BOTS = 64;
	/** Iron and copper, gold and lapis, diamonds and redstone. */
	private static final List<Integer> DEFAULT_MINESHAFT_LEVELS = List.of(16, -16, -58);
	/** Two minutes: long enough for a felled tree's decaying leaves to drop a sapling of its own kind. */
	private static final int DEFAULT_REPLANT_MATCH_WAIT_TICKS = 2400;

	/**
	 * {@code mineshaftLevels} are the heights at which a new colony mineshaft gets a landing to branch-mine from,
	 * at least {@link Mineshaft#MIN_LEVEL_GAP} apart.
	 * {@code colonyActionsRequireOp} limits the colony screen, both viewing and changes, to operators; otherwise its owner, trusted players and operators may (see {@code ColonyActions.mayAct}).
	 * {@code replantMatchWaitTicks} is how long a felled 1x1 tree waits for a sapling of its own kind before any sapling will do.
	 */
	public record Values(boolean enableOnDedicatedServer, int maxBotsPerLevel, List<Integer> mineshaftLevels, boolean colonyActionsRequireOp,
		int replantMatchWaitTicks) {
		public Values(boolean enableOnDedicatedServer, int maxBotsPerLevel) {
			this(enableOnDedicatedServer, maxBotsPerLevel, DEFAULT_MINESHAFT_LEVELS, false, DEFAULT_REPLANT_MATCH_WAIT_TICKS);
		}
	}

	private static Values values = new Values(false, DEFAULT_MAX_BOTS);

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

		values = parse(props);

		props.setProperty("enableOnDedicatedServer", Boolean.toString(values.enableOnDedicatedServer()));
		props.setProperty("maxBotsPerLevel", Integer.toString(values.maxBotsPerLevel()));
		props.setProperty("mineshaftLevels", String.join(",", values.mineshaftLevels().stream().map(String::valueOf).toList()));
		props.setProperty("colonyActionsRequireOp", Boolean.toString(values.colonyActionsRequireOp()));
		props.setProperty("replantMatchWaitTicks", Integer.toString(values.replantMatchWaitTicks()));
		try (Writer writer = Files.newBufferedWriter(path)) {
			props.store(writer, "AntCraft settings. Bots are disabled on dedicated servers unless enableOnDedicatedServer=true.");
		} catch (IOException e) {
			AntCraft.LOGGER.error("Failed to write {}", path, e);
		}
	}

	public static Values parse(Properties props) {
		return parse(props, AntCraft.LOGGER::warn);
	}

	/** Like {@link #parse(Properties)}, telling {@code warn} what it had to correct. */
	public static Values parse(Properties props, Consumer<String> warn) {
		boolean enableOnDedicatedServer = Boolean.parseBoolean(props.getProperty("enableOnDedicatedServer", "false").trim());
		int maxBots = nonNegativeInt(props, "maxBotsPerLevel", DEFAULT_MAX_BOTS);
		List<Integer> levels;
		try {
			levels = Arrays.stream(props.getProperty("mineshaftLevels", "").split(",")).map(String::trim).map(Integer::parseInt).toList();
		} catch (NumberFormatException e) {
			levels = DEFAULT_MINESHAFT_LEVELS;
		}
		List<Integer> spaced = Mineshaft.spaced(levels);
		List<Integer> dropped = levels.stream().distinct().filter(y -> !spaced.contains(y)).toList();
		if (!dropped.isEmpty()) {
			warn.accept("mineshaftLevels " + levels + ": levels must be at least " + Mineshaft.MIN_LEVEL_GAP
				+ " blocks apart, or branches cut into the next level's hall; dropped " + dropped + ", using " + spaced);
		}
		levels = spaced;
		boolean colonyActionsRequireOp = Boolean.parseBoolean(props.getProperty("colonyActionsRequireOp", "false").trim());
		int replantMatchWaitTicks = nonNegativeInt(props, "replantMatchWaitTicks", DEFAULT_REPLANT_MATCH_WAIT_TICKS);
		return new Values(enableOnDedicatedServer, maxBots, levels, colonyActionsRequireOp, replantMatchWaitTicks);
	}

	/** The whole number under {@code key}, at least 0, or {@code fallback} when it is missing or not a number. */
	private static int nonNegativeInt(Properties props, String key, int fallback) {
		try {
			return Math.max(0, Integer.parseInt(props.getProperty(key, Integer.toString(fallback)).trim()));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public static boolean botsAllowed(boolean dedicatedServer, boolean enableOnDedicatedServer) {
		return !dedicatedServer || enableOnDedicatedServer;
	}

	public static boolean botsAllowed(MinecraftServer server) {
		return botsAllowed(server.isDedicatedServer(), values.enableOnDedicatedServer());
	}

	public static List<Integer> mineshaftLevels() {
		return values.mineshaftLevels();
	}

	public static boolean colonyActionsRequireOp() {
		return values.colonyActionsRequireOp();
	}

	public static int replantMatchWaitTicks() {
		return values.replantMatchWaitTicks();
	}

	public static int maxBotsPerLevel() {
		return values.maxBotsPerLevel();
	}

	/** Overrides the loaded settings. Used by game tests, which run on a dedicated server. */
	public static void setValues(Values newValues) {
		values = newValues;
	}
}
