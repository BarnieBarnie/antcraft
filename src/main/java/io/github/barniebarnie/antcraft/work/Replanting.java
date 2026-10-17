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
	/**
	 * How long a felled 1x1 tree waits for a sapling of its own kind before any sapling will do: long
	 * enough for its decaying leaves to drop one, so a birch forest stays birch.
	 */
	public static final int MATCH_WAIT_TICKS = 2400;

	private Replanting() {
	}

	/**
	 * The sapling kind a felled tree's {@code spots} need at game time {@code now}, or empty when any
	 * sapling will do. A 2x2 tree only grows back from its own {@code kind}, so it always needs that. A
	 * 1x1 tree waits {@link #MATCH_WAIT_TICKS} after it was {@code felled} for its own kind, then takes any.
	 */
	public static Optional<Item> saplingNeeded(Optional<Item> kind, int spots, long felled, long now) {
		return spots > 1 || now - felled < MATCH_WAIT_TICKS ? kind : Optional.empty();
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
