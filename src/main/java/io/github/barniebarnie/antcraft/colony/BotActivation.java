package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
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
		AntBot bot = ModEntities.ANT_BOT.spawn(level, pos, EntitySpawnReason.MOB_SUMMONED);
		if (bot == null) {
			return null;
		}
		bot.setCustomName(Component.literal("Ant " + bot.getUUID().toString().substring(0, 4)));
		ColonyManager colonies = ColonyManager.get(level);
		colonies.nearest(pos, ColonyManager.JOIN_DISTANCE).ifPresent(colony -> colonies.join(bot, colony));
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
