package io.github.barniebarnie.antcraft.work;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class Farming {
	private Farming() {
	}

	public static boolean isRipe(BlockState state) {
		return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
	}

	/** Whether planting this item grows {@code crop} (wheat seeds for wheat, a carrot for carrots). */
	public static boolean plants(ItemStack stack, Block crop) {
		return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == crop;
	}

	/** Items that plant a crop; farmers keep some of these. */
	public static boolean isSeed(ItemStack stack) {
		return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof CropBlock;
	}
}
