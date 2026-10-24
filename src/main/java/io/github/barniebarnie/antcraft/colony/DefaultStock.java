package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What a new colony keeps in stock: tools, weapons, crafting materials, food and armor,
 * so it can grow without the player ordering everything. Tools and weapons take any
 * material; the better option is made when the materials are at hand.
 */
public final class DefaultStock {
	/** Food bots could heal with (item tag {@code antcraft:colony_food}). */
	public static final TagKey<Item> COLONY_FOOD = TagKey.create(Registries.ITEM, AntCraft.id("colony_food"));

	/** The tools and weapons, a new colony's starter tool kit (see {@link StarterProjects}). */
	public static final List<StockTarget> TOOLS = List.of(
		// Best first. An option is only ordered when it can be made from stock right now, so
		// iron waits for raw iron and fuel (or ingots) and diamond for diamonds.
		StockTarget.of(ItemTags.PICKAXES, 2, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE),
		StockTarget.of(ItemTags.AXES, 1, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE),
		StockTarget.of(ItemTags.SHOVELS, 1, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL),
		StockTarget.of(ItemTags.HOES, 1, Items.DIAMOND_HOE, Items.IRON_HOE, Items.STONE_HOE, Items.WOODEN_HOE),
		StockTarget.of(ItemTags.SWORDS, 2, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD)
	);

	public static final List<StockTarget> TARGETS = Stream.concat(TOOLS.stream(), Stream.of(
		StockTarget.of(ItemTags.LOGS, 32),
		StockTarget.of(ItemTags.PLANKS, 32),
		StockTarget.of(Items.STICK, 16),
		StockTarget.of(Items.COBBLESTONE, 64),
		StockTarget.of(PathFollower.SCAFFOLD_BLOCKS, 32),
		// Lumberjacks only pillar with these; the cobblestone above would otherwise meet the scaffold target alone.
		StockTarget.of(PathFollower.FAST_SCAFFOLD_BLOCKS, 16),
		StockTarget.of(COLONY_FOOD, 16, Items.BREAD, Items.BAKED_POTATO),
		// Builders and miners light the mineshaft with these.
		StockTarget.of(Items.TORCH, 32),
		// Armor needs ingots, so these wait until the colony can smelt.
		StockTarget.of(ItemTags.HEAD_ARMOR, 1, Items.IRON_HELMET, Items.COPPER_HELMET),
		StockTarget.of(ItemTags.CHEST_ARMOR, 1, Items.IRON_CHESTPLATE, Items.COPPER_CHESTPLATE),
		StockTarget.of(ItemTags.LEG_ARMOR, 1, Items.IRON_LEGGINGS, Items.COPPER_LEGGINGS),
		StockTarget.of(ItemTags.FOOT_ARMOR, 1, Items.IRON_BOOTS, Items.COPPER_BOOTS)
	)).toList();

	private DefaultStock() {
	}
}
