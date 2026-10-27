package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
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
	 */
	public static @Nullable AntBot activate(ServerLevel level, BlockPos pos, @Nullable Player player) {
		AntBot bot = activate(level, pos, mayJoin(player, pos));
		if (bot != null && player != null) {
			bot.setOwner(player.getUUID());
		}
		return bot;
	}

	/** Which colonies a bot activated at {@code pos} by {@code player} may join; see {@link #activate(ServerLevel, BlockPos, Player)}. */
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
