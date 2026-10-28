package io.github.barniebarnie.antcraft.entity;

import java.util.UUID;
import net.minecraft.resources.Identifier;

/**
 * Which skin a bot wears: a default (wide-arm) player skin picked by its UUID, so it keeps it across reloads. The work
 * skins under {@code textures/entity/bot/} were worn for the goal-driven colony's jobs and wait for scripted roles.
 */
public final class BotSkins {
	private static final String[] VANILLA = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

	private BotSkins() {
	}

	/** The skin texture for the bot {@code uuid}, as a texture id without the {@code textures/} prefix and {@code .png} suffix. */
	public static Identifier texture(UUID uuid) {
		return Identifier.withDefaultNamespace("entity/player/wide/" + VANILLA[Math.floorMod(uuid.hashCode(), VANILLA.length)]);
	}
}
