package io.github.barniebarnie.antcraft.config;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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
	/** Options of the goal-driven colony, gone in milestone 4 of docs/design/index.md; dropped from the file when it is written again. */
	private static final String[] REMOVED = {"mineshaftLevels", "replantMatchWaitTicks"};
	public static final int DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK = 2000;
	/**
	 * Every bot may use its whole budget every tick, so the cap bounds what scripts cost the server thread. Measured with
	 * the bot benchmark (#322): 30 bots looping at the default average 1 ms a tick, at the cap 5 ms.
	 */
	public static final int MAX_SCRIPT_INSTRUCTIONS_PER_TICK = 20000;
	public static final Output.Level DEFAULT_SCRIPT_CHAT_LEVEL = Output.Level.ERROR;
	/** Ten seconds. */
	public static final int DEFAULT_BOT_RESPAWN_TICKS = 200;
	/** At or below this health (of 20) a bot flees a hostile mob near it (docs/features/bots.md, Reflexes). */
	public static final int DEFAULT_BOT_FLEE_HEALTH = 6;
	/** Below this much air (of 300 ticks) a bot under water swims up to breathe. */
	public static final int DEFAULT_BOT_SURFACE_AIR = 100;

	/**
	 * {@code colonyActionsRequireOp} limits changing colonies to operators; otherwise its owner, trusted players and operators may (see {@code ColonyActions.mayAct}).
	 * {@code scriptInstructionsPerTick} is how many instructions each bot's script may run a tick, at most
	 * {@link #MAX_SCRIPT_INSTRUCTIONS_PER_TICK}. {@code scriptChatLevel} is the lowest log level of a script's output that goes to
	 * its bot's owner's chat ({@code print} always does). {@code botsLoadChunks} is whether bots running a script keep their
	 * chunk loaded and {@code goto} loads the chunks on its way. {@code botRespawn} is whether a bot that died comes back at
	 * its home, {@code botRespawnTicks} after it died. {@code botFleeHealth} and {@code botSurfaceAir} are the thresholds of
	 * the reflexes that flee hostile mobs and swim up to breathe; 0 turns each off. {@code botsAttackPlayers},
	 * {@code botsAttackBots} and {@code botsAttackTamed} let a script's {@code attack} hit players, other bots, and tamed
	 * or named animals; by default it hits only hostile mobs and untamed, unnamed animals (#415).
	 */
	public record Values(boolean enableOnDedicatedServer, int maxBotsPerLevel, boolean colonyActionsRequireOp, int scriptInstructionsPerTick,
		Output.Level scriptChatLevel, boolean botsLoadChunks, boolean botRespawn, int botRespawnTicks, int botFleeHealth, int botSurfaceAir,
		boolean botsAttackPlayers, boolean botsAttackBots, boolean botsAttackTamed) {
		public Values(boolean enableOnDedicatedServer, int maxBotsPerLevel, boolean colonyActionsRequireOp, int scriptInstructionsPerTick,
			Output.Level scriptChatLevel, boolean botsLoadChunks, boolean botRespawn, int botRespawnTicks, int botFleeHealth, int botSurfaceAir) {
			this(enableOnDedicatedServer, maxBotsPerLevel, colonyActionsRequireOp, scriptInstructionsPerTick, scriptChatLevel, botsLoadChunks,
				botRespawn, botRespawnTicks, botFleeHealth, botSurfaceAir, false, false, false);
		}

		public Values(boolean enableOnDedicatedServer, int maxBotsPerLevel, boolean colonyActionsRequireOp, int scriptInstructionsPerTick,
			Output.Level scriptChatLevel, boolean botsLoadChunks, boolean botRespawn, int botRespawnTicks) {
			this(enableOnDedicatedServer, maxBotsPerLevel, colonyActionsRequireOp, scriptInstructionsPerTick, scriptChatLevel, botsLoadChunks,
				botRespawn, botRespawnTicks, DEFAULT_BOT_FLEE_HEALTH, DEFAULT_BOT_SURFACE_AIR);
		}

		public Values(boolean enableOnDedicatedServer, int maxBotsPerLevel, boolean colonyActionsRequireOp, int scriptInstructionsPerTick,
			Output.Level scriptChatLevel, boolean botsLoadChunks) {
			this(enableOnDedicatedServer, maxBotsPerLevel, colonyActionsRequireOp, scriptInstructionsPerTick, scriptChatLevel, botsLoadChunks,
				true, DEFAULT_BOT_RESPAWN_TICKS);
		}

		public Values(boolean enableOnDedicatedServer, int maxBotsPerLevel, boolean colonyActionsRequireOp, int scriptInstructionsPerTick,
			Output.Level scriptChatLevel) {
			this(enableOnDedicatedServer, maxBotsPerLevel, colonyActionsRequireOp, scriptInstructionsPerTick, scriptChatLevel, true);
		}

		public Values(boolean enableOnDedicatedServer, int maxBotsPerLevel) {
			this(enableOnDedicatedServer, maxBotsPerLevel, false);
		}

		public Values(boolean enableOnDedicatedServer, int maxBotsPerLevel, boolean colonyActionsRequireOp) {
			this(enableOnDedicatedServer, maxBotsPerLevel, colonyActionsRequireOp, DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, DEFAULT_SCRIPT_CHAT_LEVEL);
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
		props.setProperty("colonyActionsRequireOp", Boolean.toString(values.colonyActionsRequireOp()));
		props.setProperty("scriptInstructionsPerTick", Integer.toString(values.scriptInstructionsPerTick()));
		props.setProperty("scriptChatLevel", values.scriptChatLevel().name().toLowerCase(Locale.ROOT));
		props.setProperty("botsLoadChunks", Boolean.toString(values.botsLoadChunks()));
		props.setProperty("botRespawn", Boolean.toString(values.botRespawn()));
		props.setProperty("botRespawnTicks", Integer.toString(values.botRespawnTicks()));
		props.setProperty("botFleeHealth", Integer.toString(values.botFleeHealth()));
		props.setProperty("botSurfaceAir", Integer.toString(values.botSurfaceAir()));
		props.setProperty("botsAttackPlayers", Boolean.toString(values.botsAttackPlayers()));
		props.setProperty("botsAttackBots", Boolean.toString(values.botsAttackBots()));
		props.setProperty("botsAttackTamed", Boolean.toString(values.botsAttackTamed()));
		for (String removed : REMOVED) {
			props.remove(removed);
		}
		try (Writer writer = Files.newBufferedWriter(path)) {
			props.store(writer, fileComment());
		} catch (IOException e) {
			AntCraft.LOGGER.error("Failed to write {}", path, e);
		}
	}

	/** The comment at the top of the config file. */
	public static String fileComment() {
		return "AntCraft settings. Bots are disabled on dedicated servers unless enableOnDedicatedServer=true.\n"
			+ "scriptInstructionsPerTick (default " + DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK + ", at most " + MAX_SCRIPT_INSTRUCTIONS_PER_TICK
			+ ") is per bot, and every bot may use its whole budget every tick:\n"
			+ "30 bots at " + MAX_SCRIPT_INSTRUCTIONS_PER_TICK + " are 600000 instructions a tick on the server thread, which can lag the game.\n"
			+ "Raise it only for scripts that think a lot between actions, and measure with /antcraft profile.\n"
			+ "scriptChatLevel (debug, info, warn or error) is the lowest level of script output shown in the bot owner's chat; print always shows.\n"
			+ "botsLoadChunks: bots running a script keep their chunk loaded, and goto loads (and generates) the chunks on its way.\n"
			+ "botRespawn: a bot that dies drops its items and comes back at its home botRespawnTicks (default "
			+ DEFAULT_BOT_RESPAWN_TICKS + ") ticks later; false makes death final.\n"
			+ "botFleeHealth (default " + DEFAULT_BOT_FLEE_HEALTH + ", of 20): at or below this health a bot flees a hostile mob near it; 0 never.\n"
			+ "botSurfaceAir (default " + DEFAULT_BOT_SURFACE_AIR + ", of 300 ticks): below this much air a bot under water swims up to breathe; 0 never.\n"
			+ "botsAttackPlayers, botsAttackBots, botsAttackTamed (default false): whether a script's attack may hit players, other bots,\n"
			+ "and tamed or named animals; it always may hit hostile mobs and untamed, unnamed animals.";
	}

	public static Values parse(Properties props) {
		return parse(props, AntCraft.LOGGER::warn);
	}

	/** Like {@link #parse(Properties)}, telling {@code warn} what it had to correct. */
	public static Values parse(Properties props, Consumer<String> warn) {
		boolean enableOnDedicatedServer = Boolean.parseBoolean(props.getProperty("enableOnDedicatedServer", "false").trim());
		int maxBots = nonNegativeInt(props, "maxBotsPerLevel", DEFAULT_MAX_BOTS);
		boolean colonyActionsRequireOp = Boolean.parseBoolean(props.getProperty("colonyActionsRequireOp", "false").trim());
		int scriptInstructions = Math.max(1, nonNegativeInt(props, "scriptInstructionsPerTick", DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK));
		if (scriptInstructions > MAX_SCRIPT_INSTRUCTIONS_PER_TICK) {
			warn.accept("scriptInstructionsPerTick " + scriptInstructions + " is above the cap of " + MAX_SCRIPT_INSTRUCTIONS_PER_TICK
				+ " (every bot may use its whole budget every tick, which can lag the server); using " + MAX_SCRIPT_INSTRUCTIONS_PER_TICK);
			scriptInstructions = MAX_SCRIPT_INSTRUCTIONS_PER_TICK;
		}
		Output.Level chatLevel = chatLevel(props.getProperty("scriptChatLevel", "error").trim(), warn);
		boolean botsLoadChunks = Boolean.parseBoolean(props.getProperty("botsLoadChunks", "true").trim());
		boolean botRespawn = Boolean.parseBoolean(props.getProperty("botRespawn", "true").trim());
		int botRespawnTicks = nonNegativeInt(props, "botRespawnTicks", DEFAULT_BOT_RESPAWN_TICKS);
		int fleeHealth = nonNegativeInt(props, "botFleeHealth", DEFAULT_BOT_FLEE_HEALTH);
		int surfaceAir = nonNegativeInt(props, "botSurfaceAir", DEFAULT_BOT_SURFACE_AIR);
		return new Values(enableOnDedicatedServer, maxBots, colonyActionsRequireOp, scriptInstructions, chatLevel, botsLoadChunks,
			botRespawn, botRespawnTicks, fleeHealth, surfaceAir, flag(props, "botsAttackPlayers"), flag(props, "botsAttackBots"),
			flag(props, "botsAttackTamed"));
	}

	/** The boolean under {@code key}, false when it is missing. */
	private static boolean flag(Properties props, String key) {
		return Boolean.parseBoolean(props.getProperty(key, "false").trim());
	}

	/** A log level below print by name, in any case; {@code error}, with a warning, for anything else. */
	private static Output.Level chatLevel(String name, Consumer<String> warn) {
		for (Output.Level level : Output.Level.values()) {
			if (level != Output.Level.PRINT && level.name().equalsIgnoreCase(name)) {
				return level;
			}
		}
		warn.accept("scriptChatLevel " + name + " is not one of debug, info, warn or error (print always shows); using error");
		return DEFAULT_SCRIPT_CHAT_LEVEL;
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

	public static boolean colonyActionsRequireOp() {
		return values.colonyActionsRequireOp();
	}

	public static int scriptInstructionsPerTick() {
		return values.scriptInstructionsPerTick();
	}

	public static Output.Level scriptChatLevel() {
		return values.scriptChatLevel();
	}

	public static boolean botsLoadChunks() {
		return values.botsLoadChunks();
	}

	public static int botFleeHealth() {
		return values.botFleeHealth();
	}

	public static int botSurfaceAir() {
		return values.botSurfaceAir();
	}

	public static boolean botRespawn() {
		return values.botRespawn();
	}

	public static int botRespawnTicks() {
		return values.botRespawnTicks();
	}

	/** The settings in force. */
	public static Values values() {
		return values;
	}

	public static int maxBotsPerLevel() {
		return values.maxBotsPerLevel();
	}

	/** Overrides the loaded settings. Used by game tests, which run on a dedicated server. */
	public static void setValues(Values newValues) {
		values = newValues;
	}
}
