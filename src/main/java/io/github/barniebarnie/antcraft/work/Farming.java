package io.github.barniebarnie.antcraft.work;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

	/** Ground a hoe turns into farmland. */
	public static boolean isTillable(BlockState state) {
		return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH);
	}

	/** Whether a crop can grow above this block once it is tilled. */
	public static boolean isSoil(BlockState state) {
		return isTillable(state) || state.is(Blocks.FARMLAND);
	}

	/** Whether a crop fits here: the block is empty, a plant that is cleared away, or a crop already. */
	public static boolean isOpen(BlockState state) {
		return state.getFluidState().isEmpty() && (state.canBeReplaced() || state.getBlock() instanceof CropBlock);
	}

	/** Grass that drops seeds when broken. */
	public static boolean isSeedGrass(BlockState state) {
		return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS);
	}
}
