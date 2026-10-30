package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

/**
 * Who may script which bot, and the limits of the scripts of players who are not operators: all their bots share
 * {@code playerInstructionsPerTick}, and they may run {@code maxScriptsPerPlayer} scripts at once (docs/features/scripts.md,
 * Players' scripts). Operators, and every player off a dedicated server, are exempt.
 */
public final class PlayerLimits {
	/** Each limited owner's bots' budget this tick, worked out at the start of the tick. */
	private static final Map<UUID, Long> SHARES = new HashMap<>();

	private PlayerLimits() {
	}

	/** Whether {@code player} may run, stop or read the log of a bot {@code owner} owns: its owner, and operators. */
	public static boolean mayControl(@Nullable UUID owner, UUID player, boolean isOp) {
		return isOp || player.equals(owner);
	}

	/** Whether a player running {@code running} scripts may start {@code starting} more, at most {@code max} at once. */
	public static boolean mayStart(int running, int starting, int max, boolean exempt) {
		return exempt || running + starting <= max;
	}

	/**
	 * The instructions a tick of each of an owner's {@code bots} bots running a script: {@code perPlayer} split evenly, at
	 * most {@code perBot} and at least 1.
	 */
	public static long budget(int perBot, int perPlayer, int bots, boolean exempt) {
		if (exempt || bots == 0) {
			return perBot;
		}
		return Math.max(1, Math.min(perBot, perPlayer / bots));
	}

	/** Whether the limits leave {@code player} alone: an operator, or anyone off a dedicated server. */
	public static boolean exempt(MinecraftServer server, UUID player) {
		return !server.isDedicatedServer() || Commands.LEVEL_GAMEMASTERS.check(server.getProfilePermissions(new NameAndId(player, "")));
	}

	/** The budget of a bot {@code owner} owns this tick. */
	public static long budget(@Nullable UUID owner) {
		Long share = owner == null ? null : SHARES.get(owner);
		// ponytail: a bot whose owner's first script started this tick gets the per-bot budget until the next tick.
		return share != null ? share : AntCraftConfig.scriptInstructionsPerTick();
	}

	/** How many scripts of its own (not {@code idle.py}) the bots {@code owner} owns run, but those in {@code except}. */
	public static int running(MinecraftServer server, UUID owner, Collection<AntBot> except) {
		int running = 0;
		for (ServerLevel level : server.getAllLevels()) {
			running += level.getEntities(ModEntities.ANT_BOT,
				bot -> owner.equals(bot.getOwner()) && bot.isRunningScript() && !bot.isIdling() && !except.contains(bot)).size();
		}
		return running;
	}

	public static void register() {
		ServerTickEvents.START_SERVER_TICK.register(PlayerLimits::shareOut);
	}

	/** Splits each limited owner's budget between their bots running a script, {@code idle.py} too. */
	private static void shareOut(MinecraftServer server) {
		Map<UUID, Integer> bots = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			for (AntBot bot : level.getEntities(ModEntities.ANT_BOT, bot -> bot.getOwner() != null && bot.isRunningScript())) {
				bots.merge(bot.getOwner(), 1, Integer::sum);
			}
		}
		SHARES.clear();
		bots.forEach((owner, count) -> {
			if (!exempt(server, owner)) {
				SHARES.put(owner, budget(AntCraftConfig.scriptInstructionsPerTick(), AntCraftConfig.playerInstructionsPerTick(), count, false));
			}
		});
	}
}
