package io.github.barniebarnie.antcraft.work;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Dug branches a colony sends a miner back to, for the ores left in their walls because its pickaxe
 * was too weak: when the colony's best pickaxe gets better, the dug branches whose walls hold ores
 * the new one harvests and the colony wants are posted again, until a miner has done them. Not saved:
 * after a reload the first survey looks at the dug branches once more.
 */
public final class OreRevisit {
	/** The best pickaxe the colony has had, or null before the first survey. */
	private @Nullable Item best;
	/** The starts of the branches posted again, in the order they were found. */
	private final Set<BlockPos> branches = new LinkedHashSet<>();

	/**
	 * The starts of the branches to keep posted for their wall ores, given the colony's {@code best}
	 * pickaxe now. Branches whose job is no longer {@code posted} were done and are dropped. When
	 * {@code best} beats every pickaxe the colony had before, the {@code dug} branches whose walls
	 * (see {@link Mineshaft.Branch#walls}) hold an ore it harvests, that the colony wants and that is
	 * {@code minable}, are added; {@code dug} and {@code wanted} are only asked for then.
	 */
	public Set<BlockPos> update(@Nullable Item best, Predicate<BlockPos> posted, Supplier<List<Mineshaft.Branch>> dug,
			Function<BlockPos, BlockState> world, Predicate<BlockPos> minable, Supplier<Predicate<Item>> wanted) {
		this.branches.removeIf(start -> !posted.test(start));
		if (best != null && ToolKind.PICKAXE.rank(best) > ToolKind.PICKAXE.rank(this.best)) {
			this.best = best;
			Predicate<Item> want = wanted.get();
			for (Mineshaft.Branch branch : dug.get()) {
				if (branch.walls(world).stream().anyMatch(pos -> isWantedOre(world.apply(pos), best, want) && minable.test(pos))) {
					this.branches.add(branch.start());
				}
			}
		}
		return Set.copyOf(this.branches);
	}

	private static boolean isWantedOre(BlockState state, Item pickaxe, Predicate<Item> wanted) {
		return PickaxeTier.oreItem(state.getBlock()).filter(wanted).isPresent() && PickaxeTier.canHarvest(pickaxe, state);
	}
}
