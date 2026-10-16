package io.github.barniebarnie.antcraft.work;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;

/** Where felled trees get a sapling planted. */
public final class Replanting {
	private Replanting() {
	}

	/** Whether a sapling can be planted at {@code base}: it is empty and stands on dirt. */
	public static boolean isPlantable(BlockGetter level, BlockPos base) {
		return level.getBlockState(base).isAir() && level.getBlockState(base.below()).is(BlockTags.DIRT);
	}

	/** The sapling that grows the tree {@code log} came from, by name (dark_oak_log: dark_oak_sapling), if there is one. */
	public static Optional<Item> saplingFor(Block log) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(log);
		String kind = id.getPath().replaceFirst("^stripped_", "").replaceFirst("_(log|wood)$", "");
		return BuiltInRegistries.ITEM.getOptional(id.withPath(kind + "_sapling"));
	}
}
