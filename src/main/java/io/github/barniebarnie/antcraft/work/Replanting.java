package io.github.barniebarnie.antcraft.work;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;

/** Where felled trees get a sapling planted. */
public final class Replanting {
	private Replanting() {
	}

	/** Whether a sapling can be planted at {@code base}: it is empty and stands on dirt. */
	public static boolean isPlantable(BlockGetter level, BlockPos base) {
		return level.getBlockState(base).isAir() && level.getBlockState(base.below()).is(BlockTags.DIRT);
	}
}
