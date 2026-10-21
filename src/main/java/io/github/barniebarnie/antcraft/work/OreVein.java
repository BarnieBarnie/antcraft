package io.github.barniebarnie.antcraft.work;

import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobBoard;
import io.github.barniebarnie.antcraft.colony.JobType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An ore vein a miner saw a block of: that block and the ones of the same ore touching it face to face, posted as
 * one {@link JobType#MINE_VEIN} job, so a miner takes the whole vein rather than what shows in a branch wall.
 */
public final class OreVein {
	/** The most blocks of a vein one job covers; the rest is posted once it shows. */
	public static final int MAX_SIZE = 32;

	private OreVein() {
	}

	/** Whether {@code state} is an ore: of {@code c:ores}, or one {@link PickaxeTier} knows the drops of. */
	public static boolean isOre(BlockState state) {
		return state.is(ConventionalBlockTags.ORES) || PickaxeTier.oreItem(state.getBlock()).isPresent();
	}

	/**
	 * The vein of the ore at {@code seen}: it and the blocks of the same ore (dropping the same, so deepslate iron
	 * is iron) connected to it face to face, at most {@code cap}, each after one it touches, from {@code seen} on.
	 */
	public static List<BlockPos> of(BlockPos seen, Function<BlockPos, BlockState> world, int cap) {
		Item ore = kind(world.apply(seen).getBlock());
		Set<BlockPos> vein = new LinkedHashSet<>();
		Deque<BlockPos> next = new ArrayDeque<>(List.of(seen.immutable()));
		while (!next.isEmpty() && vein.size() < cap) {
			BlockPos pos = next.poll();
			if (vein.add(pos)) {
				for (Direction dir : Direction.values()) {
					BlockPos side = pos.relative(dir);
					if (!vein.contains(side) && kind(world.apply(side).getBlock()) == ore) {
						next.add(side);
					}
				}
			}
		}
		return new ArrayList<>(vein);
	}

	/**
	 * Posts a job for the vein of the ore at {@code seen}, declaring the pickaxe that harvests it, unless a vein job
	 * covers any of it already.
	 */
	public static Optional<Job> post(JobBoard board, BlockPos seen, Function<BlockPos, BlockState> world, long now) {
		Block ore = world.apply(seen).getBlock();
		return board.post(JobType.MINE_VEIN, seen, of(seen, world, MAX_SIZE), Optional.empty(), now, Optional.of(PickaxeTier.lowestFor(ore)));
	}

	private static Item kind(Block block) {
		return PickaxeTier.oreItem(block).orElse(block.asItem());
	}
}
