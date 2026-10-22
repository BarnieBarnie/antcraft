package io.github.barniebarnie.antcraft.work;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where fire spreads, as {@code FireBlock.tick} does it: it burns a block that burns beside it, and
 * catches in air up to one block to the side, one below or four above that has such a block beside it.
 */
// ponytail: a chain is followed only through face-touching blocks that burn, CHAIN_STEPS deep; fire from a burning
// block that catches in air beyond them is not, nor a longer chain. Widen it if builds catch fire that way.
public final class FireSpread {
	/**
	 * How many blocks that burn, touching face to face, fire is followed through to the block placed.
	 * Three covers a wall or floor of planks next to a lit block without scanning a whole house.
	 */
	private static final int CHAIN_STEPS = 3;

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

	/**
	 * Whether placing {@code state} at {@code pos} puts a block that burns where lit fire nearby spreads
	 * to it, or to a block that burns joined to it through up to {@link #CHAIN_STEPS} such blocks. Soul fire never spreads.
	 */
	public static boolean endangers(Function<BlockPos, BlockState> world, BlockPos pos, BlockState state) {
		if (!isFlammable(state)) {
			return false;
		}
		Function<BlockPos, BlockState> after = at -> at.equals(pos) ? state : world.apply(at);
		Set<BlockPos> chain = new HashSet<>(Set.of(pos));
		List<BlockPos> edge = List.of(pos);
		for (int step = 0; step < CHAIN_STEPS; step++) {
			List<BlockPos> next = new ArrayList<>();
			for (BlockPos at : edge) {
				for (Direction side : Direction.values()) {
					BlockPos near = at.relative(side);
					if (!chain.contains(near) && isFlammable(world.apply(near))) {
						chain.add(near);
						next.add(near);
					}
				}
			}
			edge = next;
		}
		BoundingBox box = BoundingBox.encapsulatingPositions(chain).orElseThrow();
		// Only fire that reaches the chain counts, not one that reaches some other block that burns already.
		// A fire it is in reach of is at most one block beyond the air it catches in.
		for (BlockPos fire : BlockPos.betweenClosed(box.minX() - 2, box.minY() - 5, box.minZ() - 2, box.maxX() + 2, box.maxY() + 2, box.maxZ() + 2)) {
			if (world.apply(fire).getBlock() instanceof FireBlock && reaches(after, fire.immutable(), chain::contains)) {
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
