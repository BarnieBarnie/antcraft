package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.AntCraft;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Which skin a bot wears: one per role, so players can tell the roles apart at a glance. */
public final class BotSkins {
	private static final String[] VANILLA = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

	private BotSkins() {
	}

	/**
	 * The skin texture, as a texture id without the {@code textures/} prefix and {@code .png} suffix.
	 * Bots without a role wear a default (wide-arm) player skin picked by UUID, so they keep it across reloads.
	 */
	public static Identifier texture(BotRole role, UUID uuid) {
		if (role == BotRole.NONE) {
			return Identifier.withDefaultNamespace("entity/player/wide/" + VANILLA[Math.floorMod(uuid.hashCode(), VANILLA.length)]);
		}
		return Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "entity/bot/" + role.id());
	}
}
