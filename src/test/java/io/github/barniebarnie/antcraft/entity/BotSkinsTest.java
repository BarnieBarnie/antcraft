package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.colony.JobType;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class BotSkinsTest {
	private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-000000000007");

	@Test
	void jobsOfOneKindOfWorkShareASkin() {
		assertEquals(BotSkins.texture(Optional.of(JobType.CHOP_TREE), BOT), BotSkins.texture(Optional.of(JobType.REPLANT), BOT));
		assertEquals(BotSkins.texture(Optional.of(JobType.HARVEST_CROP), BOT), BotSkins.texture(Optional.of(JobType.PLANT_CROP), BOT));
		assertEquals(Identifier.fromNamespaceAndPath("antcraft", "entity/bot/miner"), BotSkins.texture(Optional.of(JobType.MINE_BRANCH), BOT));
	}

	@Test
	void botsThatHaveNotWorkedYetKeepTheirVanillaSkin() {
		Identifier skin = BotSkins.texture(Optional.empty(), BOT);
		assertEquals("minecraft", skin.getNamespace());
		assertTrue(skin.getPath().startsWith("entity/player/wide/"), skin.toString());
		// The same bot always gets the same skin.
		assertEquals(skin, BotSkins.texture(Optional.empty(), BOT));
	}

	@Test
	void everyJobSkinHasATexture() {
		for (JobType type : JobType.values()) {
			Identifier skin = BotSkins.texture(Optional.of(type), BOT);
			assertEquals("antcraft", skin.getNamespace(), type.toString());
			String file = "/assets/antcraft/textures/" + skin.getPath() + ".png";
			assertNotNull(BotSkinsTest.class.getResource(file), "missing texture " + file);
		}
	}
}
