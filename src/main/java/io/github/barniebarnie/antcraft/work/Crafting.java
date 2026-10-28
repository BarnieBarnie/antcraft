package io.github.barniebarnie.antcraft.work;

import java.util.Collection;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** Where a bot can work a recipe: 2x2 recipes anywhere, 3x3 ones at a crafting table and smelting at a furnace. */
public final class Crafting {
	/** What a recipe is worked at. */
	public enum Station {
		/** The 2x2 grid every bot has. */
		HAND(null),
		TABLE(Items.CRAFTING_TABLE),
		FURNACE(Items.FURNACE);

		private final @Nullable Item item;

		Station(@Nullable Item item) {
			this.item = item;
		}

		/** The station as an item a bot carries and places, or empty for {@link #HAND}. */
		public Optional<Item> item() {
			return Optional.ofNullable(this.item);
		}
	}

	private Crafting() {
	}

	public static Station station(TaskPlanner.Recipe<Item> recipe) {
		return recipe.smelting() ? Station.FURNACE : recipe.large() ? Station.TABLE : Station.HAND;
	}

	/**
	 * Whether a bot carrying {@code carried} may take a job working {@code recipe}: any bot for a 2x2 recipe,
	 * else a bot that carries the station or works where one stands ({@code standing}).
	 */
	public static boolean canClaim(TaskPlanner.Recipe<Item> recipe, Collection<Item> carried, boolean standing) {
		Optional<Item> station = station(recipe).item();
		return station.isEmpty() || standing || carried.contains(station.get());
	}
}
