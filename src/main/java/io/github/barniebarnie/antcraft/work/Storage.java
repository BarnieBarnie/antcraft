package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.StockPlanner;
import io.github.barniebarnie.antcraft.colony.StockTarget;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Moving items between a bot's inventory and storage blocks. */
public final class Storage {
	/** How far from the core a furnace counts as the colony's furnace. */
	public static final int FURNACE_RANGE = 2;
	public static final int FURNACE_INPUT = 0;
	public static final int FURNACE_FUEL = 1;
	public static final int FURNACE_RESULT = 2;

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
	 * Everything the colony has: the core, its storage chests, the mineshaft landing chests,
	 * whose haul a hauler brings up, and the output waiting in the colony furnace. Use this to decide what the colony owns; bots still only take
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
		AbstractFurnaceBlockEntity furnace = colonyFurnace(level, colony.core());
		ItemStack output = furnace == null ? ItemStack.EMPTY : furnace.getItem(FURNACE_RESULT);
		if (!output.isEmpty()) {
			stocks.add(Map.of(output.getItem(), output.getCount()));
		}
		return StockPlanner.total(stocks);
	}

	/** The colony's furnace: the first furnace within {@link #FURNACE_RANGE} of the core, or null. */
	public static @Nullable AbstractFurnaceBlockEntity colonyFurnace(ServerLevel level, BlockPos core) {
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-FURNACE_RANGE, -1, -FURNACE_RANGE), core.offset(FURNACE_RANGE, 1, FURNACE_RANGE))) {
			if (level.getBlockState(pos).is(Blocks.FURNACE) && level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
				return furnace;
			}
		}
		return null;
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

	/** What the colony's open orders want, planned against {@code stock}; see {@link Stockkeeping#wantedForOrders}. */
	public static Set<Item> wantedForOrders(ServerLevel level, Colony colony, Map<Item, Integer> stock) {
		return Stockkeeping.wantedForOrders(colony.orders(), stock, GameRecipeBook.of(level.getServer()), Gatherables::isGatherable);
	}

	/**
	 * What the colony wants from mining: what its open orders want, their ingredients included, and
	 * anything it keeps a stock target of. Plans the orders once, so make one per check.
	 */
	public static Predicate<Item> wanted(ServerLevel level, Colony colony) {
		return Stockkeeping.wanted(wantedForOrders(level, colony, colonyStock(level, colony)), targets(colony));
	}

	/** The colony's stock targets, for {@link Stockkeeping}. */
	public static List<StockPlanner.Target<Item>> targets(Colony colony) {
		return colony.stockTargets().stream().map(StockTarget::toPlannerTarget).toList();
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

	/** The storage chest for {@code stack}: one of its category with room for it, else an empty one; null if none. */
	public static @Nullable BlockPos chestFor(Map<BlockPos, Container> chests, ItemStack stack) {
		StorageCategory category = StorageCategory.of(stack);
		BlockPos empty = null;
		for (Map.Entry<BlockPos, Container> chest : chests.entrySet()) {
			Container container = chest.getValue();
			if (container.isEmpty()) {
				empty = empty == null ? chest.getKey() : empty;
			} else if (category(container) == category && hasRoomFor(container, stack)) {
				return chest.getKey();
			}
		}
		return empty;
	}

	/** A storage chest holds the category of whatever is in it first. */
	public static StorageCategory category(Container chest) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (!chest.getItem(i).isEmpty()) {
				return StorageCategory.of(chest.getItem(i));
			}
		}
		return StorageCategory.MISC;
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
