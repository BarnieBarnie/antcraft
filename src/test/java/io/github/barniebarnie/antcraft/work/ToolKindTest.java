package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ToolKindTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void tiersRankWorstFirst() {
		assertEquals(List.of(Items.WOODEN_AXE, Items.STONE_AXE, Items.COPPER_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE),
			ToolKind.AXE.tiers());
		assertEquals(List.of(Items.WOODEN_HOE, Items.STONE_HOE, Items.COPPER_HOE, Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE),
			ToolKind.HOE.tiers());
		assertEquals(List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.COPPER_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE,
			Items.NETHERITE_PICKAXE), ToolKind.PICKAXE.tiers());
	}

	@Test
	void ranksTheTiersWorstFirst() {
		assertEquals(-1, ToolKind.AXE.rank(null));
		assertEquals(0, ToolKind.AXE.rank(Items.WOODEN_AXE));
		assertEquals(3, ToolKind.AXE.rank(Items.IRON_AXE));
		assertEquals(java.util.Optional.of(ToolKind.HOE), ToolKind.of(Items.STONE_HOE));
	}
}
