package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import java.util.HashMap;
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
	 * Takes the ingredients for one craft out of the container and puts the result in.
	 * Whatever does not fit is returned for the caller to carry or drop.
	 */
	public static ItemStack craft(Container container, TaskPlanner.Recipe<Item> recipe) {
		if (!canCraft(container, recipe, 1)) {
			return ItemStack.EMPTY;
		}
		for (Map.Entry<Set<Item>, Integer> ingredient : recipe.ingredients().entrySet()) {
			int needed = ingredient.getValue();
			for (int i = 0; i < container.getContainerSize() && needed > 0; i++) {
				ItemStack stack = container.getItem(i);
				if (ingredient.getKey().contains(stack.getItem())) {
					needed -= stack.split(needed).getCount();
				}
			}
		}
		ItemStack leftover = HopperBlockEntity.addItem(null, container, new ItemStack(recipe.output(), recipe.count()), null);
		container.setChanged();
		return leftover;
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
