package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReplantingTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void logsMapToTheirSapling() {
		assertEquals(Optional.of(Items.DARK_OAK_SAPLING), Replanting.saplingFor(Blocks.DARK_OAK_LOG));
		assertEquals(Optional.of(Items.JUNGLE_SAPLING), Replanting.saplingFor(Blocks.JUNGLE_LOG));
		assertEquals(Optional.of(Items.SPRUCE_SAPLING), Replanting.saplingFor(Blocks.SPRUCE_WOOD));
		assertEquals(Optional.of(Items.PALE_OAK_SAPLING), Replanting.saplingFor(Blocks.PALE_OAK_LOG));
	}

	@Test
	void logsWithoutASaplingMapToNothing() {
		assertTrue(Replanting.saplingFor(Blocks.CRIMSON_STEM).isEmpty());
		assertTrue(Replanting.saplingFor(Blocks.MANGROVE_LOG).isEmpty());
	}
}
