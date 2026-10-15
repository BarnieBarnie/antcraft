package io.github.barniebarnie.antcraft.work;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
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
}
