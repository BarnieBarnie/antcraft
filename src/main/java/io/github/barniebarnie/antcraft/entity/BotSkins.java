package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.JobType;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Which skin a bot wears: one per kind of work, so players can tell at a glance what a bot is up to. */
public final class BotSkins {
	private static final String[] VANILLA = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

	private BotSkins() {
	}

	/**
	 * The skin texture for a bot whose last job was of type {@code job}, as a texture id without the {@code textures/}
	 * prefix and {@code .png} suffix. Bots that have not taken a job wear a default (wide-arm) player skin picked by
	 * UUID, so they keep it across reloads.
	 */
	public static Identifier texture(Optional<JobType> job, UUID uuid) {
		if (job.isEmpty()) {
			return Identifier.withDefaultNamespace("entity/player/wide/" + VANILLA[Math.floorMod(uuid.hashCode(), VANILLA.length)]);
		}
		String skin = switch (job.get()) {
			case CHOP_TREE, CHOP_LOG, REPLANT -> "lumberjack";
			case MINE_BRANCH, MINE_VEIN, CLEAR_AREA -> "miner";
			case HARVEST_CROP, PLANT_CROP -> "farmer";
			case BUILD -> "builder";
			case CRAFT, ACTIVATE_BOT -> "crafter";
			case HAUL -> "hauler";
		};
		return Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "entity/bot/" + skin);
	}
}
