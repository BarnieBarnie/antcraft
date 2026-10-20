package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FarmingTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void plantingNeedsAHoeUnlessTheSoilIsTilled() {
		assertEquals(Optional.of(Items.WOODEN_HOE), Farming.plantTool(Blocks.DIRT.defaultBlockState()));
		assertEquals(Optional.of(Items.WOODEN_HOE), Farming.plantTool(Blocks.GRASS_BLOCK.defaultBlockState()));
		assertEquals(Optional.empty(), Farming.plantTool(Blocks.FARMLAND.defaultBlockState()));
	}
}
