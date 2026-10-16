package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.Test;

class StorageCategoryTest {
	/** Tags are loaded with data packs, so each item is described by the tags it has in game. */
	@SafeVarargs
	private static StorageCategory of(TagKey<Item>... tags) {
		return StorageCategory.of(Set.of(tags)::contains);
	}

	@Test
	void logsAndPlanksAreWood() {
		assertEquals(StorageCategory.WOOD, of(ItemTags.LOGS, ItemTags.LOGS_THAT_BURN));
		assertEquals(StorageCategory.WOOD, of(ItemTags.PLANKS));
	}

	@Test
	void toolsWeaponsAndArmorAreTools() {
		// A pickaxe is in both minecraft:pickaxes and c:tools.
		assertEquals(StorageCategory.TOOLS, of(ItemTags.PICKAXES, ConventionalItemTags.TOOLS));
		assertEquals(StorageCategory.TOOLS, of(ConventionalItemTags.ARMORS));
	}

	@Test
	void cropsAndFoodAreFood() {
		// Wheat is in c:crops.
		assertEquals(StorageCategory.FOOD, of(ConventionalItemTags.CROPS));
		assertEquals(StorageCategory.FOOD, of(ConventionalItemTags.FOODS));
	}

	@Test
	void stoneAndOres() {
		assertEquals(StorageCategory.STONE, of(ConventionalItemTags.COBBLESTONES, ItemTags.STONE_CRAFTING_MATERIALS));
		assertEquals(StorageCategory.STONE, of(ConventionalItemTags.ORES));
	}

	@Test
	void unknownItemsAreMisc() {
		assertEquals(StorageCategory.MISC, of());
	}
}
