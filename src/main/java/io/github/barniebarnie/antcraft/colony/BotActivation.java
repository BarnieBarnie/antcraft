package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.item.CoreScript;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.PlayerLimits;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jspecify.annotations.Nullable;

/** Turning a Bot Core into a working bot. */
public final class BotActivation {
	private BotActivation() {
	}

	public static boolean canActivate(ServerLevel level) {
		return AntCraftConfig.botsAllowed(level.getServer()) && AntBot.countIn(level) < AntCraftConfig.maxBotsPerLevel();
	}

	/** Spawns a bot at {@code pos}, names it and lets it join the nearest colony. */
	public static @Nullable AntBot activate(ServerLevel level, BlockPos pos) {
		return activate(level, pos, colony -> true);
	}

	/**
	 * Spawns a bot at {@code pos} for {@code player}, who used a Bot Core: it joins the nearest colony that
	 * {@code player} may act on ({@link ColonyActions#mayAct}, counting from where the bot stands), or none. With no
	 * player, such as a Bot Core used by a machine, it joins none. The player owns the bot (see {@link AntBot#getOwner}).
	 * With a {@code script} (the core's {@link CoreScript}) the bot starts it at once, as {@code /antcraft bot run} would;
	 * one that is gone or no longer compiles is refused as that command refuses it, in the player's chat, and the bot
	 * runs {@code idle.py} as one from a core without a script does. So does a player who already runs
	 * {@code maxScriptsPerPlayer} scripts ({@link PlayerLimits#mayStart}), who is told so.
	 */
	public static @Nullable AntBot activate(ServerLevel level, BlockPos pos, @Nullable Player player, @Nullable CoreScript script) {
		AntBot bot = activate(level, pos, mayJoin(player, pos));
		if (bot == null) {
			return null;
		}
		if (player != null) {
			bot.setOwner(player.getUUID());
		}
		if (script != null && player != null && !PlayerLimits.mayStart(PlayerLimits.running(level.getServer(), player.getUUID(), List.of()), 1,
			AntCraftConfig.maxScriptsPerPlayer(), PlayerLimits.exempt(level.getServer(), player.getUUID()))) {
			player.sendSystemMessage(Component.translatable("commands.antcraft.bot.too_many", AntCraftConfig.maxScriptsPerPlayer()));
		} else if (script != null) {
			AntCraftCommands.runnable(script.name(), failure -> {
				if (player != null) {
					player.sendSystemMessage(failure);
				}
			}).ifPresent(found -> bot.runScript(found, script.args()));
		}
		return bot;
	}

	/** Which colonies a bot activated at {@code pos} by {@code player} may join; see {@link #activate(ServerLevel, BlockPos, Player, CoreScript)}. */
	public static Predicate<Colony> mayJoin(@Nullable Player player, BlockPos pos) {
		if (player == null) {
			return colony -> false;
		}
		boolean isOp = ColonyActions.isOp(player);
		boolean requireOp = AntCraftConfig.colonyActionsRequireOp();
		return colony -> ColonyActions.mayAct(colony, player.getUUID(), pos, isOp, requireOp);
	}

	private static @Nullable AntBot activate(ServerLevel level, BlockPos pos, Predicate<Colony> mayJoin) {
		AntBot bot = ModEntities.ANT_BOT.spawn(level, pos, EntitySpawnReason.MOB_SUMMONED);
		if (bot == null) {
			return null;
		}
		bot.setCustomName(Component.literal("Ant " + bot.getUUID().toString().substring(0, 4)));
		ColonyManager colonies = ColonyManager.get(level);
		colonies.nearest(pos, ColonyManager.JOIN_DISTANCE, mayJoin).ifPresent(colony -> colonies.join(bot, colony));
		level.gameEvent(null, GameEvent.ENTITY_PLACE, pos);
		return bot;
	}

	/** A free spot next to {@code center} where a bot can stand. */
	public static Optional<BlockPos> freeSpotNear(ServerLevel level, BlockPos center) {
		for (int dy = -1; dy <= 1; dy++) {
			for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, dy, -1), center.offset(1, dy, 1))) {
				if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
					&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
					&& level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) {
					return Optional.of(pos.immutable());
				}
			}
		}
		return Optional.empty();
	}
}
