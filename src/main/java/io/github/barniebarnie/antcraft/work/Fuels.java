package io.github.barniebarnie.antcraft.work;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** What crafters burn in a furnace, best first: coal, then charcoal, then logs. */
public final class Fuels {
	private static final int COAL_TICKS = 1600;
	private static final int LOG_TICKS = 300;

	private Fuels() {
	}

	/** @param logs the items that count as logs (the {@code minecraft:logs} tag on a running server), in the order to burn them when stock ties */
	public static List<TaskPlanner.Fuel<Item>> order(Set<Item> logs) {
		return List.of(
			new TaskPlanner.Fuel<>(Set.of(Items.COAL), COAL_TICKS),
			new TaskPlanner.Fuel<>(Set.of(Items.CHARCOAL), COAL_TICKS),
			new TaskPlanner.Fuel<>(Collections.unmodifiableSet(new LinkedHashSet<>(logs)), LOG_TICKS));
	}
}
