package io.github.barniebarnie.antcraft.work;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** What crafters burn in a furnace, best first: coal, then charcoal, then logs and planks. */
public final class Fuels {
	private static final int COAL_TICKS = 1600;
	private static final int LOG_TICKS = 300;

	private Fuels() {
	}

	/**
	 * @param logs the items that count as logs (the {@code minecraft:logs} tag on a running server), in the order to burn them when stock ties
	 * @param planks the planks (the {@code minecraft:planks} tag), which burn as long as a log, after the logs on a tie
	 */
	public static List<TaskPlanner.Fuel<Item>> order(Set<Item> logs, Set<Item> planks) {
		Set<Item> wood = new LinkedHashSet<>(logs);
		wood.addAll(planks);
		return List.of(
			new TaskPlanner.Fuel<>(Set.of(Items.COAL), COAL_TICKS),
			new TaskPlanner.Fuel<>(Set.of(Items.CHARCOAL), COAL_TICKS),
			new TaskPlanner.Fuel<>(Collections.unmodifiableSet(wood), LOG_TICKS));
	}
}
