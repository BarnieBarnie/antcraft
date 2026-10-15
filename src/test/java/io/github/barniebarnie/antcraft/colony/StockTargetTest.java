package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StockTargetTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Colony roundTrip(Colony colony) {
		return Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow()).getOrThrow();
	}

	private static Colony colony(List<StockTarget> stock) {
		return new Colony(UUID.randomUUID(), BlockPos.ZERO, List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(), Optional.empty(), stock);
	}

	@Test
	void targetsSurviveCodecRoundTrip() {
		List<StockTarget> stock = List.of(
			StockTarget.of(Items.STICK, 16),
			StockTarget.of(ItemTags.PICKAXES, 2, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE));
		assertEquals(stock, roundTrip(colony(stock)).stockTargets());
	}

	@Test
	void defaultsSurviveCodecRoundTrip() {
		assertEquals(DefaultStock.TARGETS, roundTrip(colony(DefaultStock.TARGETS)).stockTargets());
	}

	@Test
	void anEmptyListStaysEmpty() {
		// A player who removed every target does not get the defaults back.
		assertTrue(roundTrip(colony(List.of())).stockTargets().isEmpty());
	}

	@Test
	void defaultsCoverProgression() {
		List<String> names = DefaultStock.TARGETS.stream().map(StockTarget::name).toList();
		for (String expected : List.of("#minecraft:pickaxes", "#minecraft:axes", "#minecraft:swords", "#minecraft:planks", "minecraft:stick",
				"#antcraft:colony_food", "#antcraft:lumberjack_scaffold_blocks","#minecraft:head_armor", "#minecraft:chest_armor", "#minecraft:leg_armor", "#minecraft:foot_armor")) {
			assertTrue(names.contains(expected), "defaults lack " + expected + ": " + names);
		}
	}

	@Test
	void settingATargetReplacesTheOneForTheSameMatch() {
		Colony colony = colony(List.of(StockTarget.of(ItemTags.PICKAXES, 2, Items.STONE_PICKAXE)));
		colony.setStockTarget(colony.stockTargets().getFirst().withCount(5));
		assertEquals(List.of(StockTarget.of(ItemTags.PICKAXES, 5, Items.STONE_PICKAXE)), colony.stockTargets());
		assertTrue(colony.removeStockTarget(colony.stockTargets().getFirst().match()));
		assertFalse(colony.removeStockTarget(StockTarget.of(Items.STICK, 1).match()));
		assertTrue(colony.stockTargets().isEmpty());
	}
}
