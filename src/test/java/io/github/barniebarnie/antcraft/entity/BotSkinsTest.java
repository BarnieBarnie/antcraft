package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class BotSkinsTest {
	private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-000000000007");

	@Test
	void botsKeepTheirVanillaSkin() {
		Identifier skin = BotSkins.texture(BOT);
		assertEquals("minecraft", skin.getNamespace());
		assertTrue(skin.getPath().startsWith("entity/player/wide/"), skin.toString());
		// The same bot always gets the same skin.
		assertEquals(skin, BotSkins.texture(BOT));
	}
}
