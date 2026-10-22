package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobBoard;
import io.github.barniebarnie.antcraft.colony.JobType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OreVeinTest {
	@BeforeAll
	static void loadTags() {
		// Which pickaxe harvests which ore comes from the vanilla tags.
		VanillaData.recipeBook();
	}

	/** Stone with {@code ore} at each of {@code at}. */
	private static Function<BlockPos, BlockState> world(Map<BlockPos, Block> at) {
		return pos -> at.getOrDefault(pos, Blocks.STONE).defaultBlockState();
	}

	@Test
	void aVeinOfFiveIronOresBehindOneVisibleBlockGivesOneVeinJobCoveringAllFive() {
		BlockPos seen = new BlockPos(0, 10, 0);
		// One block shows in the wall; the other four run on behind it, out of sight.
		List<BlockPos> vein = List.of(seen, seen.east(), seen.east(2), seen.east(2).above(), seen.east().north());
		Map<BlockPos, Block> blocks = new HashMap<>();
		vein.forEach(pos -> blocks.put(pos, Blocks.IRON_ORE));
		JobBoard board = new JobBoard();

		Job job = OreVein.post(board, seen, world(blocks), 5L, item -> true).orElseThrow();

		assertEquals(JobType.MINE_VEIN, job.type());
		assertEquals(seen, job.target());
		assertEquals(Set.copyOf(vein), Set.copyOf(job.area()));
		assertEquals(Optional.of(Items.STONE_PICKAXE), job.tool());
		// It records the ore it yields, for its priority and to drop it once that is no longer wanted.
		assertEquals(Optional.of(Items.RAW_IRON), job.item());
		// Seen again from another of its blocks, it is the same vein.
		assertTrue(OreVein.post(board, seen.east(2), world(blocks), 6L, item -> true).isEmpty());
		assertEquals(1, board.jobs().size());
	}

	@Test
	void onlyAWantedOreGetsAVeinJobAndAnOpenOneIsDroppedOnceItIsNoLongerWanted() {
		BlockPos seen = new BlockPos(0, 10, 0);
		Map<BlockPos, Block> blocks = Map.of(seen, Blocks.IRON_ORE, seen.above(5), Blocks.COAL_ORE);
		JobBoard board = new JobBoard();

		// The colony wants coal, not iron.
		assertTrue(OreVein.post(board, seen, world(blocks), 5L, Items.COAL::equals).isEmpty());
		Job coal = OreVein.post(board, seen.above(5), world(blocks), 5L, Items.COAL::equals).orElseThrow();
		assertEquals(Optional.of(Items.COAL), coal.item());

		// Coal stays wanted while mining is.
		OreVein.dropUnwanted(board, Items.COAL::equals);
		assertEquals(List.of(coal), board.jobs());
		// Mining is no longer wanted: the open vein job comes off the board.
		OreVein.dropUnwanted(board, item -> false);
		assertTrue(board.jobs().isEmpty());
	}

	@Test
	void theFloodFillStopsAtTheSizeCapAndAtOtherOreKinds() {
		BlockPos seen = new BlockPos(0, 10, 0);
		Map<BlockPos, Block> blocks = new HashMap<>();
		for (int i = 0; i < 10; i++) {
			blocks.put(seen.east(i), Blocks.IRON_ORE);
		}
		assertEquals(4, OreVein.of(seen, world(blocks), 4).size());

		// Copper beside the iron is another vein; deepslate iron is the same ore.
		blocks.put(seen.above(), Blocks.COPPER_ORE);
		blocks.put(seen.below(), Blocks.DEEPSLATE_IRON_ORE);
		List<BlockPos> vein = OreVein.of(seen, world(blocks), 32);
		assertEquals(11, vein.size());
		assertTrue(!vein.contains(seen.above()));
		// Each block of it touches one before it, so a miner digs it from the visible block in.
		assertEquals(seen, vein.getFirst());
		Set<BlockPos> before = new HashSet<>();
		for (BlockPos pos : vein) {
			assertTrue(before.isEmpty() || before.stream().anyMatch(dug -> dug.distManhattan(pos) == 1), pos + " is reached through no dug block");
			before.add(pos);
		}
	}
}
