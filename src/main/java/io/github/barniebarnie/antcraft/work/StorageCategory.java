package io.github.barniebarnie.antcraft.work;

import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** What a storage chest holds. Items are sorted by their tags; the first category that matches wins. */
public enum StorageCategory {
	TOOLS(ConventionalItemTags.TOOLS, ConventionalItemTags.ARMORS),
	FOOD(ConventionalItemTags.FOODS, ConventionalItemTags.CROPS, ConventionalItemTags.SEEDS),
	WOOD(ItemTags.LOGS, ItemTags.PLANKS, ItemTags.SAPLINGS, ConventionalItemTags.WOODEN_RODS),
	STONE(ConventionalItemTags.COBBLESTONES, ConventionalItemTags.STONES, ItemTags.STONE_CRAFTING_MATERIALS, ConventionalItemTags.ORES,
		ConventionalItemTags.RAW_MATERIALS, ConventionalItemTags.INGOTS, ConventionalItemTags.GEMS, ItemTags.COALS),
	MISC;

	private final List<TagKey<Item>> tags;

	@SafeVarargs
	StorageCategory(TagKey<Item>... tags) {
		this.tags = List.of(tags);
	}

	/** @param hasTag whether the item has a tag */
	public static StorageCategory of(Predicate<TagKey<Item>> hasTag) {
		for (StorageCategory category : values()) {
			if (category.tags.stream().anyMatch(hasTag)) {
				return category;
			}
		}
		return MISC;
	}

	public static StorageCategory of(ItemStack stack) {
		return of(stack::is);
	}
}
