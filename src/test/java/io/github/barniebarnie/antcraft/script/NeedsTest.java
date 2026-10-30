package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import io.github.barniebarnie.antcraft.work.TaskPlanner;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The sense {@code needs(item, n=1)} (docs/reference/crafting/needs.md). */
class NeedsTest {
	@BeforeAll
	static void bootstrap() {
		VanillaData.recipeBook();
	}

	private static PyDict needs(Item item, long n, Map<Item, Integer> stock) {
		return CraftApi.needs(item, n, stock, VanillaData.recipeBook(), VanillaData.gatherable());
	}

	private static String crafts(PyDict needs) {
		return Values.repr(needs.get("crafts"));
	}

	private static String gather(PyDict needs) {
		return Values.repr(needs.get("gather"));
	}

	@Test
	void aPlanBecomesGatherAndCraftSteps() {
		TaskPlanner.Recipe<String> planks = new TaskPlanner.Recipe<>("planks", 4, Map.of(Set.of("log"), 1));
		TaskPlanner.Recipe<String> ingot = new TaskPlanner.Recipe<>("ingot", 1, Map.of(Set.of("ore"), 1), true);
		Map<String, Integer> toGather = new LinkedHashMap<>();
		toGather.put("log", 2);
		toGather.put("coal", 1);
		Map<String, Integer> missing = new LinkedHashMap<>();
		missing.put("ore", 3);
		missing.put("coal", 1);
		TaskPlanner.Plan<String> plan = new TaskPlanner.Plan<>(
			List.of(new TaskPlanner.Craft<>(planks, 2), new TaskPlanner.Craft<>(ingot, 3, Map.of("coal", 1))), toGather, missing);

		PyDict needs = CraftApi.needs(plan, Function.identity());

		assertEquals("{'log': 2, 'coal': 2, 'ore': 3}", gather(needs));
		assertEquals("[('planks', 8, 'craft'), ('ingot', 3, 'smelt')]", crafts(needs));
	}

	@Test
	void aStonePickaxeFromNothingIsCobblestoneAndALogThenPlanksSticksAndThePickaxe() {
		PyDict needs = needs(Items.STONE_PICKAXE, 1, Map.of());

		PyDict gather = (PyDict) needs.get("gather");
		assertEquals(2, gather.size(), gather(needs));
		assertEquals(3L, gather.get("minecraft:cobblestone"));
		assertEquals(1L, gather.get("minecraft:oak_log"));
		assertEquals("[('minecraft:oak_planks', 4, 'craft'), ('minecraft:stick', 4, 'craft'), ('minecraft:stone_pickaxe', 1, 'craft')]",
			crafts(needs));
	}

	@Test
	void whatIsCarriedIsNotGatheredOrMade() {
		PyDict sticks = needs(Items.STONE_PICKAXE, 1, Map.of(Items.STICK, 2));
		assertEquals("{'minecraft:cobblestone': 3}", gather(sticks));
		assertEquals("[('minecraft:stone_pickaxe', 1, 'craft')]", crafts(sticks));

		PyDict all = needs(Items.STONE_PICKAXE, 2, Map.of(Items.COBBLESTONE, 6, Items.OAK_LOG, 1));
		assertEquals("{}", gather(all));
		assertEquals("[('minecraft:oak_planks', 4, 'craft'), ('minecraft:stick', 4, 'craft'), ('minecraft:stone_pickaxe', 2, 'craft')]",
			crafts(all));
	}

	@Test
	void aSmeltedIngredientIsASmeltStep() {
		PyDict needs = needs(Items.IRON_PICKAXE, 1, Map.of(Items.RAW_IRON, 3, Items.STICK, 2, Items.COAL, 1));

		assertEquals("{}", gather(needs));
		assertEquals("[('minecraft:iron_ingot', 3, 'smelt'), ('minecraft:iron_pickaxe', 1, 'craft')]", crafts(needs));
		PyList crafts = (PyList) needs.get("crafts");
		assertEquals(2, crafts.items.size());
	}

	@Test
	void lessThanOneIsAnError() {
		ScriptError error = assertThrows(ScriptError.class, () -> needs(Items.STICK, 0, Map.of()));
		assertEquals("needs() n must be at least 1, not 0", error.getMessage());
	}
}
