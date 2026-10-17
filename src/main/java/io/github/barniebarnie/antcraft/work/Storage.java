package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.StockPlanner;
import io.github.barniebarnie.antcraft.colony.Stockkeeping;
import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Moving items between a bot's inventory and storage blocks. */
public final class Storage {
	private Storage() {
	}

	/** The container at {@code pos}, treating double chests as one container. */
	public static @Nullable Container containerAt(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		return level.getBlockEntity(pos) instanceof Container container ? container : null;
	}

	/** Moves matching stacks from {@code from} into {@code to}; whatever does not fit stays behind. */
	public static void moveItems(Container from, Container to, Predicate<ItemStack> filter) {
		for (int i = 0; i < from.getContainerSize(); i++) {
			ItemStack stack = from.getItem(i);
			if (!stack.isEmpty() && filter.test(stack)) {
				from.setItem(i, HopperBlockEntity.addItem(null, to, stack, null));
			}
		}
		from.setChanged();
		to.setChanged();
	}

	/** Moves up to {@code max} matching items from {@code from} into {@code to}; returns how many were moved. */
	public static int moveItems(Container from, Container to, Predicate<ItemStack> filter, int max) {
		int moved = 0;
		for (int i = 0; i < from.getContainerSize() && moved < max; i++) {
			ItemStack stack = from.getItem(i);
			if (!stack.isEmpty() && filter.test(stack)) {
				ItemStack taken = stack.split(max - moved);
				int count = taken.getCount();
				ItemStack rest = HopperBlockEntity.addItem(null, to, taken, null);
				moved += count - rest.getCount();
				if (!rest.isEmpty()) {
					// Whatever did not fit goes back where it came from.
					if (stack.isEmpty()) {
						from.setItem(i, rest);
					} else {
						stack.grow(rest.getCount());
					}
					break;
				}
			}
		}
		from.setChanged();
		to.setChanged();
		return moved;
	}

	/** How many of each item the container holds. */
	public static Map<Item, Integer> stock(Container container) {
		Map<Item, Integer> stock = new HashMap<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty()) {
				stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return stock;
	}

	/** The colony's storage chests by position, the core not included. */
	public static Map<BlockPos, Container> storageChests(ServerLevel level, Colony colony) {
		Map<BlockPos, Container> chests = new LinkedHashMap<>();
		colony.storage().ifPresent(storage -> storage.chestSpots().forEach(spot -> {
			Container chest = containerAt(level, spot);
			if (chest != null) {
				chests.put(spot, chest);
			}
		}));
		return chests;
	}

	/** The chests at the mineshaft landings by position, those placed so far. */
	public static Map<BlockPos, Container> landingChests(ServerLevel level, Colony colony) {
		Map<BlockPos, Container> chests = new LinkedHashMap<>();
		colony.mineshaft().ifPresent(shaft -> shaft.levels().forEach(y -> {
			Container chest = containerAt(level, shaft.landingChest(y));
			if (chest != null) {
				chests.put(shaft.landingChest(y), chest);
			}
		}));
		return chests;
	}

	/**
	 * Everything the colony has: the core, its storage chests and the mineshaft landing chests,
	 * whose haul a hauler brings up. Use this to decide what the colony owns; bots still only take
	 * items from the core.
	 */
	public static Map<Item, Integer> colonyStock(ServerLevel level, Colony colony) {
		List<Map<Item, Integer>> stocks = new ArrayList<>();
		Container core = containerAt(level, colony.core());
		if (core != null) {
			stocks.add(stock(core));
		}
		storageChests(level, colony).values().forEach(chest -> stocks.add(stock(chest)));
		landingChests(level, colony).values().forEach(chest -> stocks.add(stock(chest)));
		return StockPlanner.total(stocks);
	}

	/** What the core should hold for work under way: the ingredients open orders use up from {@code stock}, and build materials. */
	public static Map<Item, Integer> neededInCore(ServerLevel level, Colony colony, Map<Item, Integer> stock) {
		TaskPlanner.RecipeBook<Item> book = GameRecipeBook.of(level.getServer());
		List<Map<Item, Integer>> needs = new ArrayList<>();
		colony.orders().forEach((item, count) -> {
			Map<Item, Integer> available = new HashMap<>(stock);
			available.remove(item);
			needs.add(Stockkeeping.ingredients(TaskPlanner.plan(item, count, available, book, Gatherables::isGatherable).crafts(), available));
		});
		colony.builds().forEach(plan -> needs.add(plan.materials(level::getBlockState)));
		return StockPlanner.total(needs);
	}

	/** Whether some of {@code stack} would fit in the container. */
	public static boolean hasRoomFor(Container container, ItemStack stack) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack slot = container.getItem(i);
			if (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	/** Whether the container holds every ingredient for {@code times} crafts of the recipe. */
	public static boolean canCraft(Container container, TaskPlanner.Recipe<Item> recipe, int times) {
		Map<Item, Integer> stock = stock(container);
		for (Map.Entry<Set<Item>, Integer> ingredient : recipe.ingredients().entrySet()) {
			int needed = ingredient.getValue() * times;
			for (Item option : ingredient.getKey()) {
				int taken = Math.min(needed, stock.getOrDefault(option, 0));
				stock.merge(option, -taken, Integer::sum);
				needed -= taken;
			}
			if (needed > 0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether the result of one craft fits in the container once the ingredients are taken out, so a
	 * craft that uses up a whole stack has that slot for its result.
	 */
	public static boolean fits(Container container, TaskPlanner.Recipe<Item> recipe) {
		List<ItemStack> slots = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			slots.add(container.getItem(i).copy());
		}
		takeIngredients(slots, recipe);
		ItemStack result = new ItemStack(recipe.output(), recipe.count());
		int room = 0;
		for (ItemStack slot : slots) {
			if (slot.isEmpty()) {
				room += result.getMaxStackSize();
			} else if (ItemStack.isSameItemSameComponents(slot, result)) {
				room += Math.max(0, slot.getMaxStackSize() - slot.getCount());
			}
		}
		return room >= result.getCount();
	}

	/**
	 * Takes the ingredients for one craft out of the container and puts the result in. Does nothing
	 * and returns false if the container lacks an ingredient or has no room for the result.
	 */
	public static boolean craft(Container container, TaskPlanner.Recipe<Item> recipe) {
		if (!canCraft(container, recipe, 1) || !fits(container, recipe)) {
			return false;
		}
		List<ItemStack> slots = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			slots.add(container.getItem(i));
		}
		takeIngredients(slots, recipe);
		HopperBlockEntity.addItem(null, container, new ItemStack(recipe.output(), recipe.count()), null);
		container.setChanged();
		return true;
	}

	/** Shrinks the stacks by the ingredients of one craft, first slots first. */
	private static void takeIngredients(List<ItemStack> slots, TaskPlanner.Recipe<Item> recipe) {
		for (Map.Entry<Set<Item>, Integer> ingredient : recipe.ingredients().entrySet()) {
			int needed = ingredient.getValue();
			for (int i = 0; i < slots.size() && needed > 0; i++) {
				ItemStack stack = slots.get(i);
				if (ingredient.getKey().contains(stack.getItem())) {
					needed -= stack.split(needed).getCount();
				}
			}
		}
	}

	/** Removes up to {@code count} of an item; returns how many were removed. */
	public static int take(Container container, Item item, int count) {
		int taken = 0;
		for (int i = 0; i < container.getContainerSize() && taken < count; i++) {
			ItemStack stack = container.getItem(i);
			if (stack.is(item)) {
				taken += stack.split(count - taken).getCount();
			}
		}
		container.setChanged();
		return taken;
	}
}
