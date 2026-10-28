package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
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
