package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.AntCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Raw materials some bot role can collect (item tag {@code antcraft:gatherable}), so the
 * planner asks for them instead of trying to craft them.
 */
public final class Gatherables {
	public static final TagKey<Item> GATHERABLE = TagKey.create(Registries.ITEM, AntCraft.id("gatherable"));

	private Gatherables() {
	}

	public static boolean isGatherable(Item item) {
		return item.builtInRegistryHolder().is(GATHERABLE);
	}
}
