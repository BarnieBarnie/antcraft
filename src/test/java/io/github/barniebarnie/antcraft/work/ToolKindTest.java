package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import io.github.barniebarnie.antcraft.colony.ToolUpgrade;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ToolKindTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Axes and hoes from planks, cobblestone or diamonds, with sticks; no smelting, so no ingots. */
	private static final TaskPlanner.RecipeBook<Item> BOOK = item -> {
		Map<Set<Item>, Integer> head;
		if (item == Items.WOODEN_AXE || item == Items.WOODEN_HOE) {
			head = Map.of(Set.of(Items.OAK_PLANKS), item == Items.WOODEN_AXE ? 3 : 2);
		} else if (item == Items.STONE_AXE || item == Items.STONE_HOE) {
			head = Map.of(Set.of(Items.COBBLESTONE), item == Items.STONE_AXE ? 3 : 2);
		} else if (item == Items.IRON_AXE || item == Items.IRON_HOE) {
			head = Map.of(Set.of(Items.IRON_INGOT), item == Items.IRON_AXE ? 3 : 2);
		} else if (item == Items.DIAMOND_AXE || item == Items.DIAMOND_HOE) {
			head = Map.of(Set.of(Items.DIAMOND), item == Items.DIAMOND_AXE ? 3 : 2);
		} else {
			return List.of();
		}
		Map<Set<Item>, Integer> ingredients = new java.util.HashMap<>(head);
		ingredients.put(Set.of(Items.STICK), 2);
		return List.of(new TaskPlanner.Recipe<>(item, 1, ingredients));
	};

	private static Optional<Item> order(ToolKind kind, Item current, Map<Item, Integer> stock, Map<Item, Integer> orders) {
		return ToolUpgrade.toOrder(kind.tiers(), current, stock, orders, BOOK, item -> false);
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
	void lumberjackOrdersTheBestAxeTheColonyCanMake() {
		assertEquals(Optional.of(Items.STONE_AXE), order(ToolKind.AXE, Items.WOODEN_AXE, Map.of(Items.COBBLESTONE, 3, Items.STICK, 2), Map.of()));
		assertEquals(Optional.of(Items.DIAMOND_AXE),
			order(ToolKind.AXE, Items.WOODEN_AXE, Map.of(Items.COBBLESTONE, 3, Items.DIAMOND, 3, Items.STICK, 2), Map.of()));
		// Not while a better one is in the core (fetch that) or on order.
		assertEquals(Optional.empty(),
			order(ToolKind.AXE, Items.WOODEN_AXE, Map.of(Items.STONE_AXE, 1, Items.COBBLESTONE, 3, Items.STICK, 2), Map.of()));
		assertEquals(Optional.empty(),
			order(ToolKind.AXE, Items.WOODEN_AXE, Map.of(Items.COBBLESTONE, 3, Items.STICK, 2), Map.of(Items.STONE_AXE, 1)));
	}

	@Test
	void farmerOrdersTheBestHoeTheColonyCanMake() {
		assertEquals(Optional.of(Items.STONE_HOE), order(ToolKind.HOE, Items.WOODEN_HOE, Map.of(Items.COBBLESTONE, 2, Items.STICK, 2), Map.of()));
		// Iron needs smelting first.
		assertEquals(Optional.empty(), order(ToolKind.HOE, Items.STONE_HOE, Map.of(Items.COBBLESTONE, 20, Items.STICK, 20), Map.of()));
		assertEquals(Optional.of(Items.IRON_HOE), order(ToolKind.HOE, Items.STONE_HOE, Map.of(Items.IRON_INGOT, 2, Items.STICK, 2), Map.of()));
	}
}
