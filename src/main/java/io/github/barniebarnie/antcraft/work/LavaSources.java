package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.pathing.Goal;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where a bot fills a bucket with lava: a source with a safe spot within reach, one where it stands on solid ground with
 * room for its feet and head, and neither of those cells holds a fluid or has lava beside, above or below it. So it
 * fills the bucket from the rim of a pool, never standing in, over or next to lava that could flow onto it.
 */
public final class LavaSources {
	/** A source to fill a bucket at, and the safe spot nearest the bot to fill it from. */
	public record Fill(BlockPos source, BlockPos spot) {
	}

	private LavaSources() {
	}

	/**
	 * Of {@code sources}, the one nearest {@code from} that has a safe spot within {@code reach} (see {@link #isSafeSpot}),
	 * with the spot nearest {@code from}; empty if none has one.
	 */
	public static Optional<Fill> choose(Function<BlockPos, BlockState> world, BlockPos from, Collection<BlockPos> sources, double reach) {
		return sources.stream().sorted(Comparator.comparingDouble(source -> source.distSqr(from)))
			.flatMap(source -> spot(world, from, source, reach).map(spot -> new Fill(source, spot)).stream())
			.findFirst();
	}

	/** The safe spot within {@code reach} of {@code source} nearest {@code from}. */
	private static Optional<BlockPos> spot(Function<BlockPos, BlockState> world, BlockPos from, BlockPos source, double reach) {
		int r = (int) Math.ceil(reach) + 1;
		Goal within = new Goal.Reach(source, reach);
		BlockPos best = null;
		for (BlockPos pos : BlockPos.betweenClosed(source.offset(-r, -r, -r), source.offset(r, r, r))) {
			if ((best == null || pos.distSqr(from) < best.distSqr(from)) && within.isReached(pos) && isSafeSpot(world, pos)) {
				best = pos.immutable();
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Whether a bot with its feet at {@code feet} stands safe from lava: on a solid block that holds no fluid, with room for
	 * its feet and head, neither of which holds a fluid or has lava on any side of it, the floor included.
	 */
	public static boolean isSafeSpot(Function<BlockPos, BlockState> world, BlockPos feet) {
		BlockState floor = world.apply(feet.below());
		if (!floor.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP) || !floor.getFluidState().isEmpty()) {
			return false;
		}
		for (BlockPos cell : new BlockPos[] {feet, feet.above()}) {
			BlockState state = world.apply(cell);
			if (!state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty() || !state.getFluidState().isEmpty()) {
				return false;
			}
			for (Direction side : Direction.values()) {
				if (world.apply(cell.relative(side)).getFluidState().is(FluidTags.LAVA)) {
					return false;
				}
			}
		}
		return true;
	}
}
