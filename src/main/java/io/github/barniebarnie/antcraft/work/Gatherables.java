package io.github.barniebarnie.antcraft.work;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;

/** Raw materials some bot role can collect, so the planner asks for them instead of crafting. */
public final class Gatherables {
	private Gatherables() {
	}

	public static boolean isGatherable(Item item) {
		return item.builtInRegistryHolder().is(ItemTags.LOGS);
	}
}
