package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class BotSkinsTest {
	private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-000000000007");

	@Test
	void everyRoleButNoneHasItsOwnSkin() {
		Set<Identifier> seen = new HashSet<>();
		for (BotRole role : BotRole.values()) {
			if (role == BotRole.NONE) {
				continue;
			}
			Identifier skin = BotSkins.texture(role, BOT);
			assertEquals(Identifier.fromNamespaceAndPath("antcraft", "entity/bot/" + role.id()), skin);
			assertTrue(seen.add(skin), "skin shared by two roles: " + skin);
		}
	}

	@Test
	void botsWithoutARoleKeepTheirVanillaSkin() {
		Identifier skin = BotSkins.texture(BotRole.NONE, BOT);
		assertEquals("minecraft", skin.getNamespace());
		assertTrue(skin.getPath().startsWith("entity/player/wide/"), skin.toString());
		// The same bot always gets the same skin.
		assertEquals(skin, BotSkins.texture(BotRole.NONE, BOT));
	}

	@Test
	void everyRoleSkinHasATexture() {
		for (BotRole role : BotRole.values()) {
			if (role == BotRole.NONE) {
				continue;
			}
			String file = "/assets/antcraft/textures/entity/bot/" + role.id() + ".png";
			assertNotNull(BotSkinsTest.class.getResource(file), "missing texture " + file);
		}
	}
}
