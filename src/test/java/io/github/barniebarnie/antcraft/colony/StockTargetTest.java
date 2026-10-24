package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
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
		return new Colony(UUID.randomUUID(), BlockPos.ZERO, List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(), Optional.empty(), stock, Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
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
	void optionsAreTheMakeOptionsThenTheRestOfTheTag() {
		VanillaData.bindTags();
		List<Item> options = StockTarget.of(ItemTags.PICKAXES, 2, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE).options();
		assertEquals(List.of(Items.STONE_PICKAXE, Items.WOODEN_PICKAXE), options.subList(0, 2));
		assertTrue(options.contains(Items.GOLDEN_PICKAXE), "lacks the tag's other members: " + options);
		assertEquals(Set.copyOf(options).size(), options.size(), "lists an item twice: " + options);
		assertEquals(List.of(Items.STICK), StockTarget.of(Items.STICK, 16).options());
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

	/**
	 * A small slice of the game's recipes: raw iron smelts into ingots with coal, and pickaxes
	 * and iron armor are crafted from planks, cobblestone, ingots or diamonds.
	 */
	private static final TaskPlanner.RecipeBook<Item> BOOK = new TaskPlanner.RecipeBook<>() {
		@Override
		public List<TaskPlanner.Recipe<Item>> recipesFor(Item item) {
			if (item == Items.IRON_INGOT) {
				return List.of(new TaskPlanner.Recipe<>(Items.IRON_INGOT, 1, Map.of(Set.of(Items.RAW_IRON), 1), true));
			}
			if (item == Items.STICK) {
				return List.of(new TaskPlanner.Recipe<>(Items.STICK, 4, Map.of(Set.of(Items.OAK_PLANKS), 2)));
			}
			if (item == Items.WOODEN_PICKAXE) {
				return List.of(pickaxe(item, Items.OAK_PLANKS));
			}
			if (item == Items.STONE_PICKAXE) {
				return List.of(pickaxe(item, Items.COBBLESTONE));
			}
			if (item == Items.IRON_PICKAXE) {
				return List.of(pickaxe(item, Items.IRON_INGOT));
			}
			if (item == Items.DIAMOND_PICKAXE) {
				return List.of(pickaxe(item, Items.DIAMOND));
			}
			if (item == Items.IRON_HELMET) {
				return List.of(new TaskPlanner.Recipe<>(item, 1, Map.of(Set.of(Items.IRON_INGOT), 5)));
			}
			return List.of();
		}

		@Override
		public List<TaskPlanner.Fuel<Item>> fuels() {
			return List.of(new TaskPlanner.Fuel<>(Set.of(Items.COAL), 1600));
		}
	};
	// A method, not a constant: items cannot be touched before bootstrap.
	private static boolean gatherable(Item item) {
		return Set.of(Items.OAK_LOG, Items.COBBLESTONE, Items.RAW_IRON, Items.COAL, Items.DIAMOND).contains(item);
	}

	private static TaskPlanner.Recipe<Item> pickaxe(Item pickaxe, Item material) {
		return new TaskPlanner.Recipe<>(pickaxe, 1, Map.of(Set.of(material), 3, Set.of(Items.STICK), 2));
	}

	/** What the default target for {@code tag} orders with {@code stock} in the core. */
	private static Map<Item, Integer> defaultOrders(TagKey<Item> tag, Map<Item, Integer> stock) {
		StockTarget target = DefaultStock.TARGETS.stream().filter(t -> t.match().left().equals(Optional.of(tag))).findFirst().orElseThrow();
		// Tags are not bound in unit tests, so the listed options stand in for the tag's members.
		StockPlanner.Target<Item> planned = new StockPlanner.Target<>(target.make()::contains, target.makeOptions(), target.count());
		return StockPlanner.ordersToAdd(List.of(planned), stock, Map.of(), BOOK, StockTargetTest::gatherable);
	}

	@Test
	void pickaxeTargetOrdersIronOnceRawIronAndFuelAreIn() {
		assertEquals(Map.of(Items.IRON_PICKAXE, 1),
			defaultOrders(ItemTags.PICKAXES, Map.of(Items.RAW_IRON, 3, Items.COAL, 1, Items.STICK, 2, Items.COBBLESTONE, 3)));
	}

	@Test
	void pickaxeTargetFallsBackToStone() {
		assertEquals(Map.of(Items.STONE_PICKAXE, 1), defaultOrders(ItemTags.PICKAXES, Map.of(Items.COBBLESTONE, 3, Items.STICK, 2)));
		// Raw iron without fuel cannot be smelted: stone it is.
		assertEquals(Map.of(Items.STONE_PICKAXE, 1),
			defaultOrders(ItemTags.PICKAXES, Map.of(Items.RAW_IRON, 3, Items.COBBLESTONE, 3, Items.STICK, 2)));
	}

	@Test
	void pickaxeTargetOrdersDiamondWhenDiamondsAreIn() {
		assertEquals(Map.of(Items.DIAMOND_PICKAXE, 1),
			defaultOrders(ItemTags.PICKAXES, Map.of(Items.DIAMOND, 3, Items.RAW_IRON, 3, Items.COAL, 1, Items.STICK, 2)));
	}

	@Test
	void everyToolTargetPrefersDiamondThenIronThenStoneThenWood() {
		Map<TagKey<Item>, List<Item>> expected = Map.of(
			ItemTags.PICKAXES, List.of(Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE),
			ItemTags.AXES, List.of(Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE),
			ItemTags.SHOVELS, List.of(Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL),
			ItemTags.HOES, List.of(Items.DIAMOND_HOE, Items.IRON_HOE, Items.STONE_HOE, Items.WOODEN_HOE),
			ItemTags.SWORDS, List.of(Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD));
		expected.forEach((tag, make) -> assertEquals(make,
			DefaultStock.TARGETS.stream().filter(t -> t.match().left().equals(Optional.of(tag))).findFirst().orElseThrow().make(),
			tag.location().toString()));
	}

	@Test
	void helmetTargetIsOrderedOnceRawIronAndCoalAreIn() {
		assertTrue(defaultOrders(ItemTags.HEAD_ARMOR, Map.of()).isEmpty());
		assertEquals(Map.of(Items.IRON_HELMET, 1), defaultOrders(ItemTags.HEAD_ARMOR, Map.of(Items.RAW_IRON, 5, Items.COAL, 1)));
	}
}
