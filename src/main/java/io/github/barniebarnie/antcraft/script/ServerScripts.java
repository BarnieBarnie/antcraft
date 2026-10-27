package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The server's script library, loaded from {@code config/antcraft/scripts/} and {@code <world>/antcraft/scripts/} (the
 * world folder wins on a name clash) when the server starts, and again on {@code /antcraft script reload}.
 */
public final class ServerScripts {
	private static ScriptLibrary library = ScriptLibrary.load(List.of());

	private ServerScripts() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerScripts::reload);
	}

	public static Path configFolder() {
		return FabricLoader.getInstance().getConfigDir().resolve("antcraft").resolve("scripts");
	}

	public static Path worldFolder(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("antcraft").resolve("scripts");
	}

	public static ScriptLibrary library() {
		return library;
	}

	/** What a reload found, and how many running bots it restarted because their script changed. */
	public record Reload(ScriptLibrary library, int restarted) {
	}

	/**
	 * Loads the script folders again, creating them if missing. Bots running a library script whose file changed start
	 * it again from the top; one that no longer compiles is stopped, with the error in its log and its owner's chat.
	 */
	public static Reload reload(MinecraftServer server) {
		List<Path> folders = List.of(configFolder(), worldFolder(server));
		for (Path folder : folders) {
			try {
				Files.createDirectories(folder);
			} catch (IOException e) {
				AntCraft.LOGGER.warn("Cannot create the script folder {}", folder, e);
			}
		}
		library = ScriptLibrary.load(folders);
		library.errors().forEach(script -> AntCraft.LOGGER.warn("Script {}: {}", script.name(), script.error()));
		int restarted = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (AntBot bot : level.getEntities(ModEntities.ANT_BOT, AntBot::isRunningScript)) {
				if (restart(bot)) {
					restarted++;
				}
			}
		}
		return new Reload(library, restarted);
	}

	private static boolean restart(AntBot bot) {
		ScriptRun run = bot.getScript();
		String oldSource = bot.getScriptSource();
		if (run == null || oldSource == null) {
			return false;
		}
		Optional<ScriptLibrary.Script> found = library.get(run.name());
		if (found.isEmpty() || found.get().source().equals(oldSource)) {
			return false;
		}
		ScriptLibrary.Script script = found.get();
		if (script.code() == null) {
			bot.stopScript();
			bot.reportError(script.error());
			return false;
		}
		bot.runScript(script, run.args());
		return true;
	}
}
