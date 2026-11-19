package io.github.barniebarnie.antcraft.work;

import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Moving items between containers, such as a bot's inventory and a chest, and crafting from what a container holds. */
public final class Storage {
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

	/**
	 * Puts {@code stack} into the container, topping up stacks of the same item first and only then
	 * filling empty slots, unlike a hopper, which takes the first slot either way and so splits a stack
	 * (#763). Shrinks {@code stack} by what fit and returns it: the rest.
	 */
	public static ItemStack addItem(Container to, ItemStack stack) {
		for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
			for (int i = 0; i < to.getContainerSize() && !stack.isEmpty(); i++) {
				ItemStack slot = to.getItem(i);
				boolean fits = pass == 0 ? ItemStack.isSameItemSameComponents(slot, stack) : slot.isEmpty();
				if (!fits || !to.canPlaceItem(i, stack)) {
					continue;
				}
				int n = Math.min(stack.getCount(), to.getMaxStackSize(stack) - slot.getCount());
				if (n <= 0) {
					continue;
				}
				if (slot.isEmpty()) {
					to.setItem(i, stack.split(n));
				} else {
					slot.grow(n);
					stack.shrink(n);
				}
			}
		}
		return stack;
	}

	/** Moves matching stacks from {@code from} into {@code to}; whatever does not fit stays behind. */
	public static void moveItems(Container from, Container to, Predicate<ItemStack> filter) {
		for (int i = 0; i < from.getContainerSize(); i++) {
			ItemStack stack = from.getItem(i);
			if (!stack.isEmpty() && filter.test(stack)) {
				from.setItem(i, addItem(to, stack));
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
				ItemStack rest = addItem(to, taken);
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

	/** How many of {@code stack}'s item (with its components) fit in the container, as a hopper fills it. */
	public static long room(Container container, ItemStack stack) {
		long room = 0;
		int max = container.getMaxStackSize(stack);
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack slot = container.getItem(i);
			if (slot.isEmpty() && container.canPlaceItem(i, stack)) {
				room += max;
			} else if (ItemStack.isSameItemSameComponents(slot, stack)) {
				room += Math.max(0, max - slot.getCount());
			}
		}
		return room;
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
		return fits(container, recipe, Map.of());
	}

	/** {@link #fits(Container, TaskPlanner.Recipe)}, the ingredients taken as {@link #craft(Container, TaskPlanner.Recipe, Map)} takes them. */
	public static boolean fits(Container container, TaskPlanner.Recipe<Item> recipe, Map<Item, Integer> use) {
		List<ItemStack> slots = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			slots.add(container.getItem(i).copy());
		}
		takeIngredients(slots, recipe, new LinkedHashMap<>(use));
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
		return craft(container, recipe, Map.of());
	}

	/** {@link #craft(Container, TaskPlanner.Recipe, Map)} with no limit on how many of each of {@code use} it takes. */
	public static boolean craft(Container container, TaskPlanner.Recipe<Item> recipe, List<Item> use) {
		return craft(container, recipe, unlimited(use));
	}

	/** Each of {@code use}, in its order, with no limit on how many of it to take. */
	public static Map<Item, Integer> unlimited(List<Item> use) {
		Map<Item, Integer> unlimited = new LinkedHashMap<>();
		use.forEach(item -> unlimited.put(item, Integer.MAX_VALUE));
		return unlimited;
	}

	/**
	 * {@link #craft(Container, TaskPlanner.Recipe)}, but of interchangeable items those of {@code use} first, in its order
	 * (#930): what a plan made its crafting table of, not what there is most of. Each at most its count in {@code use},
	 * which this counts down, so over the crafts of one call a plan's three oak planks and one birch take no fourth oak
	 * kept for a later step (#952); once the counts are spent, the rest as without use.
	 */
	public static boolean craft(Container container, TaskPlanner.Recipe<Item> recipe, Map<Item, Integer> use) {
		if (!canCraft(container, recipe, 1) || !fits(container, recipe, use)) {
			return false;
		}
		List<ItemStack> slots = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			slots.add(container.getItem(i));
		}
		takeIngredients(slots, recipe, use);
		addItem(container, new ItemStack(recipe.output(), recipe.count()));
		container.setChanged();
		return true;
	}

	/**
	 * Shrinks the stacks by the ingredients of one craft: of interchangeable items, the one there is most of first, as
	 * the planner takes them (TaskPlanner needAny), so a craft leaves what the plan keeps for a later step (#925); each
	 * from the first slots first. Those of {@code use} come before the rest, in its order (#930), first each up to its
	 * count, which is counted down (#952).
	 */
	private static void takeIngredients(List<ItemStack> slots, TaskPlanner.Recipe<Item> recipe, Map<Item, Integer> use) {
		Map<Item, Integer> stock = new HashMap<>();
		slots.forEach(stack -> stock.merge(stack.getItem(), stack.getCount(), Integer::sum));
		List<Item> order = new ArrayList<>(use.keySet());
		for (Map.Entry<Set<Item>, Integer> ingredient : recipe.ingredients().entrySet()) {
			int needed = ingredient.getValue();
			List<Item> byStock = new ArrayList<>(ingredient.getKey());
			byStock.sort(Comparator.comparingInt((Item option) -> order.contains(option) ? order.indexOf(option) : order.size())
				.thenComparing(Comparator.comparingInt((Item option) -> stock.getOrDefault(option, 0)).reversed()));
			for (boolean limited : new boolean[] {true, false}) {
				for (Item option : byStock) {
					int wanted = Math.min(needed, limited ? use.getOrDefault(option, 0) : needed);
					int taken = 0;
					for (int i = 0; i < slots.size() && taken < wanted; i++) {
						ItemStack stack = slots.get(i);
						if (stack.is(option)) {
							taken += stack.split(wanted - taken).getCount();
						}
					}
					needed -= taken;
					int left = use.getOrDefault(option, Integer.MAX_VALUE);
					if (taken > 0 && left != Integer.MAX_VALUE) {
						use.put(option, Math.max(0, left - taken));
					}
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
