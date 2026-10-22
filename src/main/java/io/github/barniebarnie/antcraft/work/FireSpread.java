package io.github.barniebarnie.antcraft.work;

import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where fire spreads, as {@code FireBlock.tick} does it: it burns a block that burns beside it, and
 * catches in air up to one block to the side, one below or four above that has such a block beside it.
 */
// ponytail: one step of spread; a chain through a flammable block out of reach that the new fire burns is not followed.
public final class FireSpread {
	private FireSpread() {
	}

	/** Whether fire at {@code fire} burns or spreads to a block that burns. */
	public static boolean reachesFlammable(Function<BlockPos, BlockState> world, BlockPos fire) {
		return reaches(world, fire, at -> isFlammable(world.apply(at)));
	}

	/** Whether fire at {@code fire} burns or spreads to a block {@code target} picks. */
	private static boolean reaches(Function<BlockPos, BlockState> world, BlockPos fire, Predicate<BlockPos> target) {
		for (Direction side : Direction.values()) {
			if (target.test(fire.relative(side))) {
				return true;
			}
		}
		for (BlockPos air : BlockPos.betweenClosed(fire.offset(-1, -1, -1), fire.offset(1, 4, 1))) {
			if (!air.equals(fire) && world.apply(air).isAir()) {
				for (Direction side : Direction.values()) {
					if (target.test(air.relative(side))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Whether placing {@code state} at {@code pos} puts a block that burns where lit fire nearby spreads to it. */
	public static boolean endangers(Function<BlockPos, BlockState> world, BlockPos pos, BlockState state) {
		if (!isFlammable(state)) {
			return false;
		}
		Function<BlockPos, BlockState> after = at -> at.equals(pos) ? state : world.apply(at);
		// Only fire that reaches this block counts, not one that reaches some other block that burns already.
		// A fire it is in reach of is at most one block beyond the air it catches in.
		for (BlockPos fire : BlockPos.betweenClosed(pos.offset(-2, -5, -2), pos.offset(2, 2, 2))) {
			if (world.apply(fire).getBlock() instanceof BaseFireBlock && reaches(after, fire.immutable(), pos::equals)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isFlammable(BlockState state) {
		FlammableBlockRegistry.Entry entry = FlammableBlockRegistry.getDefaultInstance().get(state.getBlock());
		return entry != null && (entry.getIgniteOdds() > 0 || entry.getBurnOdds() > 0);
	}
}
