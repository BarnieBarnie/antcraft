package io.github.barniebarnie.antcraft.work;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
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

	/**
	 * The sapling kind a felled tree's {@code spots} need at game time {@code now}, or empty when any
	 * sapling will do. A 2x2 tree only grows back from its own {@code kind}, so it always needs that. A
	 * 1x1 tree waits {@code waitTicks} (the {@code replantMatchWaitTicks} setting) after it was {@code felled}
	 * for its own kind, so a birch forest stays birch, then takes any.
	 */
	public static Optional<Item> saplingNeeded(Optional<Item> kind, int spots, long felled, long now, int waitTicks) {
		return spots > 1 || now - felled < waitTicks ? kind : Optional.empty();
	}

	/**
	 * The spots of a felled tree's site to plant now: all of them when they are all {@code plantable}.
	 * A 2x2 site partly built over cannot grow its tree back, so it falls back to a 1x1 replant on its
	 * first open spot. Empty when no spot is open.
	 */
	public static List<BlockPos> spotsToPlant(List<BlockPos> spots, Predicate<BlockPos> plantable) {
		List<BlockPos> open = spots.stream().filter(plantable).toList();
		return open.size() == spots.size() || open.isEmpty() ? open : List.of(open.getFirst());
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
